package io.github.flinktelegrambridge.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flinktelegrambridge.service.HarnessEnvelopeParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessEnvelopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static TelegramPayloads.TelegramInboundMessage inbound() {
        return new TelegramPayloads.TelegramInboundMessage(
                "telegram", null, "message", 1001, 42, "12345", "private", "MyChat",
                777L, "alice", "Alice", "hello there", 1700000000, "{}", null, null);
    }

    private static TelegramPayloads.TelegramInboundMessage inboundWithBot() {
        return new TelegramPayloads.TelegramInboundMessage(
                "telegram", "ops-bot", "message", 1001, 42, "12345", "private", "MyChat",
                777L, "alice", "Alice", "hello there", 1700000000, "{}", null, null);
    }

    @Test
    void buildsConversationIdFromSourceAndChatId() {
        assertEquals("telegram:chat:12345", HarnessEnvelope.buildConversationId("telegram", "12345"));
        assertEquals(
                "telegram:ops-bot:chat:12345",
                HarnessEnvelope.buildConversationId("telegram", "ops-bot", "12345"));
    }

    @Test
    void buildsMessageIdFromUpdateIdWithFallback() {
        assertEquals("telegram-update-1001", HarnessEnvelope.buildMessageId(1001, 42));
        assertEquals("telegram-message-42", HarnessEnvelope.buildMessageId(null, 42));
    }

    @Test
    void buildsInboundEnvelope() {
        HarnessEnvelope envelope = HarnessEnvelope.fromInbound(inbound());

        assertEquals(1, envelope.version());
        assertEquals("telegram:chat:12345", envelope.conversationId());
        assertNull(envelope.sessionId());
        assertEquals("telegram-update-1001", envelope.messageId());
        assertNull(envelope.correlationId());
        assertEquals("message", envelope.type());
        assertEquals("hello there", envelope.content().get("text"));

        assertEquals("12345", envelope.metadata().get("chatId"));
        assertEquals("telegram", envelope.metadata().get("source"));
        assertEquals("message", envelope.metadata().get("sourceType"));
        assertEquals(1001, envelope.metadata().get("updateId"));
        assertEquals(42, envelope.metadata().get("messageId"));
        assertEquals(777L, envelope.metadata().get("fromUserId"));
        assertEquals("alice", envelope.metadata().get("fromUsername"));
        assertEquals("Alice", envelope.metadata().get("fromFirstName"));
        assertEquals(1700000000, envelope.metadata().get("messageDate"));
        assertEquals("private", envelope.metadata().get("chatType"));
        assertEquals("MyChat", envelope.metadata().get("chatTitle"));
        assertFalse(envelope.metadata().containsKey("botId"));
    }

    @Test
    void buildsInboundEnvelopeWithBotId() {
        HarnessEnvelope envelope = HarnessEnvelope.fromInbound(inboundWithBot());
        assertEquals("telegram:ops-bot:chat:12345", envelope.conversationId());
        assertEquals("ops-bot", envelope.metadata().get("botId"));
    }

    @Test
    void serializesInboundEnvelopeToProtocolShape() throws Exception {
        String json = MAPPER.writeValueAsString(HarnessEnvelope.fromInbound(inbound()));
        JsonNode node = MAPPER.readTree(json);

        assertEquals(1, node.get("version").asInt());
        assertEquals("telegram:chat:12345", node.get("conversation_id").asText());
        assertTrue(node.has("session_id"));
        assertTrue(node.get("session_id").isNull());
        assertEquals("telegram-update-1001", node.get("message_id").asText());
        assertFalse(node.has("correlation_id"), "inbound envelope must not carry correlation_id");
        assertEquals("message", node.get("type").asText());
        assertEquals("hello there", node.get("content").get("text").asText());
        assertEquals("12345", node.get("metadata").get("chatId").asText());
    }

    @Test
    void serializesOutboundEnvelopeToProtocolShape() throws Exception {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("text", "reply");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chatId", "12345");
        HarnessEnvelope envelope =
                new HarnessEnvelope(
                        1, "telegram:chat:12345", "harness-session-id",
                        null, "telegram-update-1001", "assistant_message", content, metadata);

        String json = MAPPER.writeValueAsString(envelope);
        JsonNode node = MAPPER.readTree(json);

        assertEquals("harness-session-id", node.get("session_id").asText());
        assertEquals("telegram-update-1001", node.get("correlation_id").asText());
        assertFalse(node.has("message_id"), "outbound envelope must not carry message_id");
        assertEquals("assistant_message", node.get("type").asText());
    }

    @Test
    void roundTripsThroughParser() throws Exception {
        HarnessEnvelope original = HarnessEnvelope.fromInbound(inbound());
        String json = MAPPER.writeValueAsString(original);

        HarnessParseResult result = new HarnessEnvelopeParser().parse(json);
        assertTrue(result.isValid());
        HarnessEnvelope parsed = result.envelope();

        assertEquals(original.version(), parsed.version());
        assertEquals(original.conversationId(), parsed.conversationId());
        assertEquals(original.messageId(), parsed.messageId());
        assertEquals(original.type(), parsed.type());
        assertEquals(original.content().get("text"), parsed.content().get("text"));
        assertEquals("12345", parsed.metadata().get("chatId").toString());
    }
}
