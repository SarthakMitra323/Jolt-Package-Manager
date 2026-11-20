// File: src/main/java/dev/jolt/resolver/VersionBitSet.java
package dev.jolt.resolver;

import dev.jolt.pep.Pep440Version;
import dev.jolt.pep.VersionSpec;

import java.util.Arrays;
import java.util.List;

/**
 * Represents a subset of candidate versions for a package as a flat 64-bit word array.
 * Index 0 is ALWAYS the newest stable version (highest priority candidate).
 */
public final class VersionBitSet {
    private final long[] words;
    private final int size;

    public VersionBitSet(int numVersions, boolean setAll) {
        this.size = numVersions;
        int numWords = (numVersions + 63) >>> 6;
        this.words = new long[numWords];
        if (setAll && numVersions > 0) {
            Arrays.fill(this.words, -1L);
            int tailBits = numVersions & 63;
            if (tailBits != 0) {
                this.words[numWords - 1] = (1L << tailBits) - 1L;
            }
        }
    }

    private VersionBitSet(long[] words, int size) {
        this.words = words;
        this.size = size;
    }

    public static VersionBitSet fromSpec(List<Pep440Version> sortedCandidatesDesc, VersionSpec spec) {
        int n = sortedCandidatesDesc.size();
        VersionBitSet bs = new VersionBitSet(n, false);
        boolean allowPre = false;

        // First pass: match stable versions
        for (int i = 0; i < n; i++) {
            Pep440Version v = sortedCandidatesDesc.get(i);
            if (!v.isPreRelease() && spec.matches(v)) {
                bs.set(i);
            }
        }

        // PEP 440 fallback: if no stable version matched, allow pre-releases that match
        if (bs.isEmpty()) {
            for (int i = 0; i < n; i++) {
                if (spec.matches(sortedCandidatesDesc.get(i))) {
                    bs.set(i);
                }
            }
        }
        return bs;
    }

    public void set(int bitIndex) {
        words[bitIndex >>> 6] |= (1L << (bitIndex & 63));
    }

    public void clear(int bitIndex) {
        words[bitIndex >>> 6] &= ~(1L << (bitIndex & 63));
    }

    public boolean get(int bitIndex) {
        return (words[bitIndex >>> 6] & (1L << (bitIndex & 63))) != 0;
    }

    public void intersectWith(VersionBitSet other) {
        for (int i = 0; i < words.length; i++) {
            words[i] &= other.words[i];
        }
    }

    /**
     * Returns the lowest bit index set (which corresponds to the NEWEST compatible version),
     * or -1 if no versions remain compatible.
     */
    public int firstSetBit() {
        for (int i = 0; i < words.length; i++) {
            long w = words[i];
            if (w != 0L) {
                return (i << 6) + Long.numberOfTrailingZeros(w);
            }
        }
        return -1;
    }

    public int cardinality() {
        int count = 0;
        for (long w : words) {
            count += Long.bitCount(w);
        }
        return count;
    }

    public boolean isEmpty() {
        for (long w : words) {
            if (w != 0L) return false;
        }
        return true;
    }

    public VersionBitSet copy() {
        return new VersionBitSet(words.clone(), size);
    }
}