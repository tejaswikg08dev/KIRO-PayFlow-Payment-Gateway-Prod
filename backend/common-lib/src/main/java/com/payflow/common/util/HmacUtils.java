package com.payflow.common.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * HMAC-SHA256 computation utility.
 * Used for webhook signature verification and API key hashing.
 */
public final class HmacUtils {

    private static final String ALGORITHM = "HmacSHA256";

    private HmacUtils() {
    }

    /**
     * Computes HMAC-SHA256 of the given payload using the secret.
     * Returns the result as a lowercase hex string.
     */
    public static String computeHmacSha256(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }

    /**
     * Verifies an HMAC signature using constant-time comparison
     * to prevent timing attacks.
     */
    public static boolean verifySignature(String payload, String secret, String expectedSignature) {
        String computed = computeHmacSha256(payload, secret);
        return constantTimeEquals(computed, expectedSignature);
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
