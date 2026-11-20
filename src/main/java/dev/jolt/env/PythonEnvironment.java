// File: src/main/java/dev/jolt/env/PythonEnvironment.java
package dev.jolt.env;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public record PythonEnvironment(
        Path rootDir,
        Path sitePackagesDir,
        Path scriptsDir,
        Path pythonBinary,
        int major,
        int minor,
        int patch,
        String abiTag,
        String primaryPlatformTag,
        boolean isWindows
) {
    public static PythonEnvironment detect(Path explicitVenv) throws IOException {
        Path root = resolveVenvRoot(explicitVenv);
        boolean isWin = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

        Path pyvenvCfg = root.resolve("pyvenv.cfg");
        int major = 3, minor = 12, patch = 0;
        boolean versionFound = false;

        // Fast path 1: Read pyvenv.cfg (written by venv, virtualenv, and uv)
        if (Files.isRegularFile(pyvenvCfg)) {
            List<String> lines = Files.readAllLines(pyvenvCfg, StandardCharsets.UTF_8);
            for (String line : lines) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim();
                    if ("version".equals(key) || "version_info".equals(key)) {
                        String val = line.substring(eq + 1).trim();
                        int[] parsed = parseVersionNumbers(val);
                        if (parsed != null) {
                            major = parsed[0];
                            minor = parsed[1];
                            patch = parsed[2];
                            versionFound = true;
                            break;
                        }
                    }
                }
            }
        }

        // Fast path 2: Scan lib/pythonX.Y directory name on POSIX if pyvenv.cfg lacked version
        Path libDir = root.resolve(isWin ? "Lib" : "lib");
        if (!versionFound && !isWin && Files.isDirectory(libDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(libDir, "python*")) {
                for (Path entry : stream) {
                    String name = entry.getFileName().toString(); // e.g., "python3.11"
                    if (name.startsWith("python") && name.length() > 6) {
                        int[] parsed = parseVersionNumbers(name.substring(6));
                        if (parsed != null) {
                            major = parsed[0];
                            minor = parsed[1];
                            break;
                        }
                    }
                }
            }
        }

        Path sitePackages = isWin
                ? libDir.resolve("site-packages")
                : libDir.resolve("python" + major + "." + minor).resolve("site-packages");
        Path scripts = root.resolve(isWin ? "Scripts" : "bin");
        Path pyBin = scripts.resolve(isWin ? "python.exe" : "python");

        String abi = "cp" + major + minor;
        String platformTag = detectPlatformTag(isWin);

        return new PythonEnvironment(
                root, sitePackages, scripts, pyBin,
                major, minor, patch, abi, platformTag, isWin
        );
    }

    private static Path resolveVenvRoot(Path explicit) {
        if (explicit != null) return explicit.toAbsolutePath().normalize();

        String envVar = System.getenv("VIRTUAL_ENV");
        if (envVar != null && !envVar.isBlank()) {
            return Path.of(envVar).toAbsolutePath().normalize();
        }

        Path cwd = Path.of("").toAbsolutePath();
        Path dotVenv = cwd.resolve(".venv");
        if (Files.isDirectory(dotVenv)) return dotVenv;

        Path venv = cwd.resolve("venv");
        if (Files.isDirectory(venv)) return venv;

        throw new IllegalStateException(
                "No virtual environment detected. Activate a venv, create './.venv', or pass '--venv <path>'."
        );
    }

    private static int[] parseVersionNumbers(String raw) {
        String[] parts = raw.split("\\.");
        if (parts.length < 2) return null;
        try {
            int maj = Integer.parseInt(parts[0]);
            int min = Integer.parseInt(parts[1]);
            int pat = parts.length >= 3 ? Integer.parseInt(parts[2].split("[^0-9]")[0]) : 0;
            return new int[]{maj, min, pat};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String detectPlatformTag(boolean isWin) {
        String archProp = System.getProperty("os.arch", "amd64").toLowerCase(Locale.ROOT);
        String arch = switch (archProp) {
            case "x86_64", "amd64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> archProp;
        };

        if (isWin) {
            return "x86_64".equals(arch) ? "win_amd64" : "win_" + arch;
        }

        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac") || osName.contains("darwin")) {
            String macArch = "aarch64".equals(arch) ? "arm64" : "x86_64";
            return "macosx_11_0_" + macArch;
        }

        // Linux: Default to broad manylinux2014 + modern manylinux_2_28
        return "manylinux_2_28_" + arch;
    }
}