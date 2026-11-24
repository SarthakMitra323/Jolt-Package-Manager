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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        Path sitePackages = env.sitePackagesDir().toAbsolutePath().normalize();
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
            Set<Path> touchedDirs = new HashSet<>();

            if (Files.isRegularFile(recordFile)) {
                List<String> lines = Files.readAllLines(recordFile, StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (line.isBlank()) continue;
                    String relPath = extractRecordPath(line);
                    if (relPath.isEmpty()) continue;

                    Path fileToDelete = sitePackages.resolve(relPath).normalize();
                    if (Files.deleteIfExists(fileToDelete)) {
                        filesDeleted++;
                    }

                    // Track parent directories inside site-packages so empty folders (PEP 420 zombies) are pruned
                    Path parent = fileToDelete.getParent();
                    while (parent != null && parent.startsWith(sitePackages) && !parent.equals(sitePackages)) {
                        touchedDirs.add(parent);
                        parent = parent.getParent();
                    }
                }
            }

            StreamingUnpacker.deleteRecursively(match.distInfoDir());

            // Prune __pycache__ and now-empty package directories deepest-first
            List<Path> sortedDirs = new ArrayList<>(touchedDirs);
            sortedDirs.sort(Comparator.comparingInt(Path::getNameCount).reversed());
            for (Path dir : sortedDirs) {
                if (!Files.isDirectory(dir)) continue;
                Path pycache = dir.resolve("__pycache__");
                if (Files.isDirectory(pycache)) {
                    StreamingUnpacker.deleteRecursively(pycache);
                }
                if (isDirEmpty(dir)) {
                    Files.deleteIfExists(dir);
                }
            }

            removedCount++;
            if (!quiet) {
                System.out.printf("Uninstalled %s (%s) (%d files removed)%n",
                        match.name(), match.version(), filesDeleted);
            }
        }
        return removedCount;
    }

    private static String extractRecordPath(String recordLine) {
        String trimmed = recordLine.trim();
        if (trimmed.startsWith("\"")) {
            int closing = trimmed.indexOf('"', 1);
            if (closing != -1) {
                return trimmed.substring(1, closing).trim();
            }
        }
        int comma = trimmed.indexOf(',');
        return (comma != -1) ? trimmed.substring(0, comma).trim() : trimmed;
    }

    private static boolean isDirEmpty(Path dir) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            return !ds.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }
}