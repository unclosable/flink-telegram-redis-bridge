package io.github.flinktelegrambridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flinktelegrambridge.protocol.HarnessEnvelope;
import io.github.flinktelegrambridge.protocol.HarnessParseResult;

import java.util.Set;

/**
 * Parses a harness envelope JSON string into a {@link HarnessParseResult}.
 *
 * <p>The parser never throws for malformed input: it rejects a missing or
 * unsupported {@code version} as {@link HarnessParseResult.Status#MALFORMED},
 * and tolerates an unknown {@code type} by returning
 * {@link HarnessParseResult.Status#UNSUPPORTED_TYPE} (forward compatibility).
 */
public final class HarnessEnvelopeParser {

    private static final Set<String> KNOWN_TYPES =
            Set.of(
                    HarnessEnvelope.TYPE_MESSAGE,
                    HarnessEnvelope.TYPE_NEW_SESSION,
                    HarnessEnvelope.TYPE_STEER,
                    HarnessEnvelope.TYPE_ASSISTANT_MESSAGE,
                    HarnessEnvelope.TYPE_ERROR,
                    HarnessEnvelope.TYPE_GROUP_MESSAGE,
                    HarnessEnvelope.TYPE_QUESTION_REQUEST,
                    HarnessEnvelope.TYPE_QUESTION_ANSWER);

    private final ObjectMapper mapper;

    public HarnessEnvelopeParser() {
        this(new ObjectMapper());
    }

    HarnessEnvelopeParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public HarnessParseResult parse(String json) {
        if (json == null || json.isBlank()) {
            return HarnessParseResult.malformed("blank envelope payload");
        }
        final HarnessEnvelope envelope;
        try {
            envelope = mapper.readValue(json, HarnessEnvelope.class);
        } catch (Exception exception) {
            return HarnessParseResult.malformed("unparseable envelope: " + exception.getMessage());
        }
        if (envelope == null) {
            return HarnessParseResult.malformed("null envelope");
        }
        if (envelope.version() == null) {
            return HarnessParseResult.malformed("missing version");
        }
        if (envelope.version() != HarnessEnvelope.SUPPORTED_VERSION) {
            return HarnessParseResult.malformed("unsupported version: " + envelope.version());
        }
        if (envelope.type() == null || envelope.type().isBlank()) {
            return HarnessParseResult.malformed("missing type");
        }
        if (!KNOWN_TYPES.contains(envelope.type())) {
            return HarnessParseResult.unsupportedType(envelope);
        }
        return HarnessParseResult.valid(envelope);
    }
}
