package dev.jolt.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses pip/uv requirements.txt files with full --hash=sha256:... lockfile support.
 * - Handles blank lines, inline '#' comments, line continuations ('\'), and nested '-r' includes
 * - Preserves and validates --hash=sha256:<hex> entries; activates strict hash-checking mode
 *   whenever any requirement supplies a hash.
 */
public final class RequirementsParser {

    public record ParsedRequirements(
            List<String> specs,
            Map<String, Set<String>> allowedHashesByPkg,
            boolean hashCheckingMode) {}

    private RequirementsParser() {}

    public static List<String> parseFile(Path reqFile) throws IOException {
        return parseFileWithHashes(reqFile).specs();
    }

    public static ParsedRequirements parseFileWithHashes(Path reqFile) throws IOException {
        List<String> specs = new ArrayList<>(32);
        Map<String, Set<String>> hashesByPkg = new LinkedHashMap<>();
        boolean[] hashMode = { false };
        Set<Path> visited = new HashSet<>();
        parseRecursive(reqFile.toAbsolutePath().normalize(), specs, hashesByPkg, hashMode, visited);
        return new ParsedRequirements(
                Collections.unmodifiableList(specs),
                Collections.unmodifiableMap(hashesByPkg),
                hashMode[0]);
    }

    private static void parseRecursive(
            Path file,
            List<String> outSpecs,
            Map<String, Set<String>> hashesByPkg,
            boolean[] hashMode,
            Set<Path> visited) throws IOException {

        if (!visited.add(file)) return;
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

            if (trimmed.endsWith("\\")) {
                logicalLine.append(trimmed, 0, trimmed.length() - 1).append(' ');
                continue;
            }

            logicalLine.append(trimmed);
            String fullLine = logicalLine.toString().trim();
            logicalLine.setLength(0);

            if (fullLine.isEmpty() || fullLine.startsWith("#")) continue;

            int commentIdx = fullLine.indexOf(" #");
            if (commentIdx != -1) {
                fullLine = fullLine.substring(0, commentIdx).trim();
            }

            if (fullLine.startsWith("-r ") || fullLine.startsWith("--requirement ")) {
                String nestedPath = fullLine.substring(fullLine.indexOf(' ') + 1).trim();
                Path resolvedNested = file.getParent().resolve(nestedPath).normalize();
                parseRecursive(resolvedNested, outSpecs, hashesByPkg, hashMode, visited);
                continue;
            }

            if (fullLine.startsWith("-")) continue;

            String specPart = fullLine;
            String optionsPart = "";
            int optIdx = fullLine.indexOf(" --");
            if (optIdx != -1) {
                specPart = fullLine.substring(0, optIdx).trim();
                optionsPart = fullLine.substring(optIdx + 1).trim();
            }

            if (specPart.isEmpty()) continue;
            outSpecs.add(specPart);

            if (!optionsPart.isEmpty()) {
                Set<String> lineHashes = extractSha256Hashes(optionsPart);
                if (!lineHashes.isEmpty()) {
                    hashMode[0] = true;
                    var parsed = dev.jolt.pep.Pep508Requirement.parse(specPart);
                    if (parsed == null) {
                        throw new IllegalArgumentException("Invalid requirement with --hash: " + specPart);
                    }
                    String normName = normalizePkgName(parsed.name());
                    hashesByPkg.computeIfAbsent(normName, k -> new LinkedHashSet<>()).addAll(lineHashes);
                }
            }
        }
    }

    private static Set<String> extractSha256Hashes(String optionsPart) {
        Set<String> hashes = new LinkedHashSet<>();
        String[] tokens = optionsPart.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String tok = tokens[i];
            String value = null;
            if (tok.startsWith("--hash=")) {
                value = tok.substring("--hash=".length());
            } else if ("--hash".equals(tok) && i + 1 < tokens.length) {
                value = tokens[++i];
            }
            if (value == null) continue;

            int colon = value.indexOf(':');
            if (colon == -1) {
                throw new IllegalArgumentException("Malformed --hash value (expected algorithm:hex): " + value);
            }
            String algo = value.substring(0, colon).toLowerCase(Locale.ROOT);
            String digest = value.substring(colon + 1).toLowerCase(Locale.ROOT);
            if (!"sha256".equals(algo)) {
                throw new IllegalArgumentException("Unsupported hash algorithm '" + algo + "' (Jolt requires sha256)");
            }
            if (digest.length() != 64 || !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid sha256 digest format: " + digest);
            }
            hashes.add(digest);
        }
        return hashes;
    }

    public static String normalizePkgName(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }
}