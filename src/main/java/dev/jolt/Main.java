package dev.jolt;

import dev.jolt.cache.BinaryMetaCache;
import dev.jolt.cli.CliConfig;
import dev.jolt.cli.PyprojectParser;
import dev.jolt.cli.RequirementsParser;
import dev.jolt.cli.RequirementsParser.ParsedRequirements;
import dev.jolt.cli.SelfUpdater;
import dev.jolt.env.PythonEnvironment;
import dev.jolt.env.VenvCreator;
import dev.jolt.installer.DownloadEngine;
import dev.jolt.installer.DownloadEngine.UnpackedPackage;
import dev.jolt.installer.EnvironmentManager;
import dev.jolt.installer.EnvironmentManager.InstalledDist;
import dev.jolt.installer.StreamingUnpacker;
import dev.jolt.installer.WheelInstaller;
import dev.jolt.net.PyPIClient;
import dev.jolt.resolver.PubGrubSolver;
import dev.jolt.resolver.PubGrubSolver.ResolvedPackage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Main {
    static {
        System.setProperty("java.net.preferIPv4Stack", "true");
    }

    public static void main(String[] args) {
        final long startNanos = System.nanoTime();
        SelfUpdater.cleanupOldBinary();
        try {
            CliConfig config = CliConfig.parse(args);
            if (config.command == CliConfig.Command.HELP) {
                printHelp();
                return;
            }

            if (config.command == CliConfig.Command.SELF_UPDATE) {
                SelfUpdater.run(config.quiet);
                if (!config.quiet) {
                    double ms = (System.nanoTime() - startNanos) / 1_000_000.0;
                    System.out.printf("\u001B[32m[OK]\u001B[0m Completed in %.2f ms%n", ms);
                }
                System.exit(0);
                return;
            }

            if (config.command == CliConfig.Command.VENV) {
                Path target = config.targets.isEmpty() ? Path.of(".venv") : Path.of(config.targets.get(0));
                PythonEnvironment created = VenvCreator.create(target);
                if (!config.quiet) {
                    System.out.printf("Created virtual environment at %s (Python %d.%d.%d)%n",
                            created.rootDir(), created.major(), created.minor(), created.patch());
                    double ms = (System.nanoTime() - startNanos) / 1_000_000.0;
                    System.out.printf("\u001B[32m[OK]\u001B[0m Completed in %.2f ms%n", ms);
                }
                return;
            }

            if (config.command == CliConfig.Command.INIT) {
                runInit(config);
                if (!config.quiet) {
                    double ms = (System.nanoTime() - startNanos) / 1_000_000.0;
                    System.out.printf("\u001B[32m[OK]\u001B[0m Completed in %.2f ms%n", ms);
                }
                System.exit(0);
                return;
            }

            PythonEnvironment env = (config.command == CliConfig.Command.CACHE_CLEAR)
                    ? detectOptionalEnv(config.venvPath)
                    : PythonEnvironment.detect(config.venvPath);

            Path cacheRoot = resolveDefaultCacheDir(env);

            if (config.command == CliConfig.Command.CACHE_CLEAR) {
                StreamingUnpacker.deleteRecursively(cacheRoot);
                if (!config.quiet) {
                    System.out.println("Cleared jolt cache at " + cacheRoot);
                }
                return;
            }

            if (config.verbose && env != null) {
                long envTimeUs = (System.nanoTime() - startNanos) / 1_000;
                System.err.printf("[diag] Detected Python %d.%d.%d (%s) at %s in %d us%n",
                        env.major(), env.minor(), env.patch(),
                        env.primaryPlatformTag(), env.rootDir(), envTimeUs);
            }

            switch (config.command) {
                case INSTALL -> runInstall(config, env, cacheRoot, false, false);
                case SYNC    -> runInstall(config, env, cacheRoot, false, true);
                case UPDATE  -> runInstall(config, env, cacheRoot, true, false);
                case UNINSTALL -> EnvironmentManager.uninstallPackages(env, config.targets, config.quiet);
                case LIST -> {
                    List<InstalledDist> dists = EnvironmentManager.listInstalled(env);
                    System.out.printf("%-30s %-15s%n", "Package", "Version");
                    System.out.println("------------------------------ ---------------");
                    for (InstalledDist d : dists) {
                        System.out.printf("%-30s %-15s%n", d.name(), d.version());
                    }
                }
                case FREEZE -> {
                    for (InstalledDist d : EnvironmentManager.listInstalled(env)) {
                        System.out.println(d.name() + "==" + d.version());
                    }
                }
                default -> printHelp();
            }

            if (!config.quiet && config.command != CliConfig.Command.FREEZE) {
                double elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0;
                System.out.printf("\u001B[32m[OK]\u001B[0m Completed in %.2f ms%n", elapsedMs);
            }
            System.exit(0);
        } catch (Exception e) {
            System.err.println("\u001B[31merror:\u001B[0m " + e.getMessage());
            System.exit(1);
        }
    }

    private static void runInit(CliConfig config) throws IOException {
        Path targetVenv = (config.venvPath != null ? config.venvPath : Path.of(".venv")).toAbsolutePath().normalize();
        PythonEnvironment env;

        if (Files.isDirectory(targetVenv) && Files.isRegularFile(targetVenv.resolve("pyvenv.cfg"))) {
            env = PythonEnvironment.detect(targetVenv);
            if (!config.quiet) {
                System.out.printf("Using existing virtual environment at %s (Python %d.%d.%d)%n",
                        env.rootDir(), env.major(), env.minor(), env.patch());
            }
        } else {
            env = VenvCreator.create(targetVenv);
            if (!config.quiet) {
                System.out.printf("Created virtual environment at %s (Python %d.%d.%d)%n",
                        env.rootDir(), env.major(), env.minor(), env.patch());
            }
        }

        boolean wantsExtras = config.allExtras || !config.extras.isEmpty();
        Path depFile;
        boolean isPyproject;

        if (config.requirementsFile != null) {
            depFile = config.requirementsFile.toAbsolutePath().normalize();
            if (!Files.isRegularFile(depFile)) {
                throw new IOException("Dependency file not found: " + depFile);
            }
            isPyproject = depFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".toml");
            if (wantsExtras && !isPyproject) {
                throw new IllegalArgumentException("--extra / --all-extras is only supported with pyproject.toml");
            }
        } else {
            Path reqTxt = Path.of("requirements.txt").toAbsolutePath().normalize();
            Path pyproject = Path.of("pyproject.toml").toAbsolutePath().normalize();
            if (wantsExtras) {
                if (!Files.isRegularFile(pyproject)) {
                    throw new IllegalArgumentException("--extra / --all-extras requires a pyproject.toml in the current directory");
                }
                depFile = pyproject;
                isPyproject = true;
            } else if (Files.isRegularFile(reqTxt)) {
                depFile = reqTxt;
                isPyproject = false;
            } else if (Files.isRegularFile(pyproject)) {
                depFile = pyproject;
                isPyproject = true;
            } else {
                if (!config.quiet) {
                    System.out.println("No requirements.txt or pyproject.toml found. Project environment is ready.");
                }
                return;
            }
        }

        List<String> specs;
        Map<String, Set<String>> allowedHashes = Map.of();
        boolean hashMode = false;

        if (isPyproject) {
            specs = PyprojectParser.parseDependencies(depFile, config.extras, config.allExtras);
        } else {
            ParsedRequirements parsedReqs = RequirementsParser.parseFileWithHashes(depFile);
            specs = parsedReqs.specs();
            allowedHashes = parsedReqs.allowedHashesByPkg();
            hashMode = parsedReqs.hashCheckingMode();
        }

        if (specs.isEmpty()) {
            if (!config.quiet) {
                System.out.println("No dependencies found in " + depFile.getFileName() + ". Project environment is ready.");
            }
            return;
        }

        if (!config.quiet) {
            String extraSuffix = "";
            if (config.allExtras) {
                extraSuffix = ", all extras";
            } else if (!config.extras.isEmpty()) {
                extraSuffix = ", extras: " + config.extras;
            }
            System.out.printf("Detected %s (%d requirements%s) -> installing into %s...%n",
                    depFile.getFileName(), specs.size(), extraSuffix, env.rootDir().getFileName());
        }

        Path cacheRoot = resolveDefaultCacheDir(env);
        installSpecs(specs, config, env, cacheRoot, false, false, allowedHashes, hashMode);
    }

    private static void runInstall(
            CliConfig config,
            PythonEnvironment env,
            Path cacheRoot,
            boolean isUpdate,
            boolean isSync) throws IOException {

        List<String> specs = new ArrayList<>(config.targets);
        Map<String, Set<String>> allowedHashes = Map.of();
        boolean hashMode = false;

        if (config.requirementsFile != null) {
            if (config.requirementsFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".toml")) {
                specs.addAll(PyprojectParser.parseDependencies(config.requirementsFile, config.extras, config.allExtras));
            } else {
                ParsedRequirements parsedReqs = RequirementsParser.parseFileWithHashes(config.requirementsFile);
                specs.addAll(parsedReqs.specs());
                allowedHashes = parsedReqs.allowedHashesByPkg();
                hashMode = parsedReqs.hashCheckingMode();
            }
        }

        if (specs.isEmpty() && isUpdate) {
            for (InstalledDist d : EnvironmentManager.listInstalled(env)) {
                specs.add(d.name());
            }
        }

        if (specs.isEmpty()) {
            if (isSync) {
                pruneExtraneousPackages(env, Set.of(), config.quiet);
                return;
            }
            throw new IllegalArgumentException("Please provide at least one package or -r <requirements.txt>");
        }

        installSpecs(specs, config, env, cacheRoot, isUpdate, isSync, allowedHashes, hashMode);
    }

    private static void installSpecs(
            List<String> specs,
            CliConfig config,
            PythonEnvironment env,
            Path cacheRoot,
            boolean isUpdate,
            boolean isSync,
            Map<String, Set<String>> allowedHashes,
            boolean hashCheckingMode) throws IOException {

        Set<String> explicitNames = new HashSet<>(specs.size());
        for (String s : specs) {
            var parsed = dev.jolt.pep.Pep508Requirement.parse(s);
            if (parsed != null) explicitNames.add(parsed.name());
        }

        StreamingUnpacker unpacker = new StreamingUnpacker(cacheRoot);
        BinaryMetaCache metaCache = new BinaryMetaCache(cacheRoot, config.noCache || isUpdate);
        PyPIClient pypi = new PyPIClient(config.maxConcurrency);
        DownloadEngine downloader = new DownloadEngine(unpacker, config.maxConcurrency);

        long resolveStart = System.nanoTime();
        PubGrubSolver solver = new PubGrubSolver(pypi, env, metaCache);
        List<ResolvedPackage> resolved = solver.resolve(specs);
        long resolveMs = (System.nanoTime() - resolveStart) / 1_000_000;

        if (!config.quiet) {
            System.out.printf("Resolved %d packages in %d ms%n", resolved.size(), resolveMs);
        }

        // Strict --hash lockfile enforcement BEFORE downloading or linking
        if (hashCheckingMode) {
            for (ResolvedPackage rp : resolved) {
                String norm = RequirementsParser.normalizePkgName(rp.name());
                Set<String> allowed = allowedHashes.get(norm);
                if (allowed == null || allowed.isEmpty()) {
                    throw new SecurityException(
                            "Hash-checking mode is active, but no --hash was provided for resolved package: "
                                    + rp.name() + "==" + rp.version());
                }
                String actualSha256 = rp.wheelArtifact().sha256();
                if (actualSha256 == null || !allowed.contains(actualSha256.toLowerCase(Locale.ROOT))) {
                    throw new SecurityException(
                            "SECURITY ABORT: Hash mismatch for " + rp.name() + "==" + rp.version()
                                    + "\n  Actual wheel SHA-256: " + actualSha256
                                    + "\n  Allowed lockfile hashes: " + allowed);
                }
            }
            if (!config.quiet) {
                System.out.printf("Verified lockfile SHA-256 hashes for %d packages%n", resolved.size());
            }
        }

        long dlStart = System.nanoTime();
        List<UnpackedPackage> unpacked = downloader.downloadAndUnpackAll(resolved);
        long dlMs = (System.nanoTime() - dlStart) / 1_000_000;

        long cacheHits = unpacked.stream().filter(UnpackedPackage::cacheHit).count();
        if (config.verbose) {
            System.err.printf("[diag] Unpacked %d wheels (%d cache hits) in %d ms%n",
                    unpacked.size(), cacheHits, dlMs);
        }

        long linkStart = System.nanoTime();
        int filesLinked = WheelInstaller.installAll(unpacked, env, explicitNames);
        long linkMs = (System.nanoTime() - linkStart) / 1_000_000;

        if (!config.quiet) {
            if (filesLinked == 0) {
                System.out.printf("Audited %d packages in %d ms (all already satisfied)%n",
                        unpacked.size(), linkMs);
            } else {
                System.out.printf("Installed %d packages (%d files linked) in %d ms%n",
                        unpacked.size(), filesLinked, linkMs);
            }
        }

        // True 'fast sync': uninstall extraneous packages not in the resolved graph
        if (isSync) {
            Set<String> keepNormalized = new HashSet<>(resolved.size() * 2);
            for (ResolvedPackage rp : resolved) {
                keepNormalized.add(RequirementsParser.normalizePkgName(rp.name()));
            }
            pruneExtraneousPackages(env, keepNormalized, config.quiet);
        }
    }

    private static void pruneExtraneousPackages(
            PythonEnvironment env,
            Set<String> keepNormalized,
            boolean quiet) throws IOException {
        List<String> toUninstall = new ArrayList<>();
        for (InstalledDist installed : EnvironmentManager.listInstalled(env)) {
            String norm = RequirementsParser.normalizePkgName(installed.name());
            if (!keepNormalized.contains(norm)) {
                toUninstall.add(installed.name());
            }
        }
        if (!toUninstall.isEmpty()) {
            EnvironmentManager.uninstallPackages(env, toUninstall, quiet);
            if (!quiet) {
                System.out.printf("Synced environment: removed %d extraneous package(s)%n", toUninstall.size());
            }
        }
    }

    private static PythonEnvironment detectOptionalEnv(Path explicitPath) {
        try {
            return PythonEnvironment.detect(explicitPath);
        } catch (Exception e) {
            return null;
        }
    }

    private static Path resolveDefaultCacheDir(PythonEnvironment env) {
        String envCache = System.getenv("JOLT_CACHE_DIR");
        if (envCache != null && !envCache.isBlank()) return Path.of(envCache);

        Path defaultCache;
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            defaultCache = Path.of(localAppData, "jolt", "cache");
        } else {
            defaultCache = Path.of(System.getProperty("user.home", "."), ".cache", "jolt");
        }

        if (env != null) {
            Path venvRootDrive = env.rootDir().toAbsolutePath().getRoot();
            Path cacheRootDrive = defaultCache.toAbsolutePath().getRoot();
            if (venvRootDrive != null && !venvRootDrive.equals(cacheRootDrive)) {
                return venvRootDrive.resolve(".jolt-cache");
            }
        }
        return defaultCache;
    }

    private static void printHelp() {
        System.out.print("""
                jolt (fast) 1.0.0 - Ultra-Fast Next-Gen Python Package Installer
                
                USAGE:
                  fast <COMMAND> [OPTIONS] [ARGS...]
                
                COMMANDS:
                  init [file]            Create .venv and install requirements.txt or pyproject.toml
                  venv [path]            Create a new virtual environment (default: .venv)
                  install <pkg...>       Install packages into active virtual environment
                  uninstall <pkg...>     Remove packages from active virtual environment
                  sync <reqs.txt>        Synchronize environment (install missing & prune extraneous)
                  update [pkg...]        Update all or specified packages
                  list                   List installed packages
                  freeze                 Output installed packages in requirements format
                  cache clear            Purge global metadata and unpacked wheel caches
                  self-update            Update fast.exe from GitHub Releases (SHA-256 verified)
                
                OPTIONS:
                  --extra <group>        Install optional-dependencies group from pyproject.toml
                  --all-extras           Install all optional-dependencies groups from pyproject.toml
                  -r, --requirement      Install from a requirements.txt or pyproject.toml file
                  -p, --venv <path>      Target virtual environment directory
                  -q, --quiet            Suppress status output
                  -v, --verbose          Print diagnostic timing details
                """);
    }
}