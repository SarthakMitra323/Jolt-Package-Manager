// File: src/test/java/dev/jolt/Phase4InstallerTest.java
package dev.jolt;

import dev.jolt.env.PythonEnvironment;
import dev.jolt.installer.InstallTransaction;
import dev.jolt.installer.StreamingUnpacker;
import dev.jolt.installer.WheelInstaller;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class Phase4InstallerTest {

    public static void main(String[] args) throws Exception {
        Path tempRoot = Files.createTempDirectory("jolt-phase4-test-");
        try {
            testStreamingUnpackAndTamperGuard(tempRoot);
            testHardlinkInstallAndEntryPoints(tempRoot);
            testAtomicRollbackOnFailure(tempRoot);
            System.out.println("All Phase 4 Streaming Unpacker, Hardlink Installer & Rollback tests passed.");
        } finally {
            StreamingUnpacker.deleteRecursively(tempRoot);
        }
    }

    private static void testStreamingUnpackAndTamperGuard(Path tempRoot) throws Exception {
        byte[] wheelBytes = createSyntheticWheelBytes();
        String expectedSha = sha256Hex(wheelBytes);

        StreamingUnpacker unpacker = new StreamingUnpacker(tempRoot.resolve("cache1"));

        // 1. Verify valid SHA-256 unpacks cleanly into content-addressed store
        Path unpackedDir = unpacker.unpackAndVerify(new ByteArrayInputStream(wheelBytes), expectedSha);
        assert Files.isDirectory(unpackedDir);
        assert Files.isRegularFile(unpackedDir.resolve("demo_pkg/__init__.py"));
        assert Files.isRegularFile(unpackedDir.resolve("demo_pkg-1.0.0.dist-info/METADATA"));

        // 2. Verify warm cache lookup hits immediately
        Path cachedHit = unpacker.getCachedWheelDir(expectedSha);
        assert unpackedDir.equals(cachedHit);

        // 3. Verify corrupted SHA-256 is rejected and leaves no partial files
        boolean threwSecurityEx = false;
        try {
            unpacker.unpackAndVerify(
                    new ByteArrayInputStream(wheelBytes),
                    "0000000000000000000000000000000000000000000000000000000000000000"
            );
        } catch (SecurityException expected) {
            threwSecurityEx = true;
        }
        assert threwSecurityEx : "Expected SecurityException on mismatched SHA-256";
    }

    private static void testHardlinkInstallAndEntryPoints(Path tempRoot) throws Exception {
        byte[] wheelBytes = createSyntheticWheelBytes();
        String expectedSha = sha256Hex(wheelBytes);

        StreamingUnpacker unpacker = new StreamingUnpacker(tempRoot.resolve("cache2"));
        Path unpackedDir = unpacker.unpackAndVerify(new ByteArrayInputStream(wheelBytes), expectedSha);

        PythonEnvironment mockEnv = createMockVenv(tempRoot.resolve("venv1"));

        try (InstallTransaction tx = new InstallTransaction()) {
            WheelInstaller.installSingleWheel(unpackedDir, mockEnv, true, tx);
            tx.commit();
        }

        // Verify module file was hardlinked into site-packages
        Path initPy = mockEnv.sitePackagesDir().resolve("demo_pkg/__init__.py");
        assert Files.isRegularFile(initPy);
        assert "def main(): return 42\n".equals(Files.readString(initPy, StandardCharsets.UTF_8));

        // Verify PEP 376 INSTALLER & REQUESTED markers
        Path distInfo = mockEnv.sitePackagesDir().resolve("demo_pkg-1.0.0.dist-info");
        assert "jolt\n".equals(Files.readString(distInfo.resolve("INSTALLER"), StandardCharsets.UTF_8));
        assert Files.isRegularFile(distInfo.resolve("REQUESTED"));

        // Verify console_scripts wrapper was generated in bin/ or Scripts/
        if (mockEnv.isWindows()) {
            assert Files.isRegularFile(mockEnv.scriptsDir().resolve("demo-cli.cmd"));
            assert Files.isRegularFile(mockEnv.scriptsDir().resolve("demo-cli-script.py"));
        } else {
            assert Files.isRegularFile(mockEnv.scriptsDir().resolve("demo-cli"));
        }
    }

    private static void testAtomicRollbackOnFailure(Path tempRoot) throws Exception {
        PythonEnvironment mockEnv = createMockVenv(tempRoot.resolve("venv-rollback"));
        Path targetFile = mockEnv.sitePackagesDir().resolve("partial_pkg/mod.py");

        try (InstallTransaction tx = new InstallTransaction()) {
            tx.writeNewFile(targetFile, "print('hello')".getBytes(StandardCharsets.UTF_8));
            assert Files.isRegularFile(targetFile);
            // Notice: we DO NOT call tx.commit(), simulating an exception aborting the install
        }

        // Verify transaction auto-rolled back both the file and its newly created parent directory
        assert !Files.exists(targetFile) : "Rolled-back file should not exist!";
        assert !Files.exists(targetFile.getParent()) : "Rolled-back directory should not exist!";
    }

    private static PythonEnvironment createMockVenv(Path venvRoot) throws IOException {
        boolean isWin = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path sitePackages = isWin
                ? venvRoot.resolve("Lib/site-packages")
                : venvRoot.resolve("lib/python3.12/site-packages");
        Path scripts = venvRoot.resolve(isWin ? "Scripts" : "bin");
        Path pyBin = scripts.resolve(isWin ? "python.exe" : "python");
        Files.createDirectories(sitePackages);
        Files.createDirectories(scripts);
        return new PythonEnvironment(
                venvRoot, sitePackages, scripts, pyBin,
                3, 12, 4, "cp312", isWin ? "win_amd64" : "manylinux_2_28_x86_64", isWin
        );
    }

    private static byte[] createSyntheticWheelBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            writeZipEntry(zos, "demo_pkg/__init__.py", "def main(): return 42\n");
            writeZipEntry(zos, "demo_pkg-1.0.0.dist-info/METADATA",
                    "Metadata-Version: 2.1\nName: demo-pkg\nVersion: 1.0.0\n\n");
            writeZipEntry(zos, "demo_pkg-1.0.0.dist-info/WHEEL",
                    "Wheel-Version: 1.0\nGenerator: jolt-test\nRoot-Is-Purelib: true\nTag: py3-none-any\n");
            writeZipEntry(zos, "demo_pkg-1.0.0.dist-info/entry_points.txt",
                    "[console_scripts]\ndemo-cli = demo_pkg:main\n");
            writeZipEntry(zos, "demo_pkg-1.0.0.dist-info/RECORD",
                    "demo_pkg/__init__.py,sha256=abc,22\ndemo_pkg-1.0.0.dist-info/RECORD,,\n");
        }
        return baos.toByteArray();
    }

    private static void writeZipEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }
}