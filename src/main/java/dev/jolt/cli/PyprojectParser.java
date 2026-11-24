package dev.jolt.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Zero-dependency TOML scanner for PEP 621 [project] dependencies and
 * [project.optional-dependencies] groups in pyproject.toml.
 */
public final class PyprojectParser {

    private enum Section {
        OTHER, PROJECT, OPTIONAL_DEPENDENCIES
    }

    public record ParsedPyproject(
            List<String> dependencies,
            Map<String, List<String>> optionalDependencies) {}

    private PyprojectParser() {}

    public static List<String> parseDependencies(Path pyprojectFile) throws IOException {
        return parseDependencies(pyprojectFile, List.of(), false);
    }

    public static List<String> parseDependencies(
            Path pyprojectFile,
            List<String> requestedExtras,
            boolean allExtras) throws IOException {

        ParsedPyproject parsed = parse(pyprojectFile);
        Set<String> combined = new LinkedHashSet<>(parsed.dependencies());

        if (allExtras) {
            for (List<String> groupDeps : parsed.optionalDependencies().values()) {
                combined.addAll(groupDeps);
            }
        } else if (requestedExtras != null && !requestedExtras.isEmpty()) {
            for (String rawExtra : requestedExtras) {
                String norm = normalizeExtraName(rawExtra);
                List<String> groupDeps = parsed.optionalDependencies().get(norm);
                if (groupDeps == null) {
                    String available = parsed.optionalDependencies().isEmpty()
                            ? "none"
                            : String.join(", ", parsed.optionalDependencies().keySet());
                    throw new IllegalArgumentException(
                            "Optional dependency group '" + rawExtra + "' not found in "
                                    + pyprojectFile.getFileName() + " (available: " + available + ")");
                }
                combined.addAll(groupDeps);
            }
        }

        return new ArrayList<>(combined);
    }

    public static ParsedPyproject parse(Path pyprojectFile) throws IOException {
        if (!Files.isRegularFile(pyprojectFile)) {
            throw new IOException("pyproject.toml not found: " + pyprojectFile);
        }

        String src = Files.readString(pyprojectFile, StandardCharsets.UTF_8);
        if (!src.isEmpty() && src.charAt(0) == '\uFEFF') {
            src = src.substring(1); // Strip UTF-8 BOM
        }

        List<String> baseDeps = List.of();
        Map<String, List<String>> optionalDeps = new LinkedHashMap<>();

        int i = 0;
        int len = src.length();
        Section section = Section.OTHER;
        boolean atLineStart = true;
        int bracketDepth = 0;

        while (i < len) {
            char c = src.charAt(i);

            if (c == '\n') {
                i++;
                atLineStart = true;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\r') {
                i++;
                continue;
            }
            if (c == '#') {
                i = skipToEndOfLine(src, i);
                continue;
            }

            // Section header at top level: [project], [project.optional-dependencies], etc.
            if (atLineStart && bracketDepth == 0 && c == '[') {
                if (i + 1 < len && src.charAt(i + 1) == '[') {
                    section = Section.OTHER;
                    i = skipToEndOfLine(src, i);
                    atLineStart = false;
                    continue;
                }
                int close = src.indexOf(']', i + 1);
                int nl = src.indexOf('\n', i + 1);
                if (close != -1 && (nl == -1 || close < nl)) {
                    String header = src.substring(i + 1, close).trim();
                    section = switch (header) {
                        case "project" -> Section.PROJECT;
                        case "project.optional-dependencies" -> Section.OPTIONAL_DEPENDENCIES;
                        default -> Section.OTHER;
                    };
                    i = close + 1;
                    atLineStart = false;
                    continue;
                }
            }

            // Key assignment inside [project] or [project.optional-dependencies]
            if (atLineStart && bracketDepth == 0
                    && (section == Section.PROJECT || section == Section.OPTIONAL_DEPENDENCIES)
                    && (isKeyChar(c) || c == '"' || c == '\'')) {

                String key;
                if (c == '"' || c == '\'') {
                    StringBuilder keyBuf = new StringBuilder(32);
                    i = readQuotedString(src, i, keyBuf);
                    key = keyBuf.toString().trim();
                } else {
                    int keyStart = i;
                    while (i < len && isKeyChar(src.charAt(i))) {
                        i++;
                    }
                    key = src.substring(keyStart, i);
                }

                while (i < len && (src.charAt(i) == ' ' || src.charAt(i) == '\t')) {
                    i++;
                }

                if (i < len && src.charAt(i) == '=') {
                    i++; // consume '='
                    i = skipWhitespaceAndComments(src, i);

                    if (section == Section.PROJECT && "dependencies".equals(key)) {
                        if (i < len && src.charAt(i) == '[') {
                            List<String> parsedArr = new ArrayList<>();
                            i = parseStringArray(src, i, parsedArr);
                            baseDeps = parsedArr;
                        }
                        atLineStart = false;
                        continue;
                    }

                    if (section == Section.OPTIONAL_DEPENDENCIES) {
                        if (i < len && src.charAt(i) == '[') {
                            List<String> groupArr = new ArrayList<>();
                            i = parseStringArray(src, i, groupArr);
                            optionalDeps.put(normalizeExtraName(key), Collections.unmodifiableList(groupArr));
                        }
                        atLineStart = false;
                        continue;
                    }
                }

                atLineStart = false;
                continue;
            }

            // Skip strings safely so brackets or '#' inside quotes don't corrupt state
            if (c == '"' || c == '\'') {
                i = skipQuotedString(src, i);
                atLineStart = false;
                continue;
            }

            if (c == '[') {
                bracketDepth++;
            } else if (c == ']' && bracketDepth > 0) {
                bracketDepth--;
            }

            i++;
            atLineStart = false;
        }

        return new ParsedPyproject(
                Collections.unmodifiableList(baseDeps),
                Collections.unmodifiableMap(optionalDeps));
    }

