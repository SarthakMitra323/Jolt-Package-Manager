// File: src/main/java/dev/jolt/pep/Pep508Requirement.java
package dev.jolt.pep;

import dev.jolt.env.PythonEnvironment;
import dev.jolt.net.PyPIClient;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Recursive-descent parser and evaluator for PEP 508 Dependency Specifications
 * and Environment Markers.
 */
public record Pep508Requirement(
        String name,
        Set<String> extras,
        VersionSpec versionSpec,
        String markerExpression
) {
    public static Pep508Requirement parse(String rawLine) {
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#")) return null;

        int hashIdx = line.indexOf(" #");
        if (hashIdx != -1) line = line.substring(0, hashIdx).trim();

        String marker = null;
        int semiIdx = findTopLevelSemicolon(line);
        if (semiIdx != -1) {
            marker = line.substring(semiIdx + 1).trim();
            line = line.substring(0, semiIdx).trim();
        }

        int pos = 0;
        int len = line.length();
        while (pos < len && isIdentifierChar(line.charAt(pos))) pos++;
        if (pos == 0) throw new IllegalArgumentException("Invalid PEP 508 requirement: " + rawLine);

        String pkgName = PyPIClient.normalizePackageName(line.substring(0, pos));

        Set<String> extras = Collections.emptySet();
        while (pos < len && Character.isWhitespace(line.charAt(pos))) pos++;
        if (pos < len && line.charAt(pos) == '[') {
            int closeBracket = line.indexOf(']', pos);
            if (closeBracket != -1) {
                extras = new HashSet<>(4);
                String[] parts = line.substring(pos + 1, closeBracket).split(",");
                for (String p : parts) {
                    String trimmed = p.trim().toLowerCase(Locale.ROOT);
                    if (!trimmed.isEmpty()) extras.add(trimmed);
                }
                pos = closeBracket + 1;
            }
        }

        String specPart = line.substring(pos).trim();
        if (specPart.startsWith("(") && specPart.endsWith(")")) {
            specPart = specPart.substring(1, specPart.length() - 1).trim();
        }
        VersionSpec vSpec = VersionSpec.parse(specPart);

        return new Pep508Requirement(pkgName, extras, vSpec, marker);
    }

    public boolean isApplicable(PythonEnvironment env, Set<String> activeExtras) {
        if (markerExpression == null || markerExpression.isEmpty()) {
            return true;
        }
        return new MarkerEvaluator(markerExpression, env, activeExtras).evaluate();
    }

    private static int findTopLevelSemicolon(String s) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == ';' && !inSingle && !inDouble) return i;
        }
        return -1;
    }

    private static boolean isIdentifierChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
    }

    private static final class MarkerEvaluator {
        private final String src;
        private final PythonEnvironment env;
        private final Set<String> activeExtras;
        private int pos;

        MarkerEvaluator(String src, PythonEnvironment env, Set<String> activeExtras) {
            this.src = src;
            this.env = env;
            this.activeExtras = activeExtras != null ? activeExtras : Set.of();
            this.pos = 0;
        }

        boolean evaluate() {
            return parseOr();
        }

        private boolean parseOr() {
            boolean left = parseAnd();
            while (true) {
                skipWs();
                if (matchKeyword("or")) {
                    boolean right = parseAnd();
                    left = left || right;
                } else {
                    break;
                }
            }
            return left;
        }

        private boolean parseAnd() {
            boolean left = parseAtom();
            while (true) {
                skipWs();
                if (matchKeyword("and")) {
                    boolean right = parseAtom();
                    left = left && right;
                } else {
                    break;
                }
            }
            return left;
        }

        private boolean parseAtom() {
            skipWs();
            if (pos < src.length() && src.charAt(pos) == '(') {
                pos++;
                boolean val = parseOr();
                skipWs();
                if (pos < src.length() && src.charAt(pos) == ')') pos++;
                return val;
            }

            String lhsKey = readToken();
            skipWs();
            String op = readOperator();
            skipWs();
            String rhsVal = readToken();

            if ("extra".equals(lhsKey)) {
                return evaluateExtra(op, rhsVal);
            }
            if ("extra".equals(rhsVal)) {
                return evaluateExtra(op, lhsKey);
            }

            String lhsResolved = resolveVariable(lhsKey);
            String rhsResolved = resolveVariable(rhsVal);

            if ("python_version".equals(lhsKey) || "python_full_version".equals(lhsKey)
                    || "python_version".equals(rhsVal) || "python_full_version".equals(rhsVal)) {
                Pep440Version vLeft = Pep440Version.parse(lhsResolved);
                Pep440Version vRight = Pep440Version.parse(rhsResolved);
                int cmp = vLeft.compareTo(vRight);
                return switch (op) {
                    case "==", "===" -> cmp == 0;
                    case "!=" -> cmp != 0;
                    case "<" -> cmp < 0;
                    case "<=" -> cmp <= 0;
                    case ">" -> cmp > 0;
                    case ">=" -> cmp >= 0;
                    default -> false;
                };
            }

            return switch (op) {
                case "==", "===" -> lhsResolved.equals(rhsResolved);
                case "!=" -> !lhsResolved.equals(rhsResolved);
                case "in" -> rhsResolved.contains(lhsResolved);
                case "not in" -> !rhsResolved.contains(lhsResolved);
                default -> lhsResolved.compareTo(rhsResolved) == 0;
            };
        }

        private boolean evaluateExtra(String op, String rawExtraToken) {
            String extraName = rawExtraToken.startsWith("\0")
                    ? rawExtraToken.substring(1)
                    : rawExtraToken;
            String normalized = PyPIClient.normalizePackageName(extraName);
            boolean present = activeExtras.contains(normalized)
                    || activeExtras.contains(extraName.toLowerCase(Locale.ROOT));
            return "==".equals(op) || "===".equals(op) ? present : !present;
        }

        private String resolveVariable(String token) {
            if (token.startsWith("\0")) return token.substring(1);

            boolean isMac = env.primaryPlatformTag().startsWith("macosx");
            return switch (token) {
                case "python_version" -> env.major() + "." + env.minor();
                case "python_full_version" -> env.major() + "." + env.minor() + "." + env.patch();
                case "os_name" -> env.isWindows() ? "nt" : "posix";
                case "sys_platform" -> env.isWindows() ? "win32" : (isMac ? "darwin" : "linux");
                case "platform_system" -> env.isWindows() ? "Windows" : (isMac ? "Darwin" : "Linux");
                case "platform_machine" -> env.primaryPlatformTag().endsWith("aarch64") || env.primaryPlatformTag().endsWith("arm64")
                        ? (isMac ? "arm64" : "aarch64")
                        : (env.isWindows() ? "AMD64" : "x86_64");
                case "implementation_name", "platform_python_implementation" -> "cpython";
                default -> token;
            };
        }

        private String readToken() {
            skipWs();
            if (pos >= src.length()) return "";
            char q = src.charAt(pos);
            if (q == '\'' || q == '"') {
                pos++;
                int start = pos;
                while (pos < src.length() && src.charAt(pos) != q) pos++;
                String literal = src.substring(start, pos);
                if (pos < src.length()) pos++;
                return "\0" + literal;
            }
            int start = pos;
            while (pos < src.length() && isIdentifierChar(src.charAt(pos))) pos++;
            return src.substring(start, pos);
        }

        private String readOperator() {
            skipWs();
            if (matchKeyword("not")) {
                skipWs();
                if (matchKeyword("in")) return "not in";
            }
            if (matchKeyword("in")) return "in";

            int start = pos;
            while (pos < src.length() && "=!<~>".indexOf(src.charAt(pos)) != -1) pos++;
            return src.substring(start, pos);
        }

        private boolean matchKeyword(String kw) {
            int len = kw.length();
            if (pos + len <= src.length() && src.regionMatches(true, pos, kw, 0, len)) {
                int after = pos + len;
                if (after == src.length() || !isIdentifierChar(src.charAt(after))) {
                    pos = after;
                    return true;
                }
            }
            return false;
        }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }
    }
}