// File: src/test/java/dev/jolt/Phase2CoreTest.java
package dev.jolt;

import dev.jolt.cli.CliConfig;
import dev.jolt.net.PyPIClient;
import dev.jolt.net.PyPIClient.PackageIndex;

import java.nio.charset.StandardCharsets;

public final class Phase2CoreTest {
    public static void main(String[] args) throws Exception {
        testCliParser();
        testPackageNormalization();
        testPep691ByteParser();
        System.out.println("All Phase 2 Core Infrastructure tests passed.");
    }

    private static void testCliParser() {
        CliConfig cfg = CliConfig.parse(new String[]{"install", "requests", "numpy>=2.0", "-v", "-j", "24"});
        assert cfg.command == CliConfig.Command.INSTALL;
        assert cfg.targets.size() == 2;
        assert cfg.verbose;
        assert cfg.maxConcurrency == 24;
    }

    private static void testPackageNormalization() {
        assert "typing-extensions".equals(PyPIClient.normalizePackageName("Typing_Extensions"));
        assert "zope-interface".equals(PyPIClient.normalizePackageName("zope.interface"));
        assert "my-pkg".equals(PyPIClient.normalizePackageName("my---___..pkg"));
    }

    private static void testPep691ByteParser() throws Exception {
        String sampleJson = """
                {
                  "meta": {"api-version": "1.1"},
                  "name": "requests",
                  "versions": ["2.31.0", "2.32.3"],
                  "files": [
                    {
                      "filename": "requests-2.32.3-py3-none-any.whl",
                      "url": "https://files.pythonhosted.org/packages/requests-2.32.3-py3-none-any.whl",
                      "hashes": {"sha256": "70761cfe03c773ceb22aa2f671b4757976145175cdfca038c02654d061d6dcc6"},
                      "requires-python": ">=3.8",
                      "data-dist-info-metadata": {"sha256": "abc1234"},
                      "yanked": false
                    }
                  ]
                }
                """;
        // Invoke package-private parser via reflection or direct call in same package
        var method = Class.forName("dev.jolt.net.Pep691ByteParser")
                .getDeclaredMethod("parse", String.class, byte[].class, String.class);
        method.setAccessible(true);
        PackageIndex idx = (PackageIndex) method.invoke(
                null, "requests", sampleJson.getBytes(StandardCharsets.UTF_8), "\"etag-123\""
        );

        assert idx.versions().size() == 2;
        assert idx.files().size() == 1;
        assert idx.files().get(0).hasDetachedMetadata();
        assert ">=3.8".equals(idx.files().get(0).requiresPython());
    }
}