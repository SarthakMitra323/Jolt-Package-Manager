package dev.jolt.installer;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class StreamingUnpacker {

    private final Path cacheRoot;
    private final Path wheelsDir;
    private final Path tmpDir;

    public StreamingUnpacker(Path cacheRoot) throws IOException {
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
        this.wheelsDir = this.cacheRoot.resolve("wheels_v1");
        this.tmpDir = this.cacheRoot.resolve("tmp");
        Files.createDirectories(this.wheelsDir);
        Files.createDirectories(this.tmpDir);
    }

    public Path defaultCacheRoot() {
        return cacheRoot;
    }

    public Path getCachedWheelDir(String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.length() < 4) return null;
        String normHash = expectedSha256.toLowerCase();
        Path targetDir = wheelsDir.resolve(normHash.substring(0, 2)).resolve(normHash);
        if (Files.isRegularFile(targetDir.resolve(".complete"))) {
            return targetDir;
        }
        return null;
    }

    public Path unpackAndVerify(InputStream rawStream, String expectedSha256) throws IOException {
        Path existing = getCachedWheelDir(expectedSha256);
        if (existing != null) {
            rawStream.close();
            return existing;
        }

        Path stagingDir = tmpDir.resolve("unpack-" + UUID.randomUUID());
        Files.createDirectories(stagingDir);

        try {
            MessageDigest sha256 = createSha256();
            DigestInputStream digestStream = new DigestInputStream(rawStream, sha256);
            byte[] transferBuf = new byte[65536];

            try (ZipInputStream zis = new ZipInputStream(new NonClosingInputStream(digestStream))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String entryName = entry.getName();
                    Path destPath = stagingDir.resolve(entryName).normalize();

                    if (!destPath.startsWith(stagingDir)) {
                        throw new SecurityException("Malicious wheel entry outside target dir: " + entryName);
                    }

                    if (entry.isDirectory()) {
                        Files.createDirectories(destPath);
                    } else {
                        Path parent = destPath.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        try (OutputStream out = Files.newOutputStream(destPath,
                                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                            int n;
                            while ((n = zis.read(transferBuf)) != -1) {
                                out.write(transferBuf, 0, n);
                            }
                        }
                    }
                    zis.closeEntry();
                }
            }

            while (digestStream.read(transferBuf) != -1) {}

            String actualHash = HexFormat.of().formatHex(sha256.digest());
            if (expectedSha256 != null && !expectedSha256.isBlank()) {
                if (!actualHash.equalsIgnoreCase(expectedSha256)) {
                    throw new SecurityException(
                            "Wheel SHA-256 integrity mismatch! Expected: " + expectedSha256 + ", Actual: " + actualHash
                    );
                }
            }

            Path bucketDir = wheelsDir.resolve(actualHash.substring(0, 2));
            Files.createDirectories(bucketDir);
            Path finalDir = bucketDir.resolve(actualHash);

            Files.writeString(stagingDir.resolve(".complete"), actualHash);

            try {
                Files.move(stagingDir, finalDir, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveEx) {
                if (!Files.isRegularFile(finalDir.resolve(".complete"))) {
                    throw moveEx;
                }
                deleteRecursively(stagingDir);
            }

            return finalDir;
        } catch (Throwable t) {
            deleteRecursively(stagingDir);
            if (t instanceof IOException ioe) throw ioe;
            if (t instanceof RuntimeException re) throw re;
            throw new IOException("Unpack failed", t);
        } finally {
            rawStream.close();
        }
    }

    public static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try {
            if (Files.isDirectory(path)) {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                    for (Path entry : entries) {
                        deleteRecursively(entry);
                    }
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static MessageDigest createSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must be supported by Java runtime", e);
        }
    }

    private static final class NonClosingInputStream extends FilterInputStream {
        NonClosingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {}
    }
}