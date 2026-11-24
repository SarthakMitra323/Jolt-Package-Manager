package dev.jolt;

import dev.jolt.cache.BinaryMetaCache;
import dev.jolt.cli.CliConfig;
import dev.jolt.cli.RequirementsParser;
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
                case INSTALL, SYNC -> runInstall(config, env, cacheRoot, false);
                case UPDATE -> runInstall(config, env, cacheRoot, true);
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

        Path reqFile = (config.requirementsFile != null ? config.requirementsFile : Path.of("requirements.txt"))
                .toAbsolutePath().normalize();

        if (!Files.isRegularFile(reqFile)) {
            if (!config.quiet) {
                System.out.println("No requirements.txt found. Project environment is ready.");
            }
            return;
        }

        List<String> specs = RequirementsParser.parseFile(reqFile);
        if (specs.isEmpty()) {
            if (!config.quiet) {
                System.out.println("Found empty " + reqFile.getFileName() + ". Project environment is ready.");
            }
            return;
        }

        if (!config.quiet) {
            System.out.printf("Detected %s (%d requirements) -> installing into %s...%n",
                    reqFile.getFileName(), specs.size(), env.rootDir().getFileName());
        }

        Path cacheRoot = resolveDefaultCacheDir(env);
        installSpecs(specs, config, env, cacheRoot, false);
    }

    private static void runInstall(
            CliConfig config,
            PythonEnvironment env,
            Path cacheRoot,
            boolean isUpdate) throws IOException {

        List<String> specs = new ArrayList<>(config.targets);
        if (config.requirementsFile != null) {
            specs.addAll(RequirementsParser.parseFile(config.requirementsFile));
        }

        if (specs.isEmpty() && isUpdate) {
            for (InstalledDist d : EnvironmentManager.listInstalled(env)) {
                specs.add(d.name());
            }
        }

        if (specs.isEmpty()) {
            throw new IllegalArgumentException("Please provide at least one package or -r <requirements.txt>");
        }

        installSpecs(specs, config, env, cacheRoot, isUpdate);
    }

    private static void installSpecs(
            List<String> specs,
            CliConfig config,
            PythonEnvironment env,
            Path cacheRoot,
            boolean isUpdate) throws IOException {

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
                  init [reqs.txt]        Create .venv and install requirements.txt in one step
                  venv [path]            Create a new virtual environment (default: .venv)
                  install <pkg...>       Install packages into active virtual environment
                  uninstall <pkg...>     Remove packages from active virtual environment
                  sync <reqs.txt>        Synchronize environment with requirements.txt
                  update [pkg...]        Update all or specified packages
                  list                   List installed packages
                  freeze                 Output installed packages in requirements format
                  cache clear            Purge global metadata and unpacked wheel caches
                  self-update            Update fast.exe from GitHub Releases (SHA-256 verified)
                """);
    }
}