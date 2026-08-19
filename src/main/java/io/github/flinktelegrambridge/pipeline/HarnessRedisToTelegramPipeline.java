package io.github.flinktelegrambridge.pipeline;

import io.github.flinktelegrambridge.telegram.HarnessOutboundSourceFunction;
import io.github.flinktelegrambridge.telegram.TelegramMessageSinkFunction;
import io.github.flinktelegrambridge.config.AppConfig;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * Inbound job: consumes the {@code harness:outbound} Redis Stream via a consumer
 * group, routes {@code assistant_message} / {@code error} envelopes, and
 * delivers them to Telegram by reusing {@link TelegramMessageSinkFunction}.
 */
public class HarnessRedisToTelegramPipeline {

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        DataStream<String> outboundMessages =
                env.addSource(new HarnessOutboundSourceFunction())
                        .assignTimestampsAndWatermarks(WatermarkStrategy.noWatermarks())
                        .name("harness-outbound-stream-source");

        outboundMessages
                .addSink(
                        config.telegramBots().isEmpty()
                                ? new TelegramMessageSinkFunction(
                                        config.telegramBotToken(), config.telegramOutboundDefaultChatId())
                                : new TelegramMessageSinkFunction(config.telegramBots()))
                .name("telegram-outbound-sink");

        env.execute("harness-redis-to-telegram");
    }
}
