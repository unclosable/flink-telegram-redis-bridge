package io.github.flinktelegrambridge.telegram;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ForceReplyKeyboard;
import io.github.flinktelegrambridge.protocol.TelegramPayloads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramMessageSinkFunctionTest {
    @Test
    void freeTextReplyKeyboardIncludesInputPlaceholder() {
        ForceReplyKeyboard keyboard = TelegramMessageSinkFunction.freeTextReplyKeyboard();

        assertTrue(keyboard.getForceReply());
        assertEquals("Type your answer…", keyboard.getInputFieldPlaceholder());
    }

    @Test
    void passesMarkdownParseModeToTelegramRequest() {
        var request = TelegramMessageSinkFunction.sendMessageOf(
                new TelegramPayloads.TelegramOutboundMessage("123", "*done*", "MarkdownV2", false, null), "123");
        assertEquals("MarkdownV2", request.getParseMode());
    }
}
