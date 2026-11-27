package dev.jolt;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.jolt.env.PythonEnvironment;
import dev.jolt.net.PyPIClient;
import dev.jolt.resolver.PubGrubSolver;
import dev.jolt.resolver.PubGrubSolver.ResolvedPackage;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Suite 1: Offline PubGrub Resolver Stress Tests.
 * Uses an in-process JDK HttpServer serving PEP 691 JSON + PEP 658 .metadata
 * to stress-test backtracking (>64 packages), cycles, deep diamonds, and conflicts.
 */
public final class ResolverStressTest {

    private record MockRelease(String version, String requiresPython, List<String> requiresDist) {}

    private static final Map<String, List<MockRelease>> REGISTRY = new ConcurrentHashMap<>();
    private static String baseUrl;

    private static final PythonEnvironment TEST_ENV = new PythonEnvironment(
            Path.of("/tmp/.venv"),
            Path.of("/tmp/.venv/lib/python3.12/site-packages"),
            Path.of("/tmp/.venv/bin"),
            Path.of("/tmp/.venv/bin/python"),
            3, 12, 4, "cp312", "manylinux_2_28_x86_64", false
    );

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ResolverStressTest::handleRequest);
        server.start();

        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        System.setProperty("jolt.index.url", baseUrl + "/simple/");

        int passed = 0;
        int failed = 0;

        try {
            passed += runCase("1. Backtracking across >64-package boundary (80 packages)", ResolverStressTest::testBacktrackingPast64Packages) ? 1 : 0;
            passed += runCase("2. Valid 3-node dependency cycle (A -> B -> C -> A)", ResolverStressTest::testValidThreeNodeCycle) ? 1 : 0;
            passed += runCase("3. Contradictory mutual cycle backtracking (X <-> Y)", ResolverStressTest::testConflictingCycleBacktrack) ? 1 : 0;
            passed += runCase("4. Deep diamond multi-version backtracking", ResolverStressTest::testDeepDiamondBacktracking) ? 1 : 0;
            passed += runCase("5. Unsatisfiable graph fails cleanly", ResolverStressTest::testUnsatisfiableGraph) ? 1 : 0;
            passed += runCase("6. Requires-Python filtering (Python 3.12)", ResolverStressTest::testRequiresPythonFiltering) ? 1 : 0;
            failed = 6 - passed;
        } finally {
            server.stop(0);
        }

        System.out.printf("%nResolverStressTest summary: %d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static boolean runCase(String title, ThrowingRunnable test) {
        REGISTRY.clear();
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
            t.printStackTrace(System.err);
            return false;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // -------------------------------------------------------------------------
    // TEST 1: >64-package backtracking stress test (Bug 3 regression test)
    // -------------------------------------------------------------------------
    private static void testBacktrackingPast64Packages() {
        List<String> rootV2Deps = new ArrayList<>();
        for (int i = 1; i <= 75; i++) {
            String nodeName = String.format("node-%02d", i);
            rootV2Deps.add(nodeName + "==1.0.0");
            if (i == 1) {
                addPkg(nodeName, List.of(new MockRelease("1.0.0", ">=3.8", List.of("conflict-core==1.0.0"))));
            } else if (i == 75) {
                addPkg(nodeName, List.of(new MockRelease("1.0.0", ">=3.8", List.of("conflict-core==2.0.0"))));
            } else {
                addPkg(nodeName, List.of(new MockRelease("1.0.0", ">=3.8", List.of())));
            }
        }

        addPkg("conflict-core", List.of(
                new MockRelease("2.0.0", ">=3.8", List.of()),
                new MockRelease("1.0.0", ">=3.8", List.of())
        ));
        addPkg("root-pkg", List.of(
                new MockRelease("2.0.0", ">=3.8", rootV2Deps),
                new MockRelease("1.0.0", ">=3.8", List.of("node-75==1.0.0"))
        ));

        Map<String, String> res = solve("root-pkg");
        assertEq("1.0.0", res.get("root-pkg"), "root-pkg should backtrack to 1.0.0");
        assertEq("1.0.0", res.get("node-75"), "node-75 (pkgId >= 64) should resolve after backtrack");
        assertEq("2.0.0", res.get("conflict-core"), "conflict-core should resolve to 2.0.0");
    }

    // -------------------------------------------------------------------------
    // TEST 2: Valid 3-node cycle (A -> B -> C -> A)
    // -------------------------------------------------------------------------
    private static void testValidThreeNodeCycle() {
        addPkg("cyc-a", List.of(new MockRelease("1.0.0", ">=3.8", List.of("cyc-b>=1.0.0"))));
        addPkg("cyc-b", List.of(new MockRelease("1.0.0", ">=3.8", List.of("cyc-c>=1.0.0"))));
        addPkg("cyc-c", List.of(new MockRelease("1.0.0", ">=3.8", List.of("cyc-a>=1.0.0"))));

        Map<String, String> res = solve("cyc-a");
        assertEq("1.0.0", res.get("cyc-a"), "cyc-a");
        assertEq("1.0.0", res.get("cyc-b"), "cyc-b");
        assertEq("1.0.0", res.get("cyc-c"), "cyc-c");
    }

    // -------------------------------------------------------------------------
    // TEST 3: Contradictory mutual cycle requiring backtrack
    // -------------------------------------------------------------------------
    private static void testConflictingCycleBacktrack() {
        addPkg("cyc-x", List.of(
                new MockRelease("2.0.0", ">=3.8", List.of("cyc-y==2.0.0")),
                new MockRelease("1.0.0", ">=3.8", List.of("cyc-y==1.0.0"))
        ));
        addPkg("cyc-y", List.of(
                new MockRelease("2.0.0", ">=3.8", List.of("cyc-x==1.0.0")), // contradicts cyc-x==2.0.0
                new MockRelease("1.0.0", ">=3.8", List.of("cyc-x==1.0.0"))
        ));

        Map<String, String> res = solve("cyc-x");
        assertEq("1.0.0", res.get("cyc-x"), "cyc-x should backtrack to 1.0.0");
        assertEq("1.0.0", res.get("cyc-y"), "cyc-y should resolve to 1.0.0");
    }

    // -------------------------------------------------------------------------
    // TEST 4: Deep diamond multi-version backtracking
    // -------------------------------------------------------------------------
    private static void testDeepDiamondBacktracking() {
        addPkg("app", List.of(new MockRelease("1.0.0", ">=3.8", List.of("left>=1.0.0", "right>=1.0.0"))));
        addPkg("left", List.of(
                new MockRelease("5.0.0", ">=3.8", List.of("shared>=2.0.0")),
                new MockRelease("4.0.0", ">=3.8", List.of("shared>=2.0.0")),
                new MockRelease("3.0.0", ">=3.8", List.of("shared>=2.0.0")),
                new MockRelease("2.0.0", ">=3.8", List.of("shared>=1.0.0,<2.0.0")),
                new MockRelease("1.0.0", ">=3.8", List.of("shared>=1.0.0,<2.0.0"))
        ));
        addPkg("right", List.of(new MockRelease("1.0.0", ">=3.8", List.of("shared<2.0.0"))));
        addPkg("shared", List.of(
                new MockRelease("2.1.0", ">=3.8", List.of()),
                new MockRelease("2.0.0", ">=3.8", List.of()),
                new MockRelease("1.5.0", ">=3.8", List.of()),
                new MockRelease("1.0.0", ">=3.8", List.of())
        ));

        Map<String, String> res = solve("app");
        assertEq("2.0.0", res.get("left"), "left should backtrack to highest compatible 2.0.0");
        assertEq("1.0.0", res.get("right"), "right");
        assertEq("1.5.0", res.get("shared"), "shared should pick 1.5.0");
    }

    // -------------------------------------------------------------------------
    // TEST 5: Unsatisfiable dependency graph fails cleanly
    // -------------------------------------------------------------------------
    private static void testUnsatisfiableGraph() {
        addPkg("unsat-root", List.of(new MockRelease("1.0.0", ">=3.8", List.of("branch-a==1.0.0", "branch-b==1.0.0"))));
        addPkg("branch-a", List.of(new MockRelease("1.0.0", ">=3.8", List.of("leaf>=2.0.0"))));
        addPkg("branch-b", List.of(new MockRelease("1.0.0", ">=3.8", List.of("leaf<2.0.0"))));
        addPkg("leaf", List.of(
                new MockRelease("2.0.0", ">=3.8", List.of()),
                new MockRelease("1.0.0", ">=3.8", List.of())
        ));

        boolean threw = false;
        try {
            solve("unsat-root");
        } catch (RuntimeException expected) {
            threw = true;
        }
        if (!threw) {
            throw new AssertionError("Expected unsatisfiable graph to throw a resolution exception!");
        }
    }

    // -------------------------------------------------------------------------
    // TEST 6: Requires-Python filtering (Python 3.12.4)
    // -------------------------------------------------------------------------
    private static void testRequiresPythonFiltering() {
        addPkg("pyver-pkg", List.of(
                new MockRelease("3.0.0", ">=3.13", List.of()),
                new MockRelease("2.0.0", "<3.12", List.of()),
                new MockRelease("1.5.0", ">=3.10,<3.13", List.of())
        ));

        Map<String, String> res = solve("pyver-pkg");
        assertEq("1.5.0", res.get("pyver-pkg"), "pyver-pkg should skip 3.0.0 (>=3.13) and 2.0.0 (<3.12)");
    }

    // -------------------------------------------------------------------------
    // Helpers & Mock PEP 691 / PEP 658 Server
    // -------------------------------------------------------------------------
    private static void addPkg(String name, List<MockRelease> releases) {
        REGISTRY.put(PyPIClient.normalizePackageName(name), releases);
    }

    private static Map<String, String> solve(String... specs) {
        try (PyPIClient client = new PyPIClient(8)) {
            PubGrubSolver solver = new PubGrubSolver(client, TEST_ENV);
            List<ResolvedPackage> resolved = solver.resolve(List.of(specs));
            Map<String, String> map = new LinkedHashMap<>();
            for (ResolvedPackage rp : resolved) {
                map.put(PyPIClient.normalizePackageName(rp.name()), rp.version().toString());
            }
            return map;
        }
    }

    private static void assertEq(String expected, String actual, String msg) {
        if (!expected.equals(actual)) {
            throw new AssertionError(msg + " -> expected: " + expected + ", actual: " + actual);
        }
    }

    private static void handleRequest(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.startsWith("/simple/")) {
            String pkg = path.substring("/simple/".length()).replace("/", "");
            List<MockRelease> releases = REGISTRY.get(pkg.toLowerCase(Locale.ROOT));
            if (releases == null) {
                send(ex, 404, "text/plain", "Not found");
                return;
            }
            String json = buildPep691Json(pkg, releases);
            send(ex, 200, "application/vnd.pypi.simple.v1+json", json);
            return;
        }

        if (path.startsWith("/files/") && path.endsWith(".whl.metadata")) {
            String fname = path.substring("/files/".length(), path.length() - ".whl.metadata".length());
            // Format: <norm_with_underscores>-<version>-py3-none-any
            String[] parts = fname.split("-");
            String pkg = PyPIClient.normalizePackageName(parts[0]);
            String ver = parts[1];
            List<MockRelease> releases = REGISTRY.get(pkg);
            if (releases != null) {
                for (MockRelease r : releases) {
                    if (r.version().equals(ver)) {
                        StringBuilder meta = new StringBuilder();
                        meta.append("Metadata-Version: 2.1\n");
                        meta.append("Name: ").append(pkg).append("\n");
                        meta.append("Version: ").append(ver).append("\n");
                        for (String dep : r.requiresDist()) {
                            meta.append("Requires-Dist: ").append(dep).append("\n");
                        }
                        send(ex, 200, "text/plain; charset=utf-8", meta.toString());
                        return;
                    }
                }
            }
            send(ex, 404, "text/plain", "Metadata not found");
            return;
        }

        send(ex, 404, "text/plain", "Unknown path: " + path);
    }

    private static String buildPep691Json(String pkg, List<MockRelease> releases) {
        String wheelPkg = pkg.replace('-', '_');
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"meta\":{\"api-version\":\"1.1\"},\"name\":\"").append(pkg).append("\",\"files\":[");
        for (int i = 0; i < releases.size(); i++) {
            MockRelease r = releases.get(i);
            if (i > 0) sb.append(',');
            String filename = wheelPkg + "-" + r.version() + "-py3-none-any.whl";
            String url = baseUrl + "/files/" + filename;
            String sha = String.format("%064x", (pkg + r.version()).hashCode() & 0xffffffffL);
            sb.append("{\"filename\":\"").append(filename).append("\",");
            sb.append("\"url\":\"").append(url).append("\",");
            sb.append("\"hashes\":{\"sha256\":\"").append(sha).append("\"},");
            sb.append("\"requires-python\":\"").append(r.requiresPython()).append("\",");
            sb.append("\"core-metadata\":{\"sha256\":\"").append(sha).append("\"},");
            sb.append("\"data-dist-info-metadata\":{\"sha256\":\"").append(sha).append("\"},");
            sb.append("\"yanked\":false}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void send(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}