package io.github.flinktelegrambridge.service;

import io.github.flinktelegrambridge.protocol.HarnessEnvelope;
import io.github.flinktelegrambridge.protocol.HarnessParseResult;
import io.github.flinktelegrambridge.protocol.RoutingDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessOutboundRouterTest {

    private final HarnessOutboundRouter router = new HarnessOutboundRouter();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void recoversChatIdFromMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chatId", "12345");
        HarnessEnvelope envelope = envelope("telegram:chat:99999", metadata, "assistant_message", text("hi"));
        assertEquals("12345", HarnessOutboundRouter.resolveChatId(envelope));
    }

    @Test
    void recoversChatIdFromConversationIdFallback() {
        HarnessEnvelope envelope = envelope("telegram:chat:99999", new LinkedHashMap<>(), "assistant_message", text("hi"));
        assertEquals("99999", HarnessOutboundRouter.resolveChatId(envelope));
    }

    @Test
    void recoversChatIdFromBotConversationIdFallback() {
        HarnessEnvelope envelope =
                envelope("telegram:ops-bot:chat:99999", new LinkedHashMap<>(), "assistant_message", text("hi"));
        assertEquals("99999", HarnessOutboundRouter.resolveChatId(envelope));
    }

    @Test
    void returnsNullWhenChatIdUnresolvable() {
        HarnessEnvelope envelope = envelope("weird:conversation", new LinkedHashMap<>(), "assistant_message", text("hi"));
        assertNull(HarnessOutboundRouter.resolveChatId(envelope));
    }

    @Test
    void routesAssistantMessage() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chatId", "12345");
        HarnessEnvelope envelope = envelope("telegram:chat:12345", metadata, "assistant_message", text("hello"));

        RoutingDecision decision = router.route(HarnessParseResult.valid(envelope));
        assertTrue(decision.shouldForward());
        assertEquals("12345", decision.outboundMessage().chatId());
        assertEquals("hello", decision.outboundMessage().text());
        assertEquals("", decision.outboundMessage().parseMode());
        assertNull(decision.outboundMessage().botId());
    }

    @Test
    void routesAssistantMessageWithBotId() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chatId", "12345");
        metadata.put("botId", "ops-bot");
        HarnessEnvelope envelope = envelope("telegram:ops-bot:chat:12345", metadata, "assistant_message", text("hello"));

        RoutingDecision decision = router.route(HarnessParseResult.valid(envelope));
        assertTrue(decision.shouldForward());
        assertEquals("ops-bot", decision.outboundMessage().botId());
    }

    @Test
    void routesMarkdownAssistantMessageWithStringContent() throws Exception {
        HarnessEnvelope envelope = new HarnessEnvelope(1, "telegram:chat:12345", "s1", null, "c1",
                "assistant_message", "TEXT/MARKDOWN", JSON.readTree("\"## Result\\n\\n**Success**!\""), Map.of("chatId", "12345"));
        RoutingDecision decision = router.route(HarnessParseResult.valid(envelope));
        assertTrue(decision.shouldForward());
        assertEquals("MarkdownV2", decision.outboundMessage().parseMode());
        assertEquals("*Result*\n\n*Success*\\!", decision.outboundMessage().text());
    }

    @Test
    void routesMarkdownAssistantMessageWithLegacyObjectContent() {
        HarnessEnvelope envelope = new HarnessEnvelope(1, "telegram:chat:12345", "s1", null, "c1",
                "assistant_message", "text/markdown", JSON.valueToTree(text("*emphasis*")), Map.of("chatId", "12345"));
        RoutingDecision decision = router.route(HarnessParseResult.valid(envelope));
        assertEquals("_emphasis_", decision.outboundMessage().text());
        assertEquals("MarkdownV2", decision.outboundMessage().parseMode());
    }

    @Test
    void routesErrorWithChatId() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chatId", "12345");
        HarnessEnvelope envelope = envelope("telegram:chat:12345", metadata, "error", text("boom"));

        RoutingDecision decision = router.route(HarnessParseResult.valid(envelope));
        assertTrue(decision.shouldForward());
        assertTrue(decision.outboundMessage().text().startsWith("Error:"));
    }

    @Test
    void routesMarkdownErrorWithMarkdownV2() throws Exception {
        HarnessEnvelope envelope = new HarnessEnvelope(1, "telegram:chat:12345", "s1", null, "c1", "error",
                "text/markdown", JSON.readTree("\"**boom**!\""), Map.of("chatId", "12345"));
        RoutingDecision decision = router.route(HarnessParseResult.valid(envelope));
        assertEquals("MarkdownV2", decision.outboundMessage().parseMode());
        assertEquals("Error: *boom*\\!", decision.outboundMessage().text());
    }

    @Test
    void dropsErrorWithoutChatId() {
        HarnessEnvelope envelope = envelope("weird:conversation", new LinkedHashMap<>(), "error", text("boom"));
        RoutingDecision decision = router.route(HarnessParseResult.valid(envelope));
        assertFalse(decision.shouldForward());
    }

    @Test
    void dropsUnknownType() {
        HarnessEnvelope envelope = envelope("telegram:chat:12345", new LinkedHashMap<>(), "weird", text("x"));
        RoutingDecision decision = router.route(HarnessParseResult.unsupportedType(envelope));
        assertFalse(decision.shouldForward());
    }

    @Test
    void dropsMalformed() {
        RoutingDecision decision = router.route(HarnessParseResult.malformed("missing version"));
        assertFalse(decision.shouldForward());
    }

    @Test
    void routesQuestionRequestWithOpaqueCallbackButtons() {
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("id", "deployment");
        question.put("text", "Choose a deployment approach");
        question.put("options", List.of("Blue/green", "Rolling"));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("questions", List.of(question));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chatId", "12345");
        metadata.put("botId", "ops-bot");

        RoutingDecision decision = router.route(HarnessParseResult.valid(
                new HarnessEnvelope(1, "telegram:ops-bot:chat:12345", "s1", null, "opaque-correlation",
                        "question_request", content, metadata)));

        assertTrue(decision.shouldForward());
        assertEquals(1, decision.outboundMessages().size());
        assertEquals("Choose a deployment approach", decision.outboundMessage().text());
        assertEquals(2, decision.outboundMessage().inlineKeyboard().size());
        String callbackData = decision.outboundMessage().inlineKeyboard().get(0).get(0).callbackData();
        assertTrue(callbackData.startsWith("hq:"));
        assertTrue(callbackData.length() <= 64, "Telegram callback_data must fit its 64-byte limit");
        assertEquals(decision.callbackBindings().get(0).callbackKey(), callbackData.substring("hq:".length()));
        assertEquals(2, decision.callbackBindings().size());
        assertEquals("opaque-correlation", decision.callbackBindings().get(0).correlationId());
        assertEquals("deployment", decision.callbackBindings().get(0).questionId());
        assertEquals("Blue/green", decision.callbackBindings().get(0).value());
    }

    @Test
    void routesOptionlessQuestionAsFreeTextPrompt() {
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("id", "details");
        question.put("text", "What should we call this deployment?");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("questions", List.of(question));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chatId", "12345");

        RoutingDecision decision = router.route(HarnessParseResult.valid(
                new HarnessEnvelope(1, "telegram:chat:12345", "s1", null, "opaque-correlation",
                        "question_request", content, metadata)));

        assertTrue(decision.shouldForward());
        assertTrue(decision.outboundMessage().inlineKeyboard().isEmpty());
        assertTrue(decision.outboundMessage().forceReply());
        assertTrue(decision.outboundMessage().freeTextBinding().freeText());
        assertEquals(1, decision.callbackBindings().size());
        assertTrue(decision.callbackBindings().get(0).freeText());
        assertEquals("details", decision.callbackBindings().get(0).questionId());
    }

    @Test
    void routesEnabledGroupMessageToExplicitTargetAndBot() {
        HarnessOutboundRouter enabled = new HarnessOutboundRouter(Set.of("personal", "operator"), true);
        RoutingDecision decision = enabled.route(HarnessParseResult.valid(groupMessage("hello", null, Map.of(
                "target_chat_id", "-100123", "bot_id", "operator"))));
        assertTrue(decision.shouldForward());
        assertEquals("-100123", decision.outboundMessage().chatId());
        assertEquals("operator", decision.outboundMessage().botId());
        assertEquals("hello", decision.outboundMessage().text());
        assertEquals("", decision.outboundMessage().parseMode());
    }

    @Test
    void groupMessageUsesExplicitTargetAndBotWithoutFallback() {
        HarnessOutboundRouter enabled = new HarnessOutboundRouter(Set.of("operator"), true);
        HarnessEnvelope envelope = new HarnessEnvelope(1, "telegram:chat:999", "s1", null, "c1", "group_message",
                null, JSON.valueToTree(text("hello")), Map.of(
                "target_chat_id", "-100123", "bot_id", "operator", "chatId", "999", "botId", "decoy"));

        RoutingDecision decision = enabled.route(HarnessParseResult.valid(envelope));

        assertTrue(decision.shouldForward());
        assertEquals("-100123", decision.outboundMessage().chatId());
        assertEquals("operator", decision.outboundMessage().botId());
    }

    @Test
    void routesMarkdownGroupMessage() {
        HarnessOutboundRouter enabled = new HarnessOutboundRouter(Set.of("personal"), true);
        RoutingDecision decision = enabled.route(HarnessParseResult.valid(groupMessage("**hello**!", "TEXT/MARKDOWN", Map.of(
                "target_chat_id", "-100123", "bot_id", "personal"))));
        assertTrue(decision.shouldForward());
        assertEquals("*hello*\\!", decision.outboundMessage().text());
        assertEquals("MarkdownV2", decision.outboundMessage().parseMode());
    }

    @Test
    void defaultRouterDropsGroupMessage() {
        RoutingDecision decision = router.route(HarnessParseResult.valid(groupMessage("hello", null, Map.of(
                "target_chat_id", "-100123", "bot_id", "personal"))));
        assertFalse(decision.shouldForward());
        assertEquals("group_message dropped: feature disabled", decision.reason());
    }

    @Test
    void enabledGroupMessageValidatesExplicitFieldsAndBot() {
        HarnessOutboundRouter enabled = new HarnessOutboundRouter(Set.of("personal"), true);
        assertFalse(enabled.route(HarnessParseResult.valid(groupMessage(" ", null, Map.of(
                "target_chat_id", "-100123", "bot_id", "personal")))).shouldForward());
        assertFalse(enabled.route(HarnessParseResult.valid(groupMessage("hello", null, Map.of(
                "bot_id", "personal")))).shouldForward());
        assertFalse(enabled.route(HarnessParseResult.valid(groupMessage("hello", null, Map.of(
                "target_chat_id", "-100123")))).shouldForward());
        RoutingDecision unknown = enabled.route(HarnessParseResult.valid(groupMessage("hello", null, Map.of(
                "target_chat_id", "-100123", "bot_id", "unknown"))));
        assertFalse(unknown.shouldForward());
        assertEquals("group_message dropped: unknown bot_id unknown", unknown.reason());
    }

    @Test
    void enabledRouterPreservesExistingOutboundBehavior() {
        HarnessOutboundRouter enabled = new HarnessOutboundRouter(Set.of("personal"), true);
        RoutingDecision assistant = enabled.route(HarnessParseResult.valid(envelope(
                "telegram:chat:12345", Map.of("chatId", "12345"), "assistant_message", text("hello"))));
        RoutingDecision error = enabled.route(HarnessParseResult.valid(envelope(
                "telegram:chat:12345", Map.of("chatId", "12345"), "error", text("boom"))));
        Map<String, Object> question = Map.of("id", "choice", "text", "Choose", "options", List.of("yes"));
        RoutingDecision questionRequest = enabled.route(HarnessParseResult.valid(new HarnessEnvelope(
                1, "telegram:chat:12345", "s1", null, "c1", "question_request",
                Map.of("questions", List.of(question)), Map.of("chatId", "12345"))));
        assertTrue(assistant.shouldForward());
        assertTrue(error.shouldForward());
        assertTrue(questionRequest.shouldForward());
    }

    private static HarnessEnvelope groupMessage(String text, String contentType, Map<String, Object> metadata) {
        return new HarnessEnvelope(1, "unrelated:conversation", "s1", null, "c1", "group_message",
                contentType, JSON.valueToTree(text(text)), metadata);
    }

    private static HarnessEnvelope envelope(
            String conversationId, Map<String, Object> metadata, String type, Map<String, Object> content) {
        return new HarnessEnvelope(1, conversationId, "s1", null, "c1", type, content, metadata);
    }

    private static Map<String, Object> text(String value) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("text", value);
        return content;
    }
}
