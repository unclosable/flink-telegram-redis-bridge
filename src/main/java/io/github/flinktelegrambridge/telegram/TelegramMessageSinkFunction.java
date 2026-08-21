package io.github.flinktelegrambridge.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flinktelegrambridge.config.AppConfig;
import io.github.flinktelegrambridge.protocol.TelegramPayloads;
import io.github.flinktelegrambridge.registry.LettuceQuestionCallbackRegistry;
import io.github.flinktelegrambridge.registry.QuestionCallbackRegistry;
import org.apache.flink.streaming.api.functions.sink.legacy.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ForceReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flink sink that sends outbound messages through the Telegram Bot API.
 */
public final class TelegramMessageSinkFunction extends RichSinkFunction<String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(TelegramMessageSinkFunction.class);

    private final List<AppConfig.TelegramBotConfig> bots;
    private final Map<String, AppConfig.TelegramBotConfig> botsById;

    private transient Map<String, OkHttpTelegramClient> telegramClients;
    private transient QuestionCallbackRegistry callbackRegistry;

    public TelegramMessageSinkFunction() {
        this(resolveConfiguredBots(AppConfig.load()));
    }

    public TelegramMessageSinkFunction(String botToken) {
        this(botToken, "");
    }

    public TelegramMessageSinkFunction(String botToken, String defaultChatId) {
        this(
                List.of(
                        new AppConfig.TelegramBotConfig(
                                "primary",
                                requireNonBlank(botToken, "Telegram bot token must not be blank."),
                                normalizeValue(defaultChatId))));
    }

    public TelegramMessageSinkFunction(List<AppConfig.TelegramBotConfig> bots) {
        List<AppConfig.TelegramBotConfig> normalizedBots = normalizeBots(bots);
        this.bots = List.copyOf(normalizedBots);
        this.botsById = new LinkedHashMap<>();
        for (AppConfig.TelegramBotConfig bot : normalizedBots) {
            this.botsById.put(bot.id(), bot);
        }
    }

    @Override
    public void invoke(String value, Context context) throws Exception {
        TelegramPayloads.TelegramOutboundMessage payload;
        AppConfig.TelegramBotConfig selectedBot = null;
        String chatId = null;
        SendMessage sendMessage;
        try {
            payload = parsePayload(value);
            selectedBot = selectBot(payload);
            chatId = resolveChatId(payload.chatId(), selectedBot.defaultChatId());
            sendMessage = sendMessageOf(payload, chatId);
            if (Boolean.TRUE.equals(payload.disableNotification())) {
                sendMessage.setDisableNotification(true);
            }
            if (!payload.inlineKeyboard().isEmpty()) {
                sendMessage.setReplyMarkup(toInlineKeyboard(payload.inlineKeyboard()));
            } else if (Boolean.TRUE.equals(payload.forceReply())) {
                sendMessage.setReplyMarkup(freeTextReplyKeyboard());
            }
            sendMessage.validate();
        } catch (Exception exception) {
            LOG.warn(
                    "Dropping Telegram outbound message for bot {} chat {}: {}",
                    selectedBot == null ? "<unresolved>" : selectedBot.id(),
                    chatId == null ? "<unresolved>" : chatId,
                    exception.getMessage());
            LOG.debug("Dropped Telegram outbound payload: {}", value, exception);
            return;
        }

        LOG.info(
                "Sending Telegram message via bot {} to chat {} with textLength={}",
                selectedBot.id(),
                chatId,
                sendMessage.getText().length());
        LOG.debug("Telegram outbound text for bot {} chat {}: {}", selectedBot.id(), chatId, sendMessage.getText());
        try {
            var sent = telegramClient(selectedBot).execute(sendMessage);
            if (payload.freeTextBinding() != null) {
                freeTextRegistry().registerFreeTextPrompt(payload.freeTextBinding(), sent.getMessageId());
            }
            LOG.info("Telegram message sent via bot {} to chat {}", selectedBot.id(), chatId);
        } catch (Exception exception) {
            LOG.error("Telegram send via bot {} to chat {} failed: {}", selectedBot.id(), chatId, exception.getMessage());
            LOG.debug("Telegram send failure via bot {} chat {}", selectedBot.id(), chatId, exception);
        }
    }

    @Override
    public void close() throws Exception {
        if (callbackRegistry != null) callbackRegistry.close();
        super.close();
    }

    private QuestionCallbackRegistry freeTextRegistry() {
        if (callbackRegistry == null) callbackRegistry = new LettuceQuestionCallbackRegistry(AppConfig.load());
        return callbackRegistry;
    }

    private static InlineKeyboardMarkup toInlineKeyboard(
            List<List<TelegramPayloads.TelegramInlineKeyboardButton>> keyboard) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (List<TelegramPayloads.TelegramInlineKeyboardButton> row : keyboard) {
            if (row == null || row.isEmpty()) continue;
            InlineKeyboardRow telegramRow = new InlineKeyboardRow();
            for (TelegramPayloads.TelegramInlineKeyboardButton button : row) {
                if (button == null) continue;
                telegramRow.add(
                        InlineKeyboardButton.builder()
                                .text(requireNonBlank(button.text(), "Inline keyboard button text must not be blank."))
                                .callbackData(requireNonBlank(button.callbackData(), "Inline keyboard callback data must not be blank."))
                                .build());
            }
            if (!telegramRow.isEmpty()) rows.add(telegramRow);
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("Inline keyboard must contain at least one button.");
        return new InlineKeyboardMarkup(rows);
    }

    static ForceReplyKeyboard freeTextReplyKeyboard() {
        return ForceReplyKeyboard.builder()
                .forceReply(true)
                .inputFieldPlaceholder("Type your answer…")
                .build();
    }

    static SendMessage sendMessageOf(TelegramPayloads.TelegramOutboundMessage payload, String chatId) {
        SendMessage sendMessage = SendMessage.builder()
                .chatId(chatId)
                .text(requireNonBlank(payload.text(), "Telegram outbound text must not be blank."))
                .build();
        if (payload.parseMode() != null && !payload.parseMode().isBlank()) {
            sendMessage.setParseMode(payload.parseMode().trim());
        }
        return sendMessage;
    }

    private OkHttpTelegramClient telegramClient(AppConfig.TelegramBotConfig bot) {
        if (telegramClients == null) {
            telegramClients = new LinkedHashMap<>();
        }
        OkHttpTelegramClient existingClient = telegramClients.get(bot.id());
        if (existingClient != null) {
            return existingClient;
        }
        OkHttpTelegramClient createdClient = new OkHttpTelegramClient(bot.token());
        telegramClients.put(bot.id(), createdClient);
        return createdClient;
    }

    private static TelegramPayloads.TelegramOutboundMessage parsePayload(String value) throws Exception {
        String normalizedValue = requireNonBlank(value, "Telegram sink payload must not be blank.");
        return MAPPER.readValue(normalizedValue, TelegramPayloads.TelegramOutboundMessage.class);
    }

    private static String resolveChatId(String payloadChatId, String defaultChatId) {
        if (payloadChatId != null && !payloadChatId.isBlank()) {
            return payloadChatId.trim();
        }
        if (defaultChatId != null && !defaultChatId.isBlank()) {
            return defaultChatId.trim();
        }
        throw new IllegalArgumentException(
                "Telegram outbound chatId must be provided in the payload or via telegram.outbound.default-chat-id.");
    }

    private AppConfig.TelegramBotConfig selectBot(TelegramPayloads.TelegramOutboundMessage payload) {
        AppConfig.TelegramBotConfig primaryBot = bots.get(0);
        String requestedBotId = normalizeValue(payload.botId());
        if (!requestedBotId.isBlank()) {
            AppConfig.TelegramBotConfig requestedBot = botsById.get(requestedBotId);
            if (requestedBot != null) {
                return requestedBot;
            }
            LOG.warn("Requested outbound botId {} is not configured; falling back to primary bot {}", requestedBotId, primaryBot.id());
            return primaryBot;
        }

        String resolvedChatId;
        try {
            resolvedChatId = resolveChatId(payload.chatId(), primaryBot.defaultChatId());
        } catch (IllegalArgumentException exception) {
            return primaryBot;
        }

        AppConfig.TelegramBotConfig matchingBot = null;
        for (AppConfig.TelegramBotConfig bot : bots) {
            if (bot.defaultChatId().equals(resolvedChatId)) {
                if (matchingBot != null) {
                    return primaryBot;
                }
                matchingBot = bot;
            }
        }
        return matchingBot == null ? primaryBot : matchingBot;
    }

    private static List<AppConfig.TelegramBotConfig> normalizeBots(List<AppConfig.TelegramBotConfig> bots) {
        if (bots == null || bots.isEmpty()) {
            throw new IllegalArgumentException("Telegram bot list must not be empty.");
        }
        List<AppConfig.TelegramBotConfig> normalizedBots = new ArrayList<>();
        Map<String, Boolean> ids = new LinkedHashMap<>();
        for (int index = 0; index < bots.size(); index++) {
            AppConfig.TelegramBotConfig bot = bots.get(index);
            if (bot == null) {
                continue;
            }
            String id = normalizeValue(bot.id());
            if (id.isBlank()) {
                id = "bot-" + (index + 1);
            }
            String token = requireNonBlank(bot.token(), "Telegram bot token must not be blank.");
            String defaultChatId = normalizeValue(bot.defaultChatId());
            if (ids.containsKey(id)) {
                throw new IllegalArgumentException("Duplicate Telegram bot id: " + id);
            }
            ids.put(id, true);
            normalizedBots.add(new AppConfig.TelegramBotConfig(id, token, defaultChatId));
        }
        if (normalizedBots.isEmpty()) {
            throw new IllegalArgumentException("Telegram bot list must not be empty.");
        }
        return normalizedBots;
    }

    private static String normalizeValue(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private static List<AppConfig.TelegramBotConfig> resolveConfiguredBots(AppConfig config) {
        if (!config.telegramBots().isEmpty()) {
            return config.telegramBots();
        }
        return List.of(
                new AppConfig.TelegramBotConfig(
                        "primary", config.telegramBotToken(), config.telegramOutboundDefaultChatId()));
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
