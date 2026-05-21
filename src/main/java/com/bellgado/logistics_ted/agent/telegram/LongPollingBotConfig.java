package com.bellgado.logistics_ted.agent.telegram;

import com.bellgado.logistics_ted.agent.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Configuration
@Profile("dev")
@Slf4j
public class LongPollingBotConfig {

    @Bean
    public TelegramClient telegramClient(AgentProperties agentProperties) {
        return new OkHttpTelegramClient(agentProperties.getTelegram().getBotToken());
    }

    @Bean
    public LogisticsLongPollingBot logisticsLongPollingBot(
            AgentProperties agentProperties,
            TelegramUpdateHandler updateHandler,
            TelegramClient telegramClient) {
        validateBotToken(agentProperties.getTelegram().getBotToken());
        log.info("Initializing Telegram bot in LONG-POLLING mode (dev)");
        return new LogisticsLongPollingBot(agentProperties, updateHandler, telegramClient);
    }

    /**
     * Telegram returns 404 on any API call (including the deleteWebhook the long-polling lib
     * runs at startup) when the token is unknown. The cryptic error originates deep inside
     * BotSession — fail fast here with a useful message instead.
     */
    private static void validateBotToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                "agent.telegram.bot-token is empty. Set TELEGRAM_BOT_TOKEN before starting " +
                "the dev profile, or remove the 'dev' profile to run the dashboard without " +
                "the Telegram bot.");
        }
        // Telegram bot tokens look like "<numeric_bot_id>:<35-char_secret>" — catch obvious typos
        // (whole-quoted string, missing colon, leading/trailing whitespace) before hitting Telegram.
        if (!token.matches("\\d+:[A-Za-z0-9_-]{20,}")) {
            throw new IllegalStateException(
                "agent.telegram.bot-token does not look like a valid Telegram bot token " +
                "(expected '<digits>:<secret>'). Verify with: " +
                "curl https://api.telegram.org/bot<TOKEN>/getMe");
        }
    }

    @Bean
    public TelegramBotsLongPollingApplication telegramBotsApplication() {
        return new TelegramBotsLongPollingApplication();
    }

    @Bean
    public ApplicationRunner registerLongPollingBot(
            TelegramBotsLongPollingApplication application,
            LogisticsLongPollingBot bot) {
        return args -> {
            application.registerBot(bot.getBotToken(), bot.getUpdatesConsumer());
            log.info("Telegram long-polling bot registered and polling started");
        };
    }
}
