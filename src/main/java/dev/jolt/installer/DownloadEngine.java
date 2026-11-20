package dev.jolt.installer;

import dev.jolt.net.PyPIClient.ArtifactFile;
import dev.jolt.resolver.PubGrubSolver.ResolvedPackage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public final class DownloadEngine implements AutoCloseable {

    public record UnpackedPackage(
            ResolvedPackage resolved,
            Path unpackedWheelDir,
            boolean cacheHit
    ) {}

    private final HttpClient httpClient;
    private final ExecutorService virtualPool;
    private final Semaphore concurrencyLimiter;
    private final StreamingUnpacker unpacker;
    private final Map<String, CompletableFuture<Path>> inFlightByKey = new ConcurrentHashMap<>();

    public DownloadEngine(StreamingUnpacker unpacker, int maxConcurrency) {
        this.unpacker = unpacker;
        this.virtualPool = Executors.newVirtualThreadPerTaskExecutor();
        this.concurrencyLimiter = new Semaphore(Math.max(1, maxConcurrency));
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(this.virtualPool)
                .build();
    }

    public List<UnpackedPackage> downloadAndUnpackAll(List<ResolvedPackage> packages) {
        List<CompletableFuture<UnpackedPackage>> futures = new ArrayList<>(packages.size());
        for (ResolvedPackage pkg : packages) {
            futures.add(downloadAndUnpackAsync(pkg));
        }

        List<UnpackedPackage> results = new ArrayList<>(packages.size());
        for (CompletableFuture<UnpackedPackage> f : futures) {
            results.add(f.join());
        }
        return results;
    }

    public CompletableFuture<UnpackedPackage> downloadAndUnpackAsync(ResolvedPackage pkg) {
        ArtifactFile artifact = pkg.wheelArtifact();
        String sha = artifact.sha256();

        Path cached = unpacker.getCachedWheelDir(sha);
        if (cached != null) {
            return CompletableFuture.completedFuture(new UnpackedPackage(pkg, cached, true));
        }

        String dedupKey = (sha != null && !sha.isBlank()) ? sha.toLowerCase() : artifact.url();
        CompletableFuture<Path> sharedFuture = inFlightByKey.computeIfAbsent(
                dedupKey,
                k -> CompletableFuture.supplyAsync(() -> fetchWithRetryAndUnpack(artifact), virtualPool)
        );

        return sharedFuture.thenApply(dir -> new UnpackedPackage(pkg, dir, false));
    }

    private Path fetchWithRetryAndUnpack(ArtifactFile artifact) {
        try {
            concurrencyLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Download interrupted for " + artifact.filename(), e);
        }

        try {
            int maxAttempts = 3;
            long backoffMs = 150;
            Throwable lastError = null;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(artifact.url()))
                            .header("User-Agent", "jolt/0.1.0 (GraalVM-Native; StreamingUnpacker)")
                            .timeout(Duration.ofSeconds(60))
                            .GET()
                            .build();

                    HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
                    int status = resp.statusCode();
                    if (status == 200) {
                        return unpacker.unpackAndVerify(resp.body(), artifact.sha256());
                    }
                    resp.body().close();

                    if (status == 429 || status >= 500) {
                        lastError = new IOException("HTTP " + status + " from " + artifact.url());
                    } else {
                        throw new IllegalStateException("Fatal HTTP " + status + " downloading " + artifact.filename());
                    }
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    lastError = e;
                }

                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    backoffMs *= 2;
                }
            }
            throw new RuntimeException("Failed to download " + artifact.filename() + " after " + maxAttempts + " attempts", lastError);
        } finally {
            concurrencyLimiter.release();
        }
    }

    @Override
    public void close() {
        virtualPool.close();
    }
}