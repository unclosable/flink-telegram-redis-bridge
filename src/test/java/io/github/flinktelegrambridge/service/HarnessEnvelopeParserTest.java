package io.github.flinktelegrambridge.service;

import io.github.flinktelegrambridge.protocol.HarnessParseResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessEnvelopeParserTest {

    private final HarnessEnvelopeParser parser = new HarnessEnvelopeParser();

    private static String outboundJson(String type) {
        return "{\"version\":1,\"conversation_id\":\"telegram:chat:12345\","
                + "\"session_id\":\"s1\",\"correlation_id\":\"telegram-update-1\","
                + "\"type\":\""
                + type
                + "\",\"content\":{\"text\":\"hi\"},"
                + "\"metadata\":{\"chatId\":\"12345\"}}";
    }

    @Test
    void parsesValidEnvelope() {
        HarnessParseResult result = parser.parse(outboundJson("assistant_message"));
        assertTrue(result.isValid());
        assertEquals("assistant_message", result.type());
        assertEquals("12345", result.envelope().metadata().get("chatId"));
    }

    @Test
    void parsesStringContentEnvelope() {
        HarnessParseResult result = parser.parse("{\"version\":1,\"conversation_id\":\"telegram:chat:12345\","
                + "\"type\":\"assistant_message\",\"content_type\":\"text/markdown\","
                + "\"content\":\"**hi**\",\"metadata\":{\"chatId\":\"12345\"}}");
        assertTrue(result.isValid());
        assertEquals("**hi**", result.envelope().content().asText());
        assertEquals("text/markdown", result.envelope().contentType());
    }

    @Test
    void parsesQuestionRequestAsKnownType() {
        HarnessParseResult result = parser.parse(
                "{\"version\":1,\"conversation_id\":\"telegram:chat:12345\",\"session_id\":\"s1\","
                        + "\"correlation_id\":\"opaque\",\"type\":\"question_request\","
                        + "\"content\":{\"questions\":[]},\"metadata\":{\"chatId\":\"12345\"}}");
        assertTrue(result.isValid());
        assertEquals("question_request", result.type());
    }

    @Test
    void rejectsMissingVersion() {
        String json =
                "{\"conversation_id\":\"telegram:chat:1\",\"type\":\"message\","
                        + "\"content\":{},\"metadata\":{}}";
        HarnessParseResult result = parser.parse(json);
        assertTrue(result.isMalformed());
        assertNull(result.envelope());
    }

    @Test
    void rejectsUnsupportedVersion() {
        String json =
                "{\"version\":2,\"conversation_id\":\"telegram:chat:1\",\"type\":\"message\","
                        + "\"content\":{},\"metadata\":{}}";
        HarnessParseResult result = parser.parse(json);
        assertTrue(result.isMalformed());
        assertTrue(result.reason().contains("version"));
    }

    @Test
    void toleratesUnknownType() {
        HarnessParseResult result = parser.parse(outboundJson("totally_new_type"));
        assertTrue(result.isUnsupportedType());
        assertEquals("totally_new_type", result.type());
        assertNotNull(result.envelope());
    }

    @Test
    void toleratesUnknownExtraField() {
        String json =
                "{\"version\":1,\"conversation_id\":\"telegram:chat:1\","
                        + "\"session_id\":\"s1\",\"type\":\"assistant_message\","
                        + "\"content\":{\"text\":\"hi\"},\"metadata\":{},"
                        + "\"future_field\":true,\"another_future\":42}";
        HarnessParseResult result = parser.parse(json);
        assertTrue(result.isValid());
        assertEquals("assistant_message", result.type());
    }

    @Test
    void rejectsMalformedJson() {
        HarnessParseResult result = parser.parse("not-json{");
        assertTrue(result.isMalformed());
    }

    @Test
    void rejectsBlankPayload() {
        assertTrue(parser.parse(null).isMalformed());
        assertTrue(parser.parse("   ").isMalformed());
    }
}
