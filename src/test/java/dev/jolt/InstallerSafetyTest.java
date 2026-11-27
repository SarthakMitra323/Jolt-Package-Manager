package dev.jolt;

import dev.jolt.env.PythonEnvironment;
import dev.jolt.installer.DownloadEngine.UnpackedPackage;
import dev.jolt.installer.EnvironmentManager;
import dev.jolt.installer.EnvironmentManager.InstalledDist;
import dev.jolt.installer.StreamingUnpacker;
import dev.jolt.installer.WheelInstaller;
import dev.jolt.pep.Pep440Version;
import dev.jolt.resolver.PubGrubSolver.ResolvedPackage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Suite 2: Installer Safety Tests (offline).
 * Validates PEP 420 namespace safety, __pycache__ cleanup, SHA-256 rejection,
 * Zip-Slip path traversal protection, mid-batch rollback, and upgrade hygiene.
 */
public final class InstallerSafetyTest {

    private record BuiltWheel(String name, String version, byte[] bytes, String sha256) {}

    public static void main(String[] args) throws Exception {
        int passed = 0;
        int total = 6;

        passed += runCase("1. PEP 420 namespace package shared-dir safety", InstallerSafetyTest::testNamespacePackageSafety) ? 1 : 0;
        passed += runCase("2. __pycache__ bytecode cleanup on uninstall", InstallerSafetyTest::testPycacheCleanup) ? 1 : 0;
        passed += runCase("3. Corrupted wheel SHA-256 rejection & cache hygiene", InstallerSafetyTest::testCorruptedSha256Rejection) ? 1 : 0;
        passed += runCase("4. Zip-Slip path traversal guard in StreamingUnpacker", InstallerSafetyTest::testZipSlipGuard) ? 1 : 0;
        passed += runCase("5. Mid-batch install failure preserves existing env & rolls back", InstallerSafetyTest::testMidBatchRollback) ? 1 : 0;
        passed += runCase("6. Package upgrade (1.0.0 -> 2.0.0) leaves no duplicate dist-info", InstallerSafetyTest::testUpgradeNoDuplicateDistInfo) ? 1 : 0;

        int failed = total - passed;
        System.out.printf("%nInstallerSafetyTest summary: %d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static boolean runCase(String title, ThrowingRunnable test) {
        long t0 = System.nanoTime();
        try {
            test.run();
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            System.out.printf("[PASS] %s (%.1f ms)%n", title, ms);
            return true;
        } catch (Throwable t) {
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            System.err.printf("[FAIL] %s (%.1f ms) -> %s: %s%n",
                    title, ms, t.getClass().getSimpleName(), t.getMessage());
            return false;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // -------------------------------------------------------------------------
    // TEST 1: PEP 420 Namespace Packages (shared top-level directory)
    // -------------------------------------------------------------------------
    private static void testNamespacePackageSafety() throws Exception {
        Path sandbox = Files.createTempDirectory("jolt-safety-ns-");
        try {
            PythonEnvironment env = createMockEnv(sandbox.resolve("venv"));
            StreamingUnpacker unpacker = new StreamingUnpacker(sandbox.resolve("cache"));

            BuiltWheel alpha = buildWheel("acme-alpha", "1.0.0", Map.of("acme/alpha.py", "VAL = 'alpha'\n"));
            BuiltWheel beta  = buildWheel("acme-beta", "1.0.0", Map.of("acme/beta.py", "VAL = 'beta'\n"));

            UnpackedPackage uAlpha = unpackToPkg(unpacker, alpha);
            UnpackedPackage uBeta  = unpackToPkg(unpacker, beta);
            WheelInstaller.installAll(List.of(uAlpha, uBeta), env, Set.of("acme-alpha", "acme-beta"));

            Path nsDir = env.sitePackagesDir().resolve("acme");
            assertTrue(Files.isRegularFile(nsDir.resolve("alpha.py")), "acme/alpha.py should be installed");
            assertTrue(Files.isRegularFile(nsDir.resolve("beta.py")), "acme/beta.py should be installed");

            // Uninstall alpha: beta.py and acme/ MUST survive
            EnvironmentManager.uninstallPackages(env, List.of("acme-alpha"), true);
            assertTrue(!Files.exists(nsDir.resolve("alpha.py")), "acme/alpha.py should be removed");
            assertTrue(Files.isRegularFile(nsDir.resolve("beta.py")), "acme/beta.py MUST survive alpha uninstall");
            assertTrue(Files.isDirectory(nsDir), "acme/ namespace dir MUST survive while beta is installed");

            // Uninstall beta: now acme/ is empty and MUST be removed
            EnvironmentManager.uninstallPackages(env, List.of("acme-beta"), true);
            assertTrue(!Files.exists(nsDir.resolve("beta.py")), "acme/beta.py should be removed");
            assertTrue(!Files.exists(nsDir), "empty acme/ namespace dir should be pruned after last member uninstall");
        } finally {
            StreamingUnpacker.deleteRecursively(sandbox);
        }
    }

    // -------------------------------------------------------------------------
    // TEST 2: __pycache__ bytecode cleanup on uninstall
    // -------------------------------------------------------------------------
    private static void testPycacheCleanup() throws Exception {
        Path sandbox = Files.createTempDirectory("jolt-safety-pyc-");
        try {
            PythonEnvironment env = createMockEnv(sandbox.resolve("venv"));
            StreamingUnpacker unpacker = new StreamingUnpacker(sandbox.resolve("cache"));

            BuiltWheel demo = buildWheel("demo-pkg", "1.0.0", Map.of("demo_pkg/__init__.py", "X = 1\n"));
            WheelInstaller.installAll(List.of(unpackToPkg(unpacker, demo)), env, Set.of("demo-pkg"));

            // Simulate Python runtime generating __pycache__ (untracked by RECORD)
            Path pkgDir = env.sitePackagesDir().resolve("demo_pkg");
            Path pycache = pkgDir.resolve("__pycache__");
            Files.createDirectories(pycache);
            Files.writeString(pycache.resolve("__init__.cpython-312.pyc"), "FAKE_PYC", StandardCharsets.UTF_8);

            EnvironmentManager.uninstallPackages(env, List.of("demo-pkg"), true);
            assertTrue(!Files.exists(pycache), "__pycache__ should be deleted on uninstall");
            assertTrue(!Files.exists(pkgDir), "demo_pkg/ should be completely removed after uninstall");
        } finally {
            StreamingUnpacker.deleteRecursively(sandbox);
        }
    }

    // -------------------------------------------------------------------------
    // TEST 3: Corrupted wheel SHA-256 rejection & cache hygiene
    // -------------------------------------------------------------------------
    private static void testCorruptedSha256Rejection() throws Exception {
        Path sandbox = Files.createTempDirectory("jolt-safety-sha-");
        try {
            StreamingUnpacker unpacker = new StreamingUnpacker(sandbox.resolve("cache"));
            BuiltWheel wheel = buildWheel("corrupt-pkg", "1.0.0", Map.of("corrupt_pkg/__init__.py", "OK = True\n"));
            String wrongSha = "0000000000000000000000000000000000000000000000000000000000000000";

            boolean threw = false;
            try {
                unpacker.unpackAndVerify(new ByteArrayInputStream(wheel.bytes()), wrongSha);
            } catch (IOException | SecurityException expected) {
                threw = true;
            }
            assertTrue(threw, "unpackAndVerify MUST throw on SHA-256 mismatch");
            assertTrue(unpacker.getCachedWheelDir(wrongSha) == null, "Mismatched wheel must NOT remain in cache");
        } finally {
            StreamingUnpacker.deleteRecursively(sandbox);
        }
    }

    // -------------------------------------------------------------------------
    // TEST 4: Zip-Slip path traversal guard
    // -------------------------------------------------------------------------
    private static void testZipSlipGuard() throws Exception {
        Path sandbox = Files.createTempDirectory("jolt-safety-slip-");
        try {
            Path cacheRoot = sandbox.resolve("cache");
            StreamingUnpacker unpacker = new StreamingUnpacker(cacheRoot);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                zos.putNextEntry(new ZipEntry("../../evil_escape.txt"));
                zos.write("pwned".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            byte[] evilBytes = baos.toByteArray();
            String realSha = sha256Hex(evilBytes);

            boolean threw = false;
            try {
                unpacker.unpackAndVerify(new ByteArrayInputStream(evilBytes), realSha);
            } catch (IOException | SecurityException expected) {
                threw = true;
            }

            assertTrue(threw, "StreamingUnpacker MUST reject '../' Zip-Slip paths");
            assertTrue(!Files.exists(sandbox.resolve("evil_escape.txt")), "evil_escape.txt must NOT be written outside cache");
        } finally {
            StreamingUnpacker.deleteRecursively(sandbox);
        }
    }

    // -------------------------------------------------------------------------
    // TEST 5: Mid-batch install failure preserves existing env & rolls back
    // -------------------------------------------------------------------------
    private static void testMidBatchRollback() throws Exception {
        Path sandbox = Files.createTempDirectory("jolt-safety-rb-");
        try {
            PythonEnvironment env = createMockEnv(sandbox.resolve("venv"));
            StreamingUnpacker unpacker = new StreamingUnpacker(sandbox.resolve("cache"));

            // Pre-existing healthy package
            BuiltWheel existing = buildWheel("existing-pkg", "1.0.0", Map.of("existing_pkg/__init__.py", "SAFE = 1\n"));
            WheelInstaller.installAll(List.of(unpackToPkg(unpacker, existing)), env, Set.of("existing-pkg"));

            // New batch: 1st wheel is valid, 2nd wheel points to missing dir -> forces mid-batch failure
            BuiltWheel newPkg = buildWheel("new-pkg", "1.0.0", Map.of("new_pkg/__init__.py", "NEW = 1\n"));
            UnpackedPackage goodPkg = unpackToPkg(unpacker, newPkg);
            UnpackedPackage brokenPkg = new UnpackedPackage(
                    new ResolvedPackage("broken-pkg", Pep440Version.parse("1.0.0"), null),
                    sandbox.resolve("does-not-exist-dir"),
                    false
            );

            boolean threw = false;
            try {
                WheelInstaller.installAll(List.of(goodPkg, brokenPkg), env, Set.of("new-pkg", "broken-pkg"));
            } catch (Exception expected) {
                threw = true;
            }

            assertTrue(threw, "installAll MUST fail when a wheel in the batch is broken");
            assertTrue(Files.isRegularFile(env.sitePackagesDir().resolve("existing_pkg/__init__.py")),
                    "Pre-existing package MUST remain intact after failed batch");
            assertTrue(!Files.exists(env.sitePackagesDir().resolve("new_pkg/__init__.py")),
                    "Partially linked new_pkg should be rolled back on batch failure");
        } finally {
            StreamingUnpacker.deleteRecursively(sandbox);
        }
    }

    // -------------------------------------------------------------------------
    // TEST 6: Package upgrade hygiene (1.0.0 -> 2.0.0)
    // -------------------------------------------------------------------------
    private static void testUpgradeNoDuplicateDistInfo() throws Exception {
        Path sandbox = Files.createTempDirectory("jolt-safety-upg-");
        try {
            PythonEnvironment env = createMockEnv(sandbox.resolve("venv"));
            StreamingUnpacker unpacker = new StreamingUnpacker(sandbox.resolve("cache"));

            BuiltWheel v1 = buildWheel("upgradable", "1.0.0", Map.of(
                    "upgradable/__init__.py", "VERSION = 1\n",
                    "upgradable/old_only.py", "LEGACY = True\n"
            ));
            WheelInstaller.installAll(List.of(unpackToPkg(unpacker, v1)), env, Set.of("upgradable"));

            BuiltWheel v2 = buildWheel("upgradable", "2.0.0", Map.of(
                    "upgradable/__init__.py", "VERSION = 2\n"
            ));
            WheelInstaller.installAll(List.of(unpackToPkg(unpacker, v2)), env, Set.of("upgradable"));

            String initContent = Files.readString(env.sitePackagesDir().resolve("upgradable/__init__.py"), StandardCharsets.UTF_8);
            assertTrue(initContent.contains("VERSION = 2"), "upgradable/__init__.py should contain v2 code");

            List<InstalledDist> installed = EnvironmentManager.listInstalled(env);
            assertTrue(installed.size() == 1 && "2.0.0".equals(installed.get(0).version()),
                    "Expected exactly 1 installed dist (2.0.0), found: " + installed);
        } finally {
            StreamingUnpacker.deleteRecursively(sandbox);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private static PythonEnvironment createMockEnv(Path venvRoot) throws IOException {
        Path site = venvRoot.resolve("Lib").resolve("site-packages");
        Path scripts = venvRoot.resolve("Scripts");
        Files.createDirectories(site);
        Files.createDirectories(scripts);
        return new PythonEnvironment(
                venvRoot, site, scripts, scripts.resolve("python.exe"),
                3, 12, 4, "cp312", "win_amd64", true
        );
    }

    private static UnpackedPackage unpackToPkg(StreamingUnpacker unpacker, BuiltWheel wheel) throws IOException {
        Path unpackedDir = unpacker.unpackAndVerify(new ByteArrayInputStream(wheel.bytes()), wheel.sha256());
        ResolvedPackage rp = new ResolvedPackage(wheel.name(), Pep440Version.parse(wheel.version()), null);
        return new UnpackedPackage(rp, unpackedDir, false);
    }

    private static BuiltWheel buildWheel(String name, String version, Map<String, String> files) throws Exception {
        String normUnderscore = name.replace('-', '_');
        String distInfo = normUnderscore + "-" + version + ".dist-info/";

        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            entries.put(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8));
        }

        String metadata = "Metadata-Version: 2.1\nName: " + name + "\nVersion: " + version + "\n";
        String wheelMeta = "Wheel-Version: 1.0\nGenerator: jolt-test\nRoot-Is-Purelib: true\nTag: py3-none-any\n";
        entries.put(distInfo + "METADATA", metadata.getBytes(StandardCharsets.UTF_8));
        entries.put(distInfo + "WHEEL", wheelMeta.getBytes(StandardCharsets.UTF_8));

        StringBuilder record = new StringBuilder();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            record.append(e.getKey())
                    .append(',')
                    .append(WheelInstaller.pep376Hash(e.getValue()))
                    .append(',')
                    .append(e.getValue().length)
                    .append('\n');
        }
        record.append(distInfo).append("RECORD,,\n");
        entries.put(distInfo + "RECORD", record.toString().getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        byte[] bytes = baos.toByteArray();
        return new BuiltWheel(name, version, bytes, sha256Hex(bytes));
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}