package dev.jolt.installer;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class InstallTransaction implements AutoCloseable {

    private final java.util.List<Path> createdFiles = Collections.synchronizedList(new ArrayList<>(1024));
    private final java.util.List<Path> createdDirs = Collections.synchronizedList(new ArrayList<>(256));
    private final Set<Path> knownDirs = ConcurrentHashMap.newKeySet(256);
    private volatile boolean committed = false;
    private volatile boolean hardlinkSupported = true;
    private volatile Boolean cachedSameRoot = null;

    public void ensureDirectory(Path dir) throws IOException {
        if (dir == null || !knownDirs.add(dir)) {
            return; // Already verified or created in this transaction: 0 syscalls!
        }
        if (Files.isDirectory(dir)) return;

        Path parent = dir.getParent();
        if (parent != null) {
            ensureDirectory(parent);
        }
        try {
            Files.createDirectory(dir);
            createdDirs.add(dir);
        } catch (FileAlreadyExistsException ignored) {
        } catch (IOException e) {
            if (!Files.isDirectory(dir)) throw e;
        }
    }

    public void linkOrCopyFile(Path sourceInCache, Path targetInVenv) throws IOException {
        ensureDirectory(targetInVenv.getParent());

        if (hardlinkSupported && checkSameRoot(sourceInCache, targetInVenv)) {
            try {
                // Optimistic CreateHardLinkW without calling DeleteFileW first!
                Files.createLink(targetInVenv, sourceInCache);
                createdFiles.add(targetInVenv);
                return;
            } catch (FileAlreadyExistsException existsEx) {
                Files.deleteIfExists(targetInVenv);
                try {
                    Files.createLink(targetInVenv, sourceInCache);
                    createdFiles.add(targetInVenv);
                    return;
                } catch (IOException retryEx) {
                    hardlinkSupported = false;
                }
            } catch (UnsupportedOperationException | IOException ex) {
                hardlinkSupported = false;
            }
        }

        Files.copy(sourceInCache, targetInVenv, StandardCopyOption.REPLACE_EXISTING);
        createdFiles.add(targetInVenv);
    }

    private boolean checkSameRoot(Path a, Path b) {
        Boolean same = this.cachedSameRoot;
        if (same != null) return same;
        Path rootA = a.toAbsolutePath().getRoot();
        Path rootB = b.toAbsolutePath().getRoot();
        boolean res = (rootA != null && rootA.equals(rootB));
        this.cachedSameRoot = res;
        return res;
    }

    public void writeNewFile(Path targetInVenv, byte[] content) throws IOException {
        ensureDirectory(targetInVenv.getParent());
        Files.write(targetInVenv, content,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        createdFiles.add(targetInVenv);
    }

    public void commit() {
        this.committed = true;
    }

    public void rollback() {
        if (committed) return;
        synchronized (createdFiles) {
            for (int i = createdFiles.size() - 1; i >= 0; i--) {
                try { Files.deleteIfExists(createdFiles.get(i)); } catch (IOException ignored) {}
            }
        }
        synchronized (createdDirs) {
            for (int i = createdDirs.size() - 1; i >= 0; i--) {
                try { Files.deleteIfExists(createdDirs.get(i)); } catch (IOException ignored) {}
            }
        }
    }

    public int installedFileCount() {
        return createdFiles.size();
    }

    @Override
    public void close() {
        if (!committed) rollback();
    }
}