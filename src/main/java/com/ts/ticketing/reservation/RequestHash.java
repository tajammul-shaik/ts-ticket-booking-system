package com.ts.ticketing.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** request_hash = SHA-256 hex of show_id + ":" + sorted seat labels joined by "," (BRD section 10). */
final class RequestHash {

    private RequestHash() {
    }

    static String of(String showId, List<String> sortedSeats) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((showId + ":" + String.join(",", sortedSeats)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
