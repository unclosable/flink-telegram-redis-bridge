package io.github.flinktelegrambridge.telegram;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ForceReplyKeyboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramMessageSinkFunctionTest {
    @Test
    void freeTextReplyKeyboardIncludesInputPlaceholder() {
        ForceReplyKeyboard keyboard = TelegramMessageSinkFunction.freeTextReplyKeyboard();

        assertTrue(keyboard.getForceReply());
        assertEquals("Type your answer…", keyboard.getInputFieldPlaceholder());
    }
}
