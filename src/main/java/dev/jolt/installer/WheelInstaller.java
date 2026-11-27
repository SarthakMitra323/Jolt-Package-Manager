package dev.jolt.installer;

import dev.jolt.env.PythonEnvironment;
import dev.jolt.installer.DownloadEngine.UnpackedPackage;
import dev.jolt.installer.EnvironmentManager.InstalledDist;
import dev.jolt.net.PyPIClient;
import java.util.HashSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WheelInstaller {

    private WheelInstaller() {}

    public static int installAll(
            List<UnpackedPackage> packages,
            PythonEnvironment env,
            Set<String> explicitlyRequestedNames) throws IOException {

        Files.createDirectories(env.sitePackagesDir());
        Files.createDirectories(env.scriptsDir());

        List<UnpackedPackage> toInstall = new ArrayList<>(packages.size());
        for (UnpackedPackage pkg : packages) {
            if (!EnvironmentManager.isAlreadyInstalled(env, pkg.unpackedWheelDir())) {
                toInstall.add(pkg);
            }
        }

        if (toInstall.isEmpty()) {
            return 0;
        }

        // Fail fast if any wheel directory in the batch is missing before modifying existing installs
        for (UnpackedPackage pkg : toInstall) {
            if (!Files.isDirectory(pkg.unpackedWheelDir())) {
                throw new IOException("Unpacked wheel directory not found for " + pkg.resolved().name()
                        + ": " + pkg.unpackedWheelDir());
            }
        }

        // Remove older/conflicting installed versions of packages being upgraded or reinstalled
        Set<String> incomingNormNames = new HashSet<>(toInstall.size() * 2);
        for (UnpackedPackage pkg : toInstall) {
            incomingNormNames.add(PyPIClient.normalizePackageName(pkg.resolved().name()));
        }
        List<String> toReplace = new ArrayList<>();
        for (InstalledDist installed : EnvironmentManager.listInstalled(env)) {
            if (incomingNormNames.contains(installed.normalizedName())) {
                toReplace.add(installed.name());
            }
        }
        if (!toReplace.isEmpty()) {
            EnvironmentManager.uninstallPackages(env, toReplace, true);
        }

        int ioThreads = Math.min(4, toInstall.size());
        try (InstallTransaction tx = new InstallTransaction();
             ExecutorService pool = Executors.newFixedThreadPool(ioThreads)) {

            List<CompletableFuture<Void>> tasks = new ArrayList<>(toInstall.size());
            for (UnpackedPackage pkg : toInstall) {
                boolean requested = explicitlyRequestedNames.contains(pkg.resolved().name());
                tasks.add(CompletableFuture.runAsync(() -> {
                    try {
                        installSingleWheel(pkg.unpackedWheelDir(), env, requested, tx);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to install " + pkg.resolved().name(), e);
                    }
                }, pool));
            }

            for (CompletableFuture<Void> t : tasks) {
                t.join();
            }

            tx.commit();
            return tx.installedFileCount();
        }
    }

    public static void installSingleWheel(
            Path unpackedWheelDir,
            PythonEnvironment env,
            boolean isExplicitlyRequested,
            InstallTransaction tx) throws IOException {

        Path distInfoDirInCache = null;
        Path dataDirInCache = null;

        try (DirectoryStream<Path> topLevel = Files.newDirectoryStream(unpackedWheelDir)) {
            for (Path entry : topLevel) {
                String name = entry.getFileName().toString();
                if (".complete".equals(name) || ".lock".equals(name) || ".jolt_manifest".equals(name)) continue;

                if (name.endsWith(".dist-info") && Files.isDirectory(entry)) {
                    distInfoDirInCache = entry;
                } else if (name.endsWith(".data") && Files.isDirectory(entry)) {
                    dataDirInCache = entry;
                } else {
                    linkTreeRecursive(entry, env.sitePackagesDir().resolve(name), tx, false);
                }
            }
        }

        if (dataDirInCache != null) {
            installDataDirectory(dataDirInCache, env, tx);
        }

        if (distInfoDirInCache != null) {
            String distInfoName = distInfoDirInCache.getFileName().toString();
            Path targetDistInfo = env.sitePackagesDir().resolve(distInfoName);
            tx.ensureDirectory(targetDistInfo);

            try (DirectoryStream<Path> distEntries = Files.newDirectoryStream(distInfoDirInCache)) {
                for (Path entry : distEntries) {
                    String fileName = entry.getFileName().toString();
                    if ("RECORD".equals(fileName) || "INSTALLER".equals(fileName) || "REQUESTED".equals(fileName)) {
                        continue;
                    }
                    if (Files.isDirectory(entry)) {
                        linkTreeRecursive(entry, targetDistInfo.resolve(fileName), tx, false);
                    } else {
                        tx.linkOrCopyFile(entry, targetDistInfo.resolve(fileName));
                    }
                }
            }

            Path installerFile = targetDistInfo.resolve("INSTALLER");
            byte[] installerBytes = "jolt\n".getBytes(StandardCharsets.UTF_8);
            tx.writeNewFile(installerFile, installerBytes);

            Path requestedFile = null;
            if (isExplicitlyRequested) {
                requestedFile = targetDistInfo.resolve("REQUESTED");
                tx.writeNewFile(requestedFile, new byte[0]);
            }

            List<Path> generatedScripts = ScriptWriter.installEntryPoints(distInfoDirInCache, env, tx);

            writeUpdatedRecord(distInfoDirInCache, targetDistInfo, env.sitePackagesDir(),
                    installerFile, installerBytes, requestedFile, generatedScripts, tx);
        }
    }

    private static void installDataDirectory(
            Path dataDirInCache,
            PythonEnvironment env,
            InstallTransaction tx) throws IOException {

        try (DirectoryStream<Path> subDirs = Files.newDirectoryStream(dataDirInCache)) {
            for (Path sub : subDirs) {
                if (!Files.isDirectory(sub)) continue;
                String scheme = sub.getFileName().toString();
                switch (scheme) {
                    case "purelib", "platlib" -> copyChildrenInto(sub, env.sitePackagesDir(), tx, false);
                    case "scripts" -> copyChildrenInto(sub, env.scriptsDir(), tx, !env.isWindows());
                    case "data" -> copyChildrenInto(sub, env.rootDir(), tx, false);
                    case "headers" -> {
                        Path includeDir = env.rootDir().resolve("include");
                        copyChildrenInto(sub, includeDir, tx, false);
                    }
                    default -> copyChildrenInto(sub, env.sitePackagesDir(), tx, false);
                }
            }
        }
    }

    private static void copyChildrenInto(
            Path sourceDir,
            Path destDir,
            InstallTransaction tx,
            boolean markExecutable) throws IOException {

        try (DirectoryStream<Path> children = Files.newDirectoryStream(sourceDir)) {
            for (Path child : children) {
                Path target = destDir.resolve(child.getFileName().toString());
                linkTreeRecursive(child, target, tx, markExecutable);
            }
        }
    }

    private static void linkTreeRecursive(
            Path source,
            Path target,
            InstallTransaction tx,
            boolean markExecutable) throws IOException {

        if (Files.isDirectory(source)) {
            tx.ensureDirectory(target);
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(source)) {
                for (Path entry : entries) {
                    linkTreeRecursive(entry, target.resolve(entry.getFileName().toString()), tx, markExecutable);
                }
            }
        } else {
            tx.linkOrCopyFile(source, target);
            if (markExecutable) {
                ScriptWriter.makeExecutablePosix(target);
            }
        }
    }

    private static void writeUpdatedRecord(
            Path distInfoDirInCache,
            Path targetDistInfo,
            Path sitePackagesDir,
            Path installerFile,
            byte[] installerBytes,
            Path requestedFile,
            List<Path> generatedScripts,
            InstallTransaction tx) throws IOException {

        StringBuilder sb = new StringBuilder(4096);
        Path originalRecord = distInfoDirInCache.resolve("RECORD");
        String distInfoPrefix = targetDistInfo.getFileName().toString() + "/";

        if (Files.isRegularFile(originalRecord)) {
            List<String> lines = Files.readAllLines(originalRecord, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) continue;
                if (line.startsWith(distInfoPrefix + "RECORD,")
                        || line.startsWith(distInfoPrefix + "INSTALLER,")
                        || line.startsWith(distInfoPrefix + "REQUESTED,")) {
                    continue;
                }
                sb.append(line).append('\n');
            }
        }

        sb.append(distInfoPrefix).append("INSTALLER,")
                .append(pep376Hash(installerBytes)).append(',')
                .append(installerBytes.length).append('\n');

        if (requestedFile != null) {
            sb.append(distInfoPrefix).append("REQUESTED,").append(pep376Hash(new byte[0])).append(",0\n");
        }

        for (Path script : generatedScripts) {
            byte[] scriptBytes = Files.readAllBytes(script);
            String relPath = sitePackagesDir.relativize(script).toString().replace('\\', '/');
            sb.append(relPath).append(',')
                    .append(pep376Hash(scriptBytes)).append(',')
                    .append(scriptBytes.length).append('\n');
        }

        sb.append(distInfoPrefix).append("RECORD,,\n");
        tx.writeNewFile(targetDistInfo.resolve("RECORD"), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String pep376Hash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return "sha256=" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}