package io.github.flinktelegrambridge.telegram;

import io.github.flinktelegrambridge.config.AppConfig;
import io.github.flinktelegrambridge.protocol.QuestionCallbackOption;
import io.github.flinktelegrambridge.protocol.TelegramPayloads;
import io.github.flinktelegrambridge.service.QuestionCallbackResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bot API side effects for bridge callbacks, kept beside the Flink bridge adapter. */
final class TelegramBotCallbackResponder implements TelegramCallbackResponder {
    private static final Logger LOG = LoggerFactory.getLogger(TelegramBotCallbackResponder.class);
    private final Map<String, AppConfig.TelegramBotConfig> bots = new LinkedHashMap<>();
    private final Map<String, OkHttpTelegramClient> clients = new LinkedHashMap<>();

    TelegramBotCallbackResponder(AppConfig config) {
        List<AppConfig.TelegramBotConfig> configured = config.telegramBots().isEmpty()
                ? List.of(new AppConfig.TelegramBotConfig("personal", config.telegramBotToken(), config.telegramOutboundDefaultChatId()))
                : config.telegramBots();
        for (AppConfig.TelegramBotConfig bot : configured) bots.put(bot.id(), bot);
    }

    @Override
    public void respond(TelegramPayloads.TelegramInboundMessage inbound, QuestionCallbackResult result) {
        if (inbound.callbackQueryId() == null || inbound.callbackQueryId().isBlank()) return;
        try {
            OkHttpTelegramClient client = clientFor(inbound.botId());
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(inbound.callbackQueryId())
                    .text(result.acknowledgement())
                    .build());
            if (result.completed() && inbound.messageId() != null && inbound.chatId() != null) {
                String completion = completionText(result);
                client.execute(EditMessageText.builder()
                        .chatId(inbound.chatId())
                        .messageId(inbound.messageId())
                        .text(completion)
                        .replyMarkup(new InlineKeyboardMarkup(List.of()))
                        .build());
            } else if (result.handled() && result.binding() != null && result.binding().multiSelect()
                    && !result.binding().confirm() && inbound.messageId() != null && inbound.chatId() != null) {
                client.execute(EditMessageReplyMarkup.builder()
                        .chatId(inbound.chatId())
                        .messageId(inbound.messageId())
                        .replyMarkup(keyboard(result.binding().options(), result.binding().confirmCallbackKey(), result.selectedValues()))
                        .build());
            }
        } catch (Exception exception) {
            LOG.warn("Unable to acknowledge Telegram callback {}: {}", inbound.callbackQueryId(), exception.getMessage());
        }
    }

    static String completionText(QuestionCallbackResult result) {
        String question = result.binding().questionText();
        if (question == null || question.isBlank()) question = "Question";
        String value = result.selectedValues().isEmpty() ? "Answer recorded" : String.join(", ", result.selectedValues());
        return question + "\n\n✓ " + value;
    }

    private OkHttpTelegramClient clientFor(String botId) {
        AppConfig.TelegramBotConfig bot = bots.get(botId);
        if (bot == null) bot = bots.values().iterator().next();
        OkHttpTelegramClient existing = clients.get(bot.id());
        if (existing != null) return existing;
        OkHttpTelegramClient created = new OkHttpTelegramClient(bot.token());
        clients.put(bot.id(), created);
        return created;
    }

    private static InlineKeyboardMarkup keyboard(
            List<QuestionCallbackOption> options, String confirmCallbackKey, List<String> selected) {
        List<InlineKeyboardRow> rows = new java.util.ArrayList<>();
        for (QuestionCallbackOption option : options) {
            String label = selected.contains(option.value()) ? "✓ " + option.value() : option.value();
            rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder().text(label).callbackData("hq:" + option.callbackKey()).build()));
        }
        if (confirmCallbackKey != null && !confirmCallbackKey.isBlank()) {
            rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder().text("Confirm").callbackData("hq:" + confirmCallbackKey).build()));
        }
        return new InlineKeyboardMarkup(rows);
    }
}
