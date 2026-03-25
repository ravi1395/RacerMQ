package com.cheetah.racer.tracing;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Phase 4.2 — W3C {@code traceparent} header utilities.
 *
 * <p>Format: {@code version "-" trace-id "-" parent-id "-" flags}
 * <ul>
 *   <li>{@code version}   — always {@code "00"} (current W3C spec)</li>
 *   <li>{@code trace-id}  — 16-byte (32 hex-char) trace identifier</li>
 *   <li>{@code parent-id} — 8-byte (16 hex-char) span identifier</li>
 *   <li>{@code flags}     — {@code "01"} (sampled)</li>
 * </ul>
 *
 * <p>Example: {@code 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01}
 *
 * <p>This class is intentionally lightweight and has no external dependencies —
 * it does not require an OpenTelemetry SDK.  Applications that already use an
 * OTel agent/SDK can extract the {@code traceparent} from the current span and
 * store it on {@link com.cheetah.racer.model.RacerMessage#getTraceparent()}
 * before publishing.
 */
public final class RacerTraceContext {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();
    private static final String VERSION = "00";
    private static final String FLAGS = "01";

    private RacerTraceContext() {}

    /**
     * Generate a new root {@code traceparent} value with a fresh trace-id and span-id.
     *
     * @return W3C traceparent string, e.g.
     *         {@code 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01}
     */
    public static String generate() {
        byte[] traceId  = new byte[16];
        byte[] parentId = new byte[8];
        RANDOM.nextBytes(traceId);
        RANDOM.nextBytes(parentId);
        return VERSION + "-" + HEX.formatHex(traceId) + "-" + HEX.formatHex(parentId) + "-" + FLAGS;
    }

    /**
     * Generate a child {@code traceparent} that inherits the trace-id from
     * {@code parent} but carries a new span-id.
     *
     * <p>If {@code parent} is {@code null} or blank, a new root traceparent is
     * returned instead.
     *
     * @param parent the parent {@code traceparent} value (may be {@code null})
     * @return a new child traceparent with the same trace-id
     */
    public static String child(String parent) {
        if (parent == null || parent.isBlank()) {
            return generate();
        }
        String traceId = extractTraceId(parent);
        if (traceId == null) {
            return generate();
        }
        byte[] spanIdBytes = new byte[8];
        RANDOM.nextBytes(spanIdBytes);
        return VERSION + "-" + traceId + "-" + HEX.formatHex(spanIdBytes) + "-" + FLAGS;
    }

    /**
     * Parse the trace-id portion from a {@code traceparent} value.
     *
     * @param traceparent full traceparent string (may be {@code null})
     * @return 32-character hex trace-id, or {@code null} if parsing fails
     */
    public static String extractTraceId(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) return null;
        String[] parts = traceparent.split("-", -1);
        if (parts.length < 4) return null;
        String traceId = parts[1];
        // Must be exactly 32 hex chars and not all zeros
        if (traceId.length() != 32 || traceId.equals("00000000000000000000000000000000")) return null;
        return traceId;
    }

    /**
     * Parse the parent-id (span-id) portion from a {@code traceparent} value.
     *
     * @param traceparent full traceparent string (may be {@code null})
     * @return 16-character hex parent-id, or {@code null} if parsing fails
     */
    public static String extractParentId(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) return null;
        String[] parts = traceparent.split("-", -1);
        if (parts.length < 4) return null;
        String parentId = parts[2];
        // Must be exactly 16 hex chars and not all zeros (W3C spec)
        if (parentId.length() != 16 || parentId.equals("0000000000000000")) return null;
        return parentId;
    }

    /**
     * Returns {@code true} if the given string is a syntactically valid
     * W3C traceparent header value.
     *
     * @param traceparent value to validate (may be {@code null})
     * @return {@code true} when valid
     */
    public static boolean isValid(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) return false;
        String[] parts = traceparent.split("-", -1);
        if (parts.length != 4) return false;
        return parts[0].length() == 2
                && parts[1].length() == 32
                && parts[2].length() == 16
                && parts[3].length() == 2
                && isAllHex(parts[1])
                && isAllHex(parts[2])
                && !parts[1].equals("00000000000000000000000000000000")
                && !parts[2].equals("0000000000000000");
    }

    private static boolean isAllHex(String s) {
        for (char c : s.toCharArray()) {
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f') && !(c >= 'A' && c <= 'F')) {
                return false;
            }
        }
        return !s.isEmpty();
    }
}
