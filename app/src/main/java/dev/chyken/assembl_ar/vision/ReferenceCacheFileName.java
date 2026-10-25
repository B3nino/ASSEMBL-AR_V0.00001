package dev.chyken.assembl_ar.vision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Leave room for geometry sidecars as well as Android's 255-byte filename limit. */
final class ReferenceCacheFileName {
    private ReferenceCacheFileName() { }

    static String bounded(String legacyName) {
        // Preserve usable existing caches; only oversized names need new storage keys.
        if (legacyName.getBytes(StandardCharsets.UTF_8).length <= 180) return legacyName;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(legacyName.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder("reference-");
            for (byte b : digest) {
                name.append("0123456789abcdef".charAt((b & 255) >>> 4));
                name.append("0123456789abcdef".charAt(b & 15));
            }
            return name.append(".asor").toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
