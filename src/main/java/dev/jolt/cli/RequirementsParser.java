package dev.jolt.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses pip/uv requirements.txt files:
 * - Handles blank lines and full/inline '#' comments
 * - Handles line continuations ('\') from pip-compile / uv pip compile
 * - Strips '--hash=...' options so hashed lockfiles work out of the box
 * - Recursively resolves nested '-r <file>' / '--requirement <file>' includes
 */
public final class RequirementsParser {

    private RequirementsParser() {}

    public static List<String> parseFile(Path reqFile) throws IOException {
        List<String> specs = new ArrayList<>(32);
        Set<Path> visited = new HashSet<>();
        parseRecursive(reqFile.toAbsolutePath().normalize(), specs, visited);
        return specs;
    }

    private static void parseRecursive(Path file, List<String> out, Set<Path> visited) throws IOException {
        if (!visited.add(file)) return; // Prevent circular -r includes
        if (!Files.isRegularFile(file)) {
            throw new IOException("Requirements file not found: " + file);
        }

        List<String> rawLines = Files.readAllLines(file, StandardCharsets.UTF_8);
        StringBuilder logicalLine = new StringBuilder(128);

        for (String raw : rawLines) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (logicalLine.length() == 0) continue;
            }

            // Handle trailing backslash line continuation
            if (trimmed.endsWith("\\")) {
                logicalLine.append(trimmed, 0, trimmed.length() - 1).append(' ');
                continue;
            }

            logicalLine.append(trimmed);
            String fullLine = logicalLine.toString().trim();
            logicalLine.setLength(0);

            if (fullLine.isEmpty() || fullLine.startsWith("#")) continue;

            // Strip inline comments ("requests>=2.31 # http")
            int commentIdx = fullLine.indexOf(" #");
            if (commentIdx != -1) {
                fullLine = fullLine.substring(0, commentIdx).trim();
            }

            // Handle nested -r / --requirement includes
            if (fullLine.startsWith("-r ") || fullLine.startsWith("--requirement ")) {
                String nestedPath = fullLine.substring(fullLine.indexOf(' ') + 1).trim();
                Path resolvedNested = file.getParent().resolve(nestedPath).normalize();
                parseRecursive(resolvedNested, out, visited);
                continue;
            }

            // Skip pip-only global options (--index-url, --extra-index-url, -e, etc.)
            if (fullLine.startsWith("-")) continue;

            // Strip inline --hash=... or --no-binary=... options from pip-compile/uv lockfiles
            int optIdx = fullLine.indexOf(" --");
            if (optIdx != -1) {
                fullLine = fullLine.substring(0, optIdx).trim();
            }

            if (!fullLine.isEmpty()) {
                out.add(fullLine);
            }
        }
    }
}