    /**
     * PEP 685 extra name normalization: lowercase and collapse runs of '-', '_', '.' into '-'.
     */
    public static String normalizeExtraName(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        boolean lastDash = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '-' || c == '_' || c == '.') {
                if (!lastDash && sb.length() > 0) {
                    sb.append('-');
                    lastDash = true;
                }
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) (c + 32));
                lastDash = false;
            } else if (!Character.isWhitespace(c)) {
                sb.append(c);
                lastDash = false;
            }
        }
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == '-') {
            sb.setLength(len - 1);
        }
        return sb.toString();
    }

    private static int parseStringArray(String src, int openBracketIdx, List<String> out) {
        int i = openBracketIdx + 1;
        int len = src.length();

        while (i < len) {
            i = skipWhitespaceAndComments(src, i);
            if (i >= len) break;

            char c = src.charAt(i);
            if (c == ']') {
                return i + 1;
            }
            if (c == ',') {
                i++;
                continue;
            }

            if (c == '"' || c == '\'') {
                StringBuilder sb = new StringBuilder(64);
                i = readQuotedString(src, i, sb);
                String dep = sb.toString().trim();
                if (!dep.isEmpty()) {
                    out.add(dep);
                }
                continue;
            }

            i++;
        }

        return i;
    }

    private static int readQuotedString(String src, int start, StringBuilder out) {
        int len = src.length();
        char quote = src.charAt(start);
        boolean multiline = (start + 2 < len
                && src.charAt(start + 1) == quote
                && src.charAt(start + 2) == quote);

        int i = start + (multiline ? 3 : 1);
        if (multiline && i < len) {
            if (src.charAt(i) == '\r' && i + 1 < len && src.charAt(i + 1) == '\n') i += 2;
            else if (src.charAt(i) == '\n') i++;
        }

        while (i < len) {
            char c = src.charAt(i);
            if (multiline) {
                if (c == quote && i + 2 < len && src.charAt(i + 1) == quote && src.charAt(i + 2) == quote) {
                    return i + 3;
                }
            } else if (c == quote) {
                return i + 1;
            }

            if (quote == '"' && c == '\\' && i + 1 < len) {
                char next = src.charAt(i + 1);
                switch (next) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    default -> out.append(next);
                }
                i += 2;
                continue;
            }

            out.append(c);
            i++;
        }
        return len;
    }

    private static int skipQuotedString(String src, int start) {
        return readQuotedString(src, start, new StringBuilder(32));
    }

    private static int skipWhitespaceAndComments(String src, int i) {
        int len = src.length();
        while (i < len) {
            char c = src.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                i++;
            } else if (c == '#') {
                i = skipToEndOfLine(src, i);
            } else {
                break;
            }
        }
        return i;
    }

    private static int skipToEndOfLine(String src, int i) {
        int nl = src.indexOf('\n', i);
        return (nl == -1) ? src.length() : nl;
    }

    private static boolean isKeyChar(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_' || c == '-';
    }
}