package dev.jolt.net;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

public final class PyPIClient implements AutoCloseable {
    private static final String PYPI_SIMPLE_BASE = System.getProperty("jolt.index.url", "https://pypi.org/simple/");
    private static final String PEP691_ACCEPT = "application/vnd.pypi.simple.v1+json";

    private final HttpClient httpClient;
    private final ExecutorService virtualExecutor;

    public record ArtifactFile(
            String filename,
            String url,
            String sha256,
            String requiresPython,
            boolean hasDetachedMetadata,
            boolean yanked
    ) {}

    public record PackageIndex(
            String name,
            List<String> versions,
            List<ArtifactFile> files,
            String etag
    ) {}

    public HttpClient httpClient() {
        return this.httpClient;
    }

    public PyPIClient(int maxConcurrency) {
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(this.virtualExecutor)
                .build();
    }

    public static String normalizePackageName(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        boolean prevDash = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '-' || c == '_' || c == '.') {
                if (!prevDash) {
                    sb.append('-');
                    prevDash = true;
                }
            } else {
                sb.append(Character.toLowerCase(c));
                prevDash = false;
            }
        }
        return sb.toString();
    }

    public CompletableFuture<PackageIndex> fetchPackageIndexAsync(String packageName) {
        String normalized = normalizePackageName(packageName);
        URI uri = URI.create(PYPI_SIMPLE_BASE + normalized + "/");

        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", PEP691_ACCEPT)
                .header("Accept-Encoding", "gzip")
                .header("User-Agent", "jolt/0.1.0 (GraalVM-Native; HTTP2)")
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() == 404) {
                        throw new IllegalArgumentException("Package not found on PyPI: " + packageName);
                    }
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("PyPI returned HTTP " + response.statusCode() + " for " + packageName);
                    }
                    byte[] body = decompressIfGzipped(response);
                    String etag = response.headers().firstValue("etag").orElse("");
                    return Pep691ByteParser.parse(normalized, body, etag);
                });
    }

    public PackageIndex fetchPackageIndex(String packageName) {
        return fetchPackageIndexAsync(packageName).join();
    }

    public CompletableFuture<byte[]> fetchDetachedWheelMetadataAsync(ArtifactFile wheel) {
        URI metaUri = URI.create(wheel.url() + ".metadata");
        HttpRequest req = HttpRequest.newBuilder(metaUri)
                .header("Accept-Encoding", "gzip")
                .header("User-Agent", "jolt/0.1.0")
                .GET()
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new IllegalStateException("Failed to fetch PEP 658 metadata: HTTP " + resp.statusCode());
                    }
                    return decompressIfGzipped(resp);
                });
    }

    private static byte[] decompressIfGzipped(HttpResponse<byte[]> response) {
        byte[] raw = response.body();
        String encoding = response.headers().firstValue("content-encoding").orElse("");
        if ("gzip".equalsIgnoreCase(encoding) || (raw.length >= 2 && raw[0] == (byte) 0x1f && raw[1] == (byte) 0x8b)) {
            try (InputStream gis = new GZIPInputStream(new ByteArrayInputStream(raw), 16384)) {
                return gis.readAllBytes();
            } catch (IOException e) {
                throw new RuntimeException("Corrupt GZIP stream from PyPI", e);
            }
        }
        return raw;
    }

    @Override
    public void close() {
        virtualExecutor.close();
    }
}