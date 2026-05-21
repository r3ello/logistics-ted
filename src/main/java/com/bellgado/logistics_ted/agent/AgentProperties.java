package com.bellgado.logistics_ted.agent;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "agent")
@Validated
@Getter
@Setter
public class AgentProperties {

    private TelegramConfig telegram = new TelegramConfig();
    private String systemPrompt;

    @Getter
    @Setter
    public static class TelegramConfig {
        private String botToken;
        private String botUsername;
        private String webhookUrl;
        private List<Long> allowedChatIds = new ArrayList<>();
    }
}
