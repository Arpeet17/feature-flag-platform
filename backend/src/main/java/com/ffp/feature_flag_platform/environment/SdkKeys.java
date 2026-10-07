package com.ffp.feature_flag_platform.environment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * SDK keys are bearer secrets embedded in server applications. Only their SHA-256 hash is
 * persisted (a fast hash is fine here: keys carry 160 bits of entropy, so brute force is
 * infeasible and no salt/KDF is needed), and a short hint is kept for display.
 */
public final class SdkKeys {

    public static final String PREFIX = "sdk-";
    private static final SecureRandom RANDOM = new SecureRandom();

    private SdkKeys() {
    }

    public static String generate() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return PREFIX + HexFormat.of().formatHex(bytes);
    }

    public static String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String hint(String rawKey) {
        return PREFIX + "…" + rawKey.substring(Math.max(PREFIX.length(), rawKey.length() - 4));
    }
}
