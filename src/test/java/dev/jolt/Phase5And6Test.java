package dev.jolt;

import dev.jolt.cache.BinaryMetaCache;
import dev.jolt.installer.StreamingUnpacker;
import dev.jolt.net.PyPIClient.ArtifactFile;
import dev.jolt.net.PyPIClient.PackageIndex;
import dev.jolt.pep.Pep508Requirement;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Phase5And6Test {
    public static void main(String[] args) throws Exception {
        Path tempCache = Files.createTempDirectory("jolt-phase5-test-");
        try {
            BinaryMetaCache cache = new BinaryMetaCache(tempCache, false);

            // 1. Test Binary PackageIndex Serialization & Instant Reload
            PackageIndex original = new PackageIndex(
                    "requests",
                    List.of("2.31.0", "2.32.3"),
                    List.of(new ArtifactFile(
                            "requests-2.32.3-py3-none-any.whl",
                            "https://files.pythonhosted.org/requests-2.32.3.whl",
                            "70761cfe03c773ceb22aa2f671b4757976145175cdfca038c02654d061d6dcc6",
                            ">=3.8",
                            true,
                            false
                    )),
                    "\"etag-xyz\""
            );

            cache.putPackageIndex(original);
            PackageIndex loaded = cache.getPackageIndex("requests");
            assert loaded != null;
            assert "requests".equals(loaded.name());
            assert loaded.versions().size() == 2;
            assert loaded.files().size() == 1;
            assert loaded.files().get(0).hasDetachedMetadata();

            // 2. Test Wheel Dependencies Cache
            String shaKey = "70761cfe03c773ceb22aa2f671b4757976145175cdfca038c02654d061d6dcc6";
            cache.putWheelDeps(shaKey, new byte[0], List.of("urllib3 (<3,>=1.21.1)", "idna (<4,>=2.5)"));
            Pep508Requirement[] deps = cache.getWheelDeps(shaKey);
            assert deps != null && deps.length == 2;
            assert "urllib3".equals(deps[0].name());

            System.out.println("All Phase 5 & 6 BinaryMetaCache tests passed.");
        } finally {
            StreamingUnpacker.deleteRecursively(tempCache);
        }
    }
}