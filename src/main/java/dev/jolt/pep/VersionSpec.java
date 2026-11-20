// File: src/main/java/dev/jolt/pep/VersionSpec.java
package dev.jolt.pep;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates compound PEP 440 version specifiers such as ">=1.20.0, !=1.24.*, <2.0"
 * and pre-computes 64-bit word bitmasks over a package's sorted candidate version list.
 */
public final class VersionSpec {
    public static final VersionSpec ANY = new VersionSpec(List.of());

    public enum Op {
        EQ, NE, LT, LE, GT, GE, COMPAT, ARB_EQ, PREFIX_EQ, PREFIX_NE
    }

    public record Clause(Op op, Pep440Version target, int prefixLength) {
        public boolean matches(Pep440Version v) {
            return switch (op) {
                case EQ -> v.compareTo(target) == 0;
                case NE -> v.compareTo(target) != 0;
                case LT -> v.compareTo(target) < 0 && !isSameReleasePre(v, target);
                case LE -> v.compareTo(target) <= 0;
                case GT -> v.compareTo(target) > 0 && !isSameReleasePost(v, target);
                case GE -> v.compareTo(target) >= 0;
                case ARB_EQ -> v.raw().trim().equalsIgnoreCase(target.raw().trim());
                case PREFIX_EQ -> matchesPrefix(v, target, prefixLength);
                case PREFIX_NE -> !matchesPrefix(v, target, prefixLength);
                case COMPAT -> {
                    if (v.compareTo(target) < 0) yield false;
                    // ~= 1.4.2 means >= 1.4.2, == 1.4.*
                    int upperPrefix = Math.max(1, target.release().length - 1);
                    yield matchesPrefix(v, target, upperPrefix);
                }
            };
        }

        private static boolean matchesPrefix(Pep440Version v, Pep440Version prefix, int len) {
            if (v.epoch() != prefix.epoch()) return false;
            for (int i = 0; i < len; i++) {
                if (v.releaseSegment(i) != prefix.releaseSegment(i)) return false;
            }
            return true;
        }

        // Per PEP 440: "<1.0" must also exclude "1.0a1" unless target itself is a pre-release
        private static boolean isSameReleasePre(Pep440Version v, Pep440Version target) {
            if (target.isPreRelease() || !v.isPreRelease()) return false;
            return matchesPrefix(v, target, target.release().length);
        }

        private static boolean isSameReleasePost(Pep440Version v, Pep440Version target) {
            return false;
        }
    }

    private final List<Clause> clauses;

    private VersionSpec(List<Clause> clauses) {
        this.clauses = clauses;
    }

    public static VersionSpec parse(String specStr) {
        if (specStr == null || specStr.isBlank() || "*".equals(specStr.trim())) {
            return ANY;
        }

        List<Clause> parsed = new ArrayList<>(4);
        int start = 0;
        int len = specStr.length();

        while (start < len) {
            int comma = specStr.indexOf(',', start);
            int end = (comma == -1) ? len : comma;
            Clause c = parseSingleClause(specStr.substring(start, end).trim());
            if (c != null) parsed.add(c);
            start = end + 1;
        }
        return parsed.isEmpty() ? ANY : new VersionSpec(parsed);
    }

    private static Clause parseSingleClause(String token) {
        if (token.isEmpty()) return null;
        // Strip parentheses if wrapped e.g. "(>=1.2)"
        if (token.startsWith("(") && token.endsWith(")")) {
            token = token.substring(1, token.length() - 1).trim();
        }
        if (token.isEmpty() || "*".equals(token)) return null;

        Op op;
        int verStart;
        if (token.startsWith("===")) { op = Op.ARB_EQ; verStart = 3; }
        else if (token.startsWith("~=")) { op = Op.COMPAT; verStart = 2; }
        else if (token.startsWith("==")) { op = Op.EQ; verStart = 2; }
        else if (token.startsWith("!=")) { op = Op.NE; verStart = 2; }
        else if (token.startsWith("<=")) { op = Op.LE; verStart = 2; }
        else if (token.startsWith(">=")) { op = Op.GE; verStart = 2; }
        else if (token.startsWith("<")) { op = Op.LT; verStart = 1; }
        else if (token.startsWith(">")) { op = Op.GT; verStart = 1; }
        else { op = Op.EQ; verStart = 0; }

        String verStr = token.substring(verStart).trim();
        if (verStr.endsWith(".*")) {
            String prefixStr = verStr.substring(0, verStr.length() - 2);
            Pep440Version target = Pep440Version.parse(prefixStr);
            Op prefixOp = (op == Op.NE) ? Op.PREFIX_NE : Op.PREFIX_EQ;
            return new Clause(prefixOp, target, target.release().length);
        }

        Pep440Version target = Pep440Version.parse(verStr);
        return new Clause(op, target, target.release().length);
    }

    public boolean matches(Pep440Version version) {
        for (int i = 0, n = clauses.size(); i < n; i++) {
            if (!clauses.get(i).matches(version)) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return clauses.isEmpty() ? "*" : clauses.toString();
    }
}