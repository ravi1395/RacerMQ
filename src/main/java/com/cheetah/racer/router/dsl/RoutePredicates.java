package com.cheetah.racer.router.dsl;

import com.cheetah.racer.model.RacerMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.regex.Pattern;

/**
 * Factory methods for the most common {@link RoutePredicate} patterns.
 *
 * <p>Import statically for the most readable router definitions:
 * <pre>{@code
 * import static com.cheetah.racer.router.dsl.RoutePredicates.*;
 * import static com.cheetah.racer.router.dsl.RouteHandlers.*;
 *
 * RacerFunctionalRouter.builder()
 *     .route(fieldEquals("type", "EMAIL"),       forward("email"))
 *     .route(fieldMatches("type", "ORDER.*"),    forward("orders"))
 *     .route(senderEquals("payment-svc"),         forward("payments"))
 *     .route(fieldEquals("type", "BROADCAST")
 *                .and(senderEquals("checkout")),  multicastAndProcess("email", "sms"))
 *     .defaultRoute(drop())
 *     .build();
 * }</pre>
 */
public final class RoutePredicates {

    // Thread-safe; used only for readTree (no serialization config needed).
    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private RoutePredicates() {}

    // ── Payload-field predicates ──────────────────────────────────────────

    /**
     * Matches when the named top-level JSON field in the message payload equals {@code value}
     * (exact, case-sensitive comparison).
     */
    public static RoutePredicate fieldEquals(String field, String value) {
        return msg -> value.equals(extractField(msg, field));
    }

    /**
     * Matches when the named top-level JSON field in the message payload matches
     * {@code regex}. The pattern is pre-compiled at predicate creation time.
     */
    public static RoutePredicate fieldMatches(String field, String regex) {
        Pattern pattern = Pattern.compile(regex);
        return msg -> {
            String candidate = extractField(msg, field);
            return candidate != null && pattern.matcher(candidate).matches();
        };
    }

    // ── Sender predicates ─────────────────────────────────────────────────

    /**
     * Matches when {@link RacerMessage#getSender()} equals {@code sender} (exact, case-sensitive).
     */
    public static RoutePredicate senderEquals(String sender) {
        return msg -> sender.equals(msg.getSender());
    }

    /**
     * Matches when {@link RacerMessage#getSender()} matches the given regex.
     */
    public static RoutePredicate senderMatches(String regex) {
        Pattern pattern = Pattern.compile(regex);
        return msg -> msg.getSender() != null && pattern.matcher(msg.getSender()).matches();
    }

    // ── ID predicates ──────────────────────────────────────────────────────

    /**
     * Matches when {@link RacerMessage#getId()} equals {@code id} (exact, case-sensitive).
     */
    public static RoutePredicate idEquals(String id) {
        return msg -> id.equals(msg.getId());
    }

    /**
     * Matches when {@link RacerMessage#getId()} matches the given regex.
     */
    public static RoutePredicate idMatches(String regex) {
        Pattern pattern = Pattern.compile(regex);
        return msg -> msg.getId() != null && pattern.matcher(msg.getId()).matches();
    }

    // ── Catch-all ─────────────────────────────────────────────────────────

    /**
     * Matches every message — intended as a catch-all / default route.
     * Equivalent to calling {@link RacerFunctionalRouter.Builder#defaultRoute(RouteHandler)}.
     */
    public static RoutePredicate any() {
        return msg -> true;
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private static String extractField(RacerMessage msg, String field) {
        try {
            JsonNode node = MAPPER.readTree(msg.getPayload());
            JsonNode fieldNode = node.get(field);
            return fieldNode != null ? fieldNode.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
