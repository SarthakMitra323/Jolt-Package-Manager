package dev.jolt.installer;

import dev.jolt.env.PythonEnvironment;
import dev.jolt.net.PyPIClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class EnvironmentManager {

    public record InstalledDist(
            String name,
            String normalizedName,
            String version,
            Path distInfoDir
    ) {}

    private EnvironmentManager() {}

    public static boolean isAlreadyInstalled(PythonEnvironment env, Path unpackedWheelDir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(unpackedWheelDir, "*.dist-info")) {
            for (Path distInfo : stream) {
                Path targetDistInfo = env.sitePackagesDir().resolve(distInfo.getFileName().toString());
                if (Files.isRegularFile(targetDistInfo.resolve("RECORD"))) {
                    return true;
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    public static List<InstalledDist> listInstalled(PythonEnvironment env) throws IOException {
        if (!Files.isDirectory(env.sitePackagesDir())) {
            return List.of();
        }

        List<InstalledDist> list = new ArrayList<>(32);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(env.sitePackagesDir(), "*.dist-info")) {
            for (Path distInfo : stream) {
                Path metaFile = distInfo.resolve("METADATA");
                if (!Files.isRegularFile(metaFile)) continue;

                String pkgName = null;
                String version = null;
                for (String line : Files.readAllLines(metaFile, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) break;
                    if (pkgName == null && line.startsWith("Name:")) {
                        pkgName = line.substring(5).trim();
                    } else if (version == null && line.startsWith("Version:")) {
                        version = line.substring(8).trim();
                    }
                    if (pkgName != null && version != null) break;
                }

                if (pkgName != null && version != null) {
                    list.add(new InstalledDist(
                            pkgName,
                            PyPIClient.normalizePackageName(pkgName),
                            version,
                            distInfo
                    ));
                }
            }
        }
        list.sort(Comparator.comparing(InstalledDist::normalizedName));
        return list;
    }

    public static int uninstallPackages(PythonEnvironment env, List<String> rawNames, boolean quiet) throws IOException {
        List<InstalledDist> installed = listInstalled(env);
        int removedCount = 0;

        for (String target : rawNames) {
            String normTarget = PyPIClient.normalizePackageName(target);
            InstalledDist match = null;
            for (InstalledDist dist : installed) {
                if (dist.normalizedName().equals(normTarget)) {
                    match = dist;
                    break;
                }
            }

            if (match == null) {
                if (!quiet) {
                    System.err.println("warning: Skipping " + target + " as it is not installed.");
                }
                continue;
            }

            Path recordFile = match.distInfoDir().resolve("RECORD");
            int filesDeleted = 0;
            if (Files.isRegularFile(recordFile)) {
                List<String> lines = Files.readAllLines(recordFile, StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (line.isBlank()) continue;
                    int comma = line.indexOf(',');
                    String relPath = (comma != -1) ? line.substring(0, comma).trim() : line.trim();
                    Path fileToDelete = env.sitePackagesDir().resolve(relPath).normalize();
                    if (Files.deleteIfExists(fileToDelete)) {
                        filesDeleted++;
                    }
                }
            }

            StreamingUnpacker.deleteRecursively(match.distInfoDir());
            removedCount++;
            if (!quiet) {
                System.out.printf("Uninstalled %s (%s) (%d files removed)%n",
                        match.name(), match.version(), filesDeleted);
            }
        }
        return removedCount;
    }
}