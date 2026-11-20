package dev.jolt;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public final class JarPackager {
    public static void main(String[] args) throws IOException {
        Path classesDir = Path.of(args[0]);
        Path outputJar = Path.of(args[1]);

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "dev.jolt.Main");

        try (JarOutputStream jos = new JarOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputJar.toFile()), 65536), manifest)) {
            Files.walkFileTree(classesDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String relPath = classesDir.relativize(file).toString().replace('\\', '/');
                    if (!relPath.equals("META-INF/MANIFEST.MF")) {
                        JarEntry entry = new JarEntry(relPath);
                        jos.putNextEntry(entry);
                        Files.copy(file, jos);
                        jos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}