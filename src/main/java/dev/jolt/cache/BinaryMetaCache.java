package dev.jolt.cache;

import dev.jolt.net.PyPIClient.ArtifactFile;
import dev.jolt.net.PyPIClient.PackageIndex;
import dev.jolt.pep.Pep508Requirement;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class BinaryMetaCache {
    private static final int MAGIC_HEADER = 0x4A4F4C54; // "JOLT"
    private static final int FORMAT_VERSION = 1;

    private final Path metaDir;
    private final Path depsDir;
    private final Path tmpDir;
    private final boolean bypassCache;

    public BinaryMetaCache(Path cacheRoot, boolean bypassCache) throws IOException {
        this.metaDir = cacheRoot.resolve("http_meta");
        this.depsDir = cacheRoot.resolve("wheel_deps");
        this.tmpDir = cacheRoot.resolve("tmp");
        this.bypassCache = bypassCache;
        Files.createDirectories(this.metaDir);
        Files.createDirectories(this.depsDir);
        Files.createDirectories(this.tmpDir);
    }

    public PackageIndex getPackageIndex(String normalizedPkgName) {
        if (bypassCache) return null;
        Path file = metaDir.resolve(normalizedPkgName + ".bin");
        if (!Files.isRegularFile(file)) return null;

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 16384))) {
            if (in.readInt() != MAGIC_HEADER) return null;
            if (in.readInt() != FORMAT_VERSION) return null;

            String name = in.readUTF();
            String etag = in.readUTF();

            int verCount = in.readInt();
            List<String> versions = new ArrayList<>(verCount);
            for (int i = 0; i < verCount; i++) {
                versions.add(in.readUTF());
            }

            int fileCount = in.readInt();
            List<ArtifactFile> files = new ArrayList<>(fileCount);
            for (int i = 0; i < fileCount; i++) {
                String filename = in.readUTF();
                String url = in.readUTF();
                String sha256 = in.readUTF();
                String reqPy = in.readUTF();
                byte flags = in.readByte();
                boolean hasMeta = (flags & 1) != 0;
                boolean yanked = (flags & 2) != 0;
                files.add(new ArtifactFile(
                        filename,
                        url,
                        sha256.isEmpty() ? null : sha256,
                        reqPy.isEmpty() ? null : reqPy,
                        hasMeta,
                        yanked
                ));
            }
            return new PackageIndex(name, versions, files, etag);
        } catch (IOException e) {
            return null;
        }
    }

    public void putPackageIndex(PackageIndex idx) {
        Path staging = tmpDir.resolve("meta-" + UUID.randomUUID() + ".tmp");
        Path target = metaDir.resolve(idx.name() + ".bin");

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(staging), 16384))) {
            out.writeInt(MAGIC_HEADER);
            out.writeInt(FORMAT_VERSION);
            out.writeUTF(idx.name());
            out.writeUTF(idx.etag() != null ? idx.etag() : "");

            List<String> versions = idx.versions();
            out.writeInt(versions.size());
            for (String v : versions) {
                out.writeUTF(v);
            }

            List<ArtifactFile> files = idx.files().stream().filter(f -> !f.yanked() && f.filename().endsWith(".whl")).toList();
            out.writeInt(files.size());
            for (ArtifactFile f : files) {
                out.writeUTF(f.filename());
                out.writeUTF(f.url());
                out.writeUTF(f.sha256() != null ? f.sha256() : "");
                out.writeUTF(f.requiresPython() != null ? f.requiresPython() : "");
                byte flags = (byte) ((f.hasDetachedMetadata() ? 1 : 0) | (f.yanked() ? 2 : 0));
                out.writeByte(flags);
            }
            out.flush();
        } catch (IOException e) {
            try { Files.deleteIfExists(staging); } catch (IOException ignored) {}
            return;
        }

        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try { Files.deleteIfExists(staging); } catch (IOException ignored) {}
        }
    }

    public Pep508Requirement[] getWheelDeps(String cacheKey) {
        if (bypassCache || cacheKey == null || cacheKey.isEmpty()) return null;
        Path file = depsDir.resolve(sanitizeKey(cacheKey) + ".deps");
        if (!Files.isRegularFile(file)) return null;

        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<Pep508Requirement> reqs = new ArrayList<>(lines.size());
            for (String line : lines) {
                if (!line.isBlank()) {
                    Pep508Requirement r = Pep508Requirement.parse(line);
                    if (r != null) reqs.add(r);
                }
            }
            return reqs.toArray(new Pep508Requirement[0]);
        } catch (IOException e) {
            return null;
        }
    }

    public void putWheelDeps(String cacheKey, byte[] rawMetadataBytes, List<String> rawReqLines) {
        if (cacheKey == null || cacheKey.isEmpty()) return;
        Path target = depsDir.resolve(sanitizeKey(cacheKey) + ".deps");
        try {
            Files.write(target, String.join("\n", rawReqLines).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private static String sanitizeKey(String key) {
        if (key.length() == 64 && key.indexOf('/') == -1 && key.indexOf(':') == -1) {
            return key.toLowerCase();
        }
        return Integer.toHexString(key.hashCode());
    }
}