// File: src/main/java/dev/jolt/installer/ScriptWriter.java
package dev.jolt.installer;

import dev.jolt.env.PythonEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Parses .dist-info/entry_points.txt and generates executable console_scripts wrappers
 * in the target virtual environment's bin/ or Scripts/ directory.
 */
public final class ScriptWriter {

    public record EntryPoint(String scriptName, String moduleName, String callableName) {}

    private ScriptWriter() {}

    public static List<Path> installEntryPoints(
            Path distInfoDir,
            PythonEnvironment env,
            InstallTransaction tx) throws IOException {

        Path entryPointsFile = distInfoDir.resolve("entry_points.txt");
        if (!Files.isRegularFile(entryPointsFile)) {
            return List.of();
        }

        List<EntryPoint> consoleScripts = parseConsoleScripts(Files.readAllLines(entryPointsFile, StandardCharsets.UTF_8));
        if (consoleScripts.isEmpty()) {
            return List.of();
        }

        Files.createDirectories(env.scriptsDir());
        List<Path> createdFiles = new ArrayList<>(consoleScripts.size() * 2);

        for (EntryPoint ep : consoleScripts) {
            if (env.isWindows()) {
                // On Windows, create both a Python script (<name>-script.py) and a fast .cmd shim (<name>.cmd)
                Path pyScript = env.scriptsDir().resolve(ep.scriptName() + "-script.py");
                Path cmdShim = env.scriptsDir().resolve(ep.scriptName() + ".cmd");

                String pyContent = buildPythonLauncher(env.pythonBinary().toString(), ep.moduleName(), ep.callableName());
                String cmdContent = "@echo off\r\n\"" + env.pythonBinary().toAbsolutePath() + "\" \""
                        + pyScript.toAbsolutePath() + "\" %*\r\n";

                tx.writeNewFile(pyScript, pyContent.getBytes(StandardCharsets.UTF_8));
                tx.writeNewFile(cmdShim, cmdContent.getBytes(StandardCharsets.UTF_8));
                createdFiles.add(pyScript);
                createdFiles.add(cmdShim);
            } else {
                Path posixScript = env.scriptsDir().resolve(ep.scriptName());
                String content = buildPythonLauncher(env.pythonBinary().toAbsolutePath().toString(), ep.moduleName(), ep.callableName());
                tx.writeNewFile(posixScript, content.getBytes(StandardCharsets.UTF_8));
                makeExecutablePosix(posixScript);
                createdFiles.add(posixScript);
            }
        }
        return createdFiles;
    }

    public static List<EntryPoint> parseConsoleScripts(List<String> lines) {
        List<EntryPoint> scripts = new ArrayList<>(4);
        boolean inConsoleSection = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;

            if (line.startsWith("[") && line.endsWith("]")) {
                String section = line.substring(1, line.length() - 1).trim();
                inConsoleSection = "console_scripts".equals(section) || "gui_scripts".equals(section);
                continue;
            }

            if (!inConsoleSection) continue;

            int eqIdx = line.indexOf('=');
            if (eqIdx <= 0) continue;

            String name = line.substring(0, eqIdx).trim();
            String target = line.substring(eqIdx + 1).trim();

            // Strip optional extras e.g. "uvicorn.main:main [standard]"
            int bracketIdx = target.indexOf('[');
            if (bracketIdx != -1) {
                target = target.substring(0, bracketIdx).trim();
            }

            int colonIdx = target.indexOf(':');
            if (colonIdx <= 0) continue;

            String module = target.substring(0, colonIdx).trim();
            String callable = target.substring(colonIdx + 1).trim();
            scripts.add(new EntryPoint(name, module, callable));
        }
        return scripts;
    }

    private static String buildPythonLauncher(String pythonBinPath, String module, String callable) {
        int dotIdx = callable.indexOf('.');
        String importAttr = (dotIdx == -1) ? callable : callable.substring(0, dotIdx);

        return "#!" + pythonBinPath + "\n"
                + "# -*- coding: utf-8 -*-\n"
                + "import sys\n"
                + "from " + module + " import " + importAttr + "\n"
                + "if __name__ == '__main__':\n"
                + "    sys.exit(" + callable + "())\n";
    }

    public static void makeExecutablePosix(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
            );
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem (e.g. Windows NTFS)
        }
    }
}