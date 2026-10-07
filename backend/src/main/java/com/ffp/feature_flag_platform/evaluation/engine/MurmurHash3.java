package com.ffp.feature_flag_platform.evaluation.engine;

/**
 * MurmurHash3 x86 32-bit. Fast, well distributed and – crucially for bucketing – stable across
 * JVMs, languages and releases, unlike {@link String#hashCode()} based schemes which are
 * poorly distributed for similar keys ("user-1", "user-2", ...).
 */
public final class MurmurHash3 {

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;

    private MurmurHash3() {
    }

    public static int hash32(byte[] data, int seed) {
        int h1 = seed;
        int length = data.length;
        int roundedEnd = length & 0xfffffffc;

        for (int i = 0; i < roundedEnd; i += 4) {
            int k1 = (data[i] & 0xff)
                    | ((data[i + 1] & 0xff) << 8)
                    | ((data[i + 2] & 0xff) << 16)
                    | (data[i + 3] << 24);
            h1 ^= mixK1(k1);
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        switch (length & 0x03) {
            case 3:
                k1 = (data[roundedEnd + 2] & 0xff) << 16;
                // fall through
            case 2:
                k1 |= (data[roundedEnd + 1] & 0xff) << 8;
                // fall through
            case 1:
                k1 |= data[roundedEnd] & 0xff;
                h1 ^= mixK1(k1);
                break;
            default:
                break;
        }

        h1 ^= length;
        return fmix32(h1);
    }

    private static int mixK1(int k1) {
        k1 *= C1;
        k1 = Integer.rotateLeft(k1, 15);
        return k1 * C2;
    }

    private static int fmix32(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
