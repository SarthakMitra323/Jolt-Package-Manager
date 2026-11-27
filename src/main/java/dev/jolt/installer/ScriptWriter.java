package dev.jolt.installer;

import dev.jolt.env.PythonEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Parses .dist-info/entry_points.txt and generates executable console_scripts and gui_scripts
 * launchers in the target virtual environment's bin/ or Scripts/ directory.
 * On Windows, emits real distlib-compatible PE .exe launchers (matching pip and uv).
 */
public final class ScriptWriter {

    public record EntryPoint(String scriptName, String moduleName, String callableName, boolean isGui) {
        public EntryPoint(String scriptName, String moduleName, String callableName) {
            this(scriptName, moduleName, callableName, false);
        }
    }

    private static volatile byte[] cachedConsoleStub;
    private static volatile byte[] cachedGuiStub;

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
                byte[] stub = loadWindowsStub(ep.isGui(), env);
                if (stub != null) {
                    Path exePath = env.scriptsDir().resolve(ep.scriptName() + ".exe");
                    byte[] exeBytes = buildWindowsExeLauncher(stub, env.pythonBinary(), ep.moduleName(), ep.callableName());
                    tx.writeNewFile(exePath, exeBytes);
                    createdFiles.add(exePath);
                } else {
                    // Fallback if PE stubs are unavailable
                    Path pyScript = env.scriptsDir().resolve(ep.scriptName() + "-script.py");
                    Path cmdShim = env.scriptsDir().resolve(ep.scriptName() + ".cmd");

                    String pyContent = buildPosixLauncher(env.pythonBinary().toAbsolutePath().toString(), ep.moduleName(), ep.callableName());
                    String cmdContent = "@echo off\r\n\"" + env.pythonBinary().toAbsolutePath() + "\" \""
                            + pyScript.toAbsolutePath() + "\" %*\r\n";

                    tx.writeNewFile(pyScript, pyContent.getBytes(StandardCharsets.UTF_8));
                    tx.writeNewFile(cmdShim, cmdContent.getBytes(StandardCharsets.UTF_8));
                    createdFiles.add(pyScript);
                    createdFiles.add(cmdShim);
                }
            } else {
                Path posixScript = env.scriptsDir().resolve(ep.scriptName());
                String content = buildPosixLauncher(env.pythonBinary().toAbsolutePath().toString(), ep.moduleName(), ep.callableName());
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
        boolean currentIsGui = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;

            if (line.startsWith("[") && line.endsWith("]")) {
                String section = line.substring(1, line.length() - 1).trim();
                if ("console_scripts".equals(section)) {
                    inConsoleSection = true;
                    currentIsGui = false;
                } else if ("gui_scripts".equals(section)) {
                    inConsoleSection = true;
                    currentIsGui = true;
                } else {
                    inConsoleSection = false;
                }
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
            scripts.add(new EntryPoint(name, module, callable, currentIsGui));
        }
        return scripts;
    }

    private static byte[] loadWindowsStub(boolean isGui, PythonEnvironment env) {
        byte[] cached = isGui ? cachedGuiStub : cachedConsoleStub;
        if (cached != null) return cached;

        String stubName = isGui ? "w64.exe" : "t64.exe";
        byte[] loaded = null;

        // 1. Primary: embedded classpath resource inside jolt.jar / fast.exe
        try (InputStream is = ScriptWriter.class.getResourceAsStream("/dev/jolt/launchers/" + stubName)) {
            if (is != null) {
                loaded = is.readAllBytes();
            }
        } catch (IOException ignored) {
        }

        // 2. Secondary: locate distlib stub from base Python installation via pyvenv.cfg
        if (loaded == null) {
            loaded = findStubInBasePython(env, stubName);
        }

        if (loaded != null) {
            if (isGui) {
                cachedGuiStub = loaded;
            } else {
                cachedConsoleStub = loaded;
            }
        }
        return loaded;
    }

    private static byte[] findStubInBasePython(PythonEnvironment env, String stubName) {
        try {
            Path cfg = env.rootDir().resolve("pyvenv.cfg");
            if (Files.isRegularFile(cfg)) {
                for (String line : Files.readAllLines(cfg, StandardCharsets.UTF_8)) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String key = line.substring(0, eq).trim();
                    if ("home".equals(key)) {
                        Path baseHome = Path.of(line.substring(eq + 1).trim());
                        Path candidate = baseHome.resolve("Lib").resolve("site-packages")
                                .resolve("pip").resolve("_vendor").resolve("distlib").resolve(stubName);
                        if (Files.isRegularFile(candidate)) {
                            return Files.readAllBytes(candidate);
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static byte[] buildWindowsExeLauncher(
            byte[] stubBytes,
            Path pythonBin,
            String module,
            String callable) throws IOException {

        String pyPath = pythonBin.toAbsolutePath().toString();
        String shebang = (pyPath.indexOf(' ') != -1)
                ? "#!\"" + pyPath + "\"\r\n"
                : "#!" + pyPath + "\r\n";

        String mainPy = buildScriptBody(module, callable);

        ByteArrayOutputStream zipBuf = new ByteArrayOutputStream(512);
        try (ZipOutputStream zos = new ZipOutputStream(zipBuf)) {
            zos.putNextEntry(new ZipEntry("__main__.py"));
            zos.write(mainPy.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        byte[] shebangBytes = shebang.getBytes(StandardCharsets.UTF_8);
        byte[] zipBytes = zipBuf.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream(stubBytes.length + shebangBytes.length + zipBytes.length);
        out.write(stubBytes);
        out.write(shebangBytes);
        out.write(zipBytes);
        return out.toByteArray();
    }

    private static String buildPosixLauncher(String pythonBinPath, String module, String callable) {
        return "#!" + pythonBinPath + "\n" + buildScriptBody(module, callable);
    }

    private static String buildScriptBody(String module, String callable) {
        int dotIdx = callable.indexOf('.');
        String importAttr = (dotIdx == -1) ? callable : callable.substring(0, dotIdx);

        return "# -*- coding: utf-8 -*-\n"
                + "import re\n"
                + "import sys\n"
                + "from " + module + " import " + importAttr + "\n"
                + "if __name__ == '__main__':\n"
                + "    sys.argv[0] = re.sub(r'(-script\\.pyw|\\.exe)?$', '', sys.argv[0])\n"
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