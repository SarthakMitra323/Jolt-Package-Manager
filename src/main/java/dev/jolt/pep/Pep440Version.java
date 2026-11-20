// File: src/main/java/dev/jolt/pep/Pep440Version.java
package dev.jolt.pep;

import java.util.Arrays;

/**
 * Zero-regex, allocation-light PEP 440 Version representation and comparator.
 * Ordering rules per PEP 440:
 *   .devN < aN (alpha) < bN (beta) < rcN (candidate) < <final release> < .postN
 */
public final class Pep440Version implements Comparable<Pep440Version> {
    // Phase rank constants ordered strictly from lowest to highest precedence
    public static final int PHASE_DEV   = 0;
    public static final int PHASE_ALPHA = 1;
    public static final int PHASE_BETA  = 2;
    public static final int PHASE_RC    = 3;
    public static final int PHASE_FINAL = 4;

    private final String raw;
    private final int epoch;
    private final int[] release;      // e.g. [2, 32, 3]
    private final int prePhase;       // PHASE_ALPHA..PHASE_FINAL
    private final int preNum;
    private final int postNum;        // -1 if not a post release
    private final int devNum;         // -1 if not a dev release
    private final String local;       // +cu118, null if absent

    private Pep440Version(
            String raw, int epoch, int[] release,
            int prePhase, int preNum, int postNum, int devNum, String local) {
        this.raw = raw;
        this.epoch = epoch;
        this.release = release;
        this.prePhase = prePhase;
        this.preNum = preNum;
        this.postNum = postNum;
        this.devNum = devNum;
        this.local = local;
    }

    public static Pep440Version parse(String input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("Empty PEP 440 version");
        }
        String s = input.trim();
        int start = 0;
        int end = s.length();

        if (start < end && (s.charAt(start) == 'v' || s.charAt(start) == 'V')) {
            start++;
        }

        // 1. Extract local version (+...)
        String localPart = null;
        int plusIdx = s.indexOf('+', start);
        if (plusIdx != -1) {
            localPart = s.substring(plusIdx + 1).toLowerCase();
            end = plusIdx;
        }

        // 2. Extract epoch (N!...)
        int epoch = 0;
        int bangIdx = s.indexOf('!', start);
        if (bangIdx != -1 && bangIdx < end) {
            epoch = parsePositiveInt(s, start, bangIdx);
            start = bangIdx + 1;
        }

        // 3. Parse numeric release segments (X.Y.Z...)
        int[] tempRel = new int[8];
        int relCount = 0;
        int pos = start;
        while (pos < end) {
            int segStart = pos;
            while (pos < end && isDigit(s.charAt(pos))) pos++;
            if (pos == segStart) break;

            if (relCount == tempRel.length) {
                tempRel = Arrays.copyOf(tempRel, tempRel.length * 2);
            }
            tempRel[relCount++] = parsePositiveInt(s, segStart, pos);

            if (pos < end && s.charAt(pos) == '.') {
                // Peek ahead: only consume '.' if followed by another digit
                if (pos + 1 < end && isDigit(s.charAt(pos + 1))) {
                    pos++;
                } else {
                    break;
                }
            } else {
                break;
            }
        }

        if (relCount == 0) {
            // Fallback for legacy versions; treat as 0
            tempRel[relCount++] = 0;
        }
        int[] release = Arrays.copyOf(tempRel, relCount);

        // 4. Parse optional pre-release, post-release, and dev-release suffixes
        int prePhase = PHASE_FINAL;
        int preNum = 0;
        int postNum = -1;
        int devNum = -1;

