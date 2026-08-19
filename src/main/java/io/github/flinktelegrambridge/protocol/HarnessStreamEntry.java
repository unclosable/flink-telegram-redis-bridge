package io.github.flinktelegrambridge.protocol;

import java.util.Map;

/**
 * A single consumed Redis Stream entry: its stream id plus the decoded JSON body.
 */
public record HarnessStreamEntry(String id, String body) {

    /** Canonical Redis Stream field name used for the single JSON payload. */
    public static final String JSON_FIELD = "json";

    /**
     * Decodes a stream entry's field map into its JSON payload, preferring the
     * canonical {@code json} field and falling back to the first field value so
     * the consumer tolerates alternate field naming from the other side.
     */
    public static HarnessStreamEntry fromBody(String id, Map<String, String> body) {
        if (body == null || body.isEmpty()) {
            return new HarnessStreamEntry(id, null);
        }
        String value = body.get(JSON_FIELD);
        if (value == null) {
            value = body.values().iterator().next();
        }
        return new HarnessStreamEntry(id, value);
    }
}
