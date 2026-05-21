package com.bellgado.logistics_ted.agent.telegram;

import com.bellgado.logistics_ted.agent.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Configuration
@Profile("prod")
@Slf4j
public class WebhookBotConfig {

    @Bean
    public TelegramClient telegramClient(AgentProperties agentProperties) {
        String token = agentProperties.getTelegram().getBotToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                "agent.telegram.bot-token is empty. Set TELEGRAM_BOT_TOKEN before starting " +
                "the prod profile.");
        }
        if (!token.matches("\\d+:[A-Za-z0-9_-]{20,}")) {
            throw new IllegalStateException(
                "agent.telegram.bot-token does not look like a valid Telegram bot token " +
                "(expected '<digits>:<secret>'). Verify with: " +
                "curl https://api.telegram.org/bot<TOKEN>/getMe");
        }
        return new OkHttpTelegramClient(token);
    }

    @Bean
    public ApplicationRunner registerWebhook(AgentProperties agentProperties, TelegramClient telegramClient) {
        return args -> {
            String webhookUrl = agentProperties.getTelegram().getWebhookUrl();
            if (webhookUrl == null || webhookUrl.isBlank()) {
                log.warn("agent.telegram.webhook-url is not set — skipping webhook registration");
                return;
            }
            telegramClient.execute(SetWebhook.builder().url(webhookUrl).build());
            log.info("Telegram webhook registered at: {}", webhookUrl);
        };
    }

    @RestController
    @Profile("prod")
    @RequiredArgsConstructor
    static class TelegramWebhookController {

        private final TelegramUpdateHandler updateHandler;
        private final TelegramClient telegramClient;

        @PostMapping("/api/telegram/webhook")
        public void onWebhookUpdate(@RequestBody Update update) {
            updateHandler.handleUpdate(update, telegramClient);
        }
    }
}