        while (pos < end) {
            char sep = s.charAt(pos);
            if (sep == '.' || sep == '-' || sep == '_') {
                pos++;
            }
            if (pos >= end) break;

            // Implicit post number e.g. "1.0-1"
            if (isDigit(s.charAt(pos))) {
                int numStart = pos;
                while (pos < end && isDigit(s.charAt(pos))) pos++;
                postNum = parsePositiveInt(s, numStart, pos);
                continue;
            }

            int tagStart = pos;
            while (pos < end && isAlpha(s.charAt(pos))) pos++;
            String tag = s.substring(tagStart, pos).toLowerCase();

            if (pos < end && (s.charAt(pos) == '.' || s.charAt(pos) == '-' || s.charAt(pos) == '_')) {
                if (pos + 1 < end && isDigit(s.charAt(pos + 1))) pos++;
            }

            int numStart = pos;
            while (pos < end && isDigit(s.charAt(pos))) pos++;
            int num = (pos > numStart) ? parsePositiveInt(s, numStart, pos) : 0;

            switch (tag) {
                case "a", "alpha" -> { prePhase = PHASE_ALPHA; preNum = num; }
                case "b", "beta" -> { prePhase = PHASE_BETA; preNum = num; }
                case "c", "rc", "pre", "preview" -> { prePhase = PHASE_RC; preNum = num; }
                case "post", "rev", "r" -> postNum = num;
                case "dev" -> devNum = num;
                default -> { /* Ignore unknown legacy suffix tokens gracefully */ }
            }
        }

        return new Pep440Version(input, epoch, release, prePhase, preNum, postNum, devNum, localPart);
    }

    public boolean isPreRelease() {
        return devNum >= 0 || prePhase < PHASE_FINAL;
    }

    public int epoch() { return epoch; }
    public int[] release() { return release; }
    public String raw() { return raw; }

    public int releaseSegment(int index) {
        return index < release.length ? release[index] : 0;
    }

    @Override
    public int compareTo(Pep440Version o) {
        if (this.epoch != o.epoch) {
            return Integer.compare(this.epoch, o.epoch);
        }

        // Compare numeric release segments ignoring trailing zeros (1.2 == 1.2.0)
        int maxLen = Math.max(this.release.length, o.release.length);
        for (int i = 0; i < maxLen; i++) {
            int a = i < this.release.length ? this.release[i] : 0;
            int b = i < o.release.length ? o.release[i] : 0;
            if (a != b) return Integer.compare(a, b);
        }

        // Pure dev release (e.g. 1.0.dev1) comes BEFORE any pre-release (1.0a1)
        int thisEffectivePhase = (this.prePhase == PHASE_FINAL && this.postNum == -1 && this.devNum >= 0)
                ? PHASE_DEV : this.prePhase;
        int otherEffectivePhase = (o.prePhase == PHASE_FINAL && o.postNum == -1 && o.devNum >= 0)
                ? PHASE_DEV : o.prePhase;

        if (thisEffectivePhase != otherEffectivePhase) {
            return Integer.compare(thisEffectivePhase, otherEffectivePhase);
        }

        if (this.prePhase != PHASE_FINAL && this.preNum != o.preNum) {
            return Integer.compare(this.preNum, o.preNum);
        }

        // Compare post-release (no post = -1 < post0 = 0)
        if (this.postNum != o.postNum) {
            // Special case: 1.0a1.dev1 < 1.0a1
            if (this.postNum == -1 && o.postNum == -1) {
                // handled below
            } else {
                return Integer.compare(this.postNum, o.postNum);
            }
        }

        // Compare dev-release on same pre/post level: having a dev tag is LOWER than not having one
        if (this.devNum != o.devNum) {
            if (this.devNum == -1) return 1;
            if (o.devNum == -1) return -1;
            return Integer.compare(this.devNum, o.devNum);
        }

        // Local version comparison (+abc)
        if (this.local == null && o.local == null) return 0;
        if (this.local == null) return -1;
        if (o.local == null) return 1;
        return this.local.compareTo(o.local);
    }

    @Override
    public boolean equals(Object obj) {
        return (obj instanceof Pep440Version other) && this.compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        int h = Integer.hashCode(epoch) * 31 + prePhase;
        for (int r : release) {
            if (r != 0) h = h * 31 + r;
        }
        return h;
    }

    @Override
    public String toString() {
        return raw;
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private static boolean isAlpha(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }

    private static int parsePositiveInt(String s, int start, int end) {
        int val = 0;
        for (int i = start; i < end; i++) {
            val = val * 10 + (s.charAt(i) - '0');
        }
        return val;
    }
}