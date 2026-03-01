package com.cheetah.racer.common.schema;

import java.util.List;

/**
 * Thrown by {@link RacerSchemaRegistry} when a payload violates its registered JSON Schema
 * and {@code racer.schema.fail-on-violation=true} (the default).
 *
 * <p>Catching this exception in a client Pub/Sub consumer allows per-message rejection without
 * routing the message to the DLQ. In the publish path, it prevents the invalid payload from
 * reaching Redis at all.
 */
public class SchemaValidationException extends RuntimeException {

    private final String channel;
    private final List<SchemaViolation> violations;

    public SchemaValidationException(String channel, List<SchemaViolation> violations) {
        super(buildMessage(channel, violations));
        this.channel    = channel;
        this.violations = List.copyOf(violations);
    }

    /** Redis channel name for which validation failed. */
    public String getChannel() {
        return channel;
    }

    /** Ordered list of constraint violations found in the payload. */
    public List<SchemaViolation> getViolations() {
        return violations;
    }

    private static String buildMessage(String channel, List<SchemaViolation> violations) {
        StringBuilder sb = new StringBuilder();
        sb.append("Schema validation failed for channel '").append(channel).append("': ");
        violations.forEach(v -> sb.append("[").append(v.path()).append("] ").append(v.message()).append("; "));
        return sb.toString().stripTrailing().replaceAll(";$", "");
    }
}
