package com.bellgado.logistics_ted.agent;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfig {

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(30)
                .build();
    }

    @Bean
    public ChatClient chatClient(
            ChatClient.Builder builder,
            ChatMemory chatMemory,
            LogisticsAgentTools logisticsAgentTools,
            AgentProperties agentProperties) {

        return builder
                .defaultSystem(agentProperties.getSystemPrompt())
                .defaultTools(logisticsAgentTools)
                .defaultAdvisors(
                        List.of(MessageChatMemoryAdvisor.builder(chatMemory).build(),
                                new SimpleLoggerAdvisor())
                )
                .build();
    }
}
