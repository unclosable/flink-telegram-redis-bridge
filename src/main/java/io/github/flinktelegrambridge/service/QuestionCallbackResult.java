package io.github.flinktelegrambridge.service;

import io.github.flinktelegrambridge.protocol.HarnessEnvelope;
import io.github.flinktelegrambridge.protocol.QuestionCallbackBinding;

import java.util.List;
import java.util.Optional;

/** Validated callback handling result for Telegram acknowledgement and optional keyboard refresh. */
public record QuestionCallbackResult(
        boolean handled,
        String acknowledgement,
        QuestionCallbackBinding binding,
        List<String> selectedValues,
        Optional<HarnessEnvelope> envelope) {

    static QuestionCallbackResult ignored(String acknowledgement) {
        return new QuestionCallbackResult(false, acknowledgement, null, List.of(), Optional.empty());
    }

    /** True after a one-shot answer, when Telegram should remove the completed keyboard. */
    public boolean completed() {
        return handled && binding != null && (!binding.multiSelect() || binding.confirm());
    }
}
