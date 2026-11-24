package dev.jolt.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * Cryptographically verified self-updater for Jolt native binaries.
 * - Resolves latest release via GitHub Releases redirect (no API rate limit)
 * - Verifies SHA256SUMS.txt before touching the installed executable
 * - Performs a safe Windows rename-swap (fast.exe -> fast.exe.old) with rollback
 */
public final class SelfUpdater {

    private static final String DEFAULT_REPO = "SarthakMitra323/Jolt-Package-Manager";

    private SelfUpdater() {}

    /**
     * Best-effort cleanup of leftover fast.exe.old from a previous self-update.
     */
    public static void cleanupOldBinary() {
        try {
            Optional<Path> self = detectNativeExecutable();
            if (self.isPresent()) {
                Path oldBin = self.get().resolveSibling(self.get().getFileName().toString() + ".old");
                Files.deleteIfExists(oldBin);
            }
        } catch (Exception ignored) {
        }
    }

    public static void run(boolean quiet) throws IOException, InterruptedException {
        String repo = System.getenv("JOLT_REPO");
        if (repo == null || repo.isBlank()) {
            repo = DEFAULT_REPO;
        }

        Path targetExe = resolveTargetExecutable();
        String assetName = resolveAssetName();

        HttpClient redirectClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpClient noRedirectClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        String latestTag = fetchLatestTag(noRedirectClient, repo);
        if (!quiet) {
            System.out.printf("Checking GitHub Releases (%s) -> latest is %s%n", repo, latestTag);
        }

        String baseUrl = "https://github.com/" + repo + "/releases/download/" + latestTag;
        String sumsUrl = baseUrl + "/SHA256SUMS.txt";
        String binUrl = baseUrl + "/" + assetName;

        // 1. Fetch and parse SHA256SUMS.txt
        HttpRequest sumsReq = HttpRequest.newBuilder(URI.create(sumsUrl))
                .header("User-Agent", "jolt-self-updater/1.0.0")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> sumsResp = redirectClient.send(sumsReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (sumsResp.statusCode() != 200) {
            throw new IOException("Failed to fetch SHA256SUMS.txt from " + sumsUrl + " (HTTP " + sumsResp.statusCode() + ")");
        }

        String expectedHash = extractExpectedHash(sumsResp.body(), assetName);
        if (expectedHash == null) {
            throw new SecurityException("Security abort: " + assetName + " entry not found in SHA256SUMS.txt");
        }

        // 2. Compare against currently installed binary hash
        if (Files.isRegularFile(targetExe)) {
            String currentHash = sha256File(targetExe);
            if (currentHash.equalsIgnoreCase(expectedHash)) {
                if (!quiet) {
                    System.out.printf("Jolt is already up to date (%s, sha256: %s)%n",
                            latestTag, currentHash.substring(0, 12));
                }
                return;
            }
        }

        // 3. Stream download to sibling staging file while verifying SHA-256
        Path parentDir = targetExe.getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }
        Path stagedExe = targetExe.resolveSibling(targetExe.getFileName().toString() + ".new");
        Path backupExe = targetExe.resolveSibling(targetExe.getFileName().toString() + ".old");

        if (!quiet) {
            System.out.printf("Downloading %s (%s)...%n", assetName, latestTag);
        }

        try {
            HttpRequest binReq = HttpRequest.newBuilder(URI.create(binUrl))
                    .header("User-Agent", "jolt-self-updater/1.0.0")
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();

            HttpResponse<InputStream> binResp = redirectClient.send(binReq, HttpResponse.BodyHandlers.ofInputStream());
            if (binResp.statusCode() != 200) {
                binResp.body().close();
                throw new IOException("Failed to download " + assetName + " (HTTP " + binResp.statusCode() + ")");
            }

            String actualHash;
            MessageDigest md = sha256Digest();
            try (InputStream in = new DigestInputStream(binResp.body(), md);
                 OutputStream out = Files.newOutputStream(stagedExe,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            actualHash = HexFormat.of().formatHex(md.digest());

            if (!actualHash.equalsIgnoreCase(expectedHash)) {
                Files.deleteIfExists(stagedExe);
                throw new SecurityException(
                        "SECURITY ABORT: SHA-256 mismatch!\n  Expected: " + expectedHash + "\n  Actual:   " + actualHash);
            }

            if (!quiet) {
                System.out.printf("Verified SHA-256: %s%n", actualHash);
            }

            // 4. Safe atomic swap with rollback
            Files.deleteIfExists(backupExe);
            boolean backedUp = false;
            if (Files.exists(targetExe)) {
                Files.move(targetExe, backupExe, StandardCopyOption.REPLACE_EXISTING);
                backedUp = true;
            }

            try {
                Files.move(stagedExe, targetExe, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException swapEx) {
                if (backedUp && Files.exists(backupExe)) {
                    try {
                        Files.move(backupExe, targetExe, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ignored) {}
                }
                throw swapEx;
            }

            if (!quiet) {
                System.out.printf("Updated %s to %s%n", targetExe, latestTag);
            }
        } catch (Throwable t) {
            try { Files.deleteIfExists(stagedExe); } catch (IOException ignored) {}
            if (t instanceof IOException ioe) throw ioe;
            if (t instanceof InterruptedException ie) throw ie;
            if (t instanceof RuntimeException re) throw re;
            throw new IOException("Self-update failed", t);
        }
    }

    private static String fetchLatestTag(HttpClient noRedirectClient, String repo) throws IOException, InterruptedException {
        URI latestUri = URI.create("https://github.com/" + repo + "/releases/latest");
        HttpRequest req = HttpRequest.newBuilder(latestUri)
                .header("User-Agent", "jolt-self-updater/1.0.0")
                .timeout(Duration.ofSeconds(10))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<Void> resp = noRedirectClient.send(req, HttpResponse.BodyHandlers.discarding());
        Optional<String> location = resp.headers().firstValue("location");
        if (location.isPresent()) {
            String loc = location.get();
            int tagIdx = loc.indexOf("/tag/");
            if (tagIdx != -1) {
                return loc.substring(tagIdx + 5).trim();
            }
        }
        throw new IOException("No published GitHub Release found for " + repo + " (HTTP " + resp.statusCode() + ")");
    }

    private static String extractExpectedHash(String sumsBody, String assetName) {
        for (String line : sumsBody.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2) {
                String filePart = parts[1].startsWith("*") ? parts[1].substring(1) : parts[1];
                if (filePart.equalsIgnoreCase(assetName) && parts[0].length() == 64) {
                    return parts[0].toLowerCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    private static Optional<Path> detectNativeExecutable() {
        Optional<String> cmd = ProcessHandle.current().info().command();
        if (cmd.isEmpty()) return Optional.empty();
        Path exe = Path.of(cmd.get()).toAbsolutePath().normalize();
        String name = exe.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.equals("java.exe") || name.equals("javaw.exe") || name.equals("java")) {
            return Optional.empty();
        }
        return Optional.of(exe);
    }

    private static Path resolveTargetExecutable() {
        Optional<Path> nativeExe = detectNativeExecutable();
        if (nativeExe.isPresent()) {
            return nativeExe.get();
        }

        // Fallback when invoked via JVM during development: update user-installed fast.exe if present
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            Path installed = Path.of(localAppData, "jolt", "bin", "fast.exe");
            if (Files.isRegularFile(installed)) {
                return installed;
            }
        }
        throw new IllegalStateException(
                "self-update requires the native fast.exe binary (or an installed copy in %LOCALAPPDATA%\\jolt\\bin\\fast.exe).");
    }

    private static String resolveAssetName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "fast-windows-x64.exe";
        if (os.contains("mac")) return "fast-macos-arm64";
        return "fast-linux-x64";
    }

    private static String sha256File(Path file) throws IOException {
        MessageDigest md = sha256Digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}