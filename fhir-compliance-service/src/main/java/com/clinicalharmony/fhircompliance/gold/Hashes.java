package com.clinicalharmony.fhircompliance.gold;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 hex digests for lineage {@code input_hash}/{@code output_hash} — same algorithm and encoding as bronze.raw_messages.payload_checksum. */
public final class Hashes {

    private Hashes() {
    }

    public static String sha256Hex(String text) {
        if (text == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
