package io.github.flinktelegrambridge.telegram;

import io.github.flinktelegrambridge.protocol.QuestionCallbackBinding;
import io.github.flinktelegrambridge.service.QuestionCallbackResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramBotCallbackResponderTest {
    @Test
    void completionEditUsesOriginalQuestionAndRecordsSelectedAnswer() {
        QuestionCallbackBinding binding = new QuestionCallbackBinding(
                "key", "c", "telegram:chat:1", "environment", "prod", "1", "", false,
                List.of(), null, false, false, "Choose an environment");
        QuestionCallbackResult result = new QuestionCallbackResult(true, "✓", binding, List.of("prod"), Optional.empty());

        assertTrue(result.completed());
        assertEquals("Choose an environment\n\n✓ prod", TelegramBotCallbackResponder.completionText(result));
    }
}
