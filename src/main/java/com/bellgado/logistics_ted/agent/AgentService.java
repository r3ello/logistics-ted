package com.bellgado.logistics_ted.agent;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentService {

    private static final ZoneId APP_ZONE = ZoneId.of("Europe/Sofia");

    private static final DateTimeFormatter CURRENT_DATE_FMT =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' HH:mm zzz '(UTC'xxx')'", Locale.ENGLISH);

    private final ChatClient chatClient;
    private final AgentProperties agentProperties;

    public String chat(String conversationId, String userMessage) {
        log.debug("Agent chat [{}]: {}", conversationId, userMessage);

        ZonedDateTime nowSofia = ZonedDateTime.now(APP_ZONE);
        String currentDate = nowSofia.format(CURRENT_DATE_FMT);
        String systemPromptWithDate = agentProperties.getSystemPrompt()
                .replace("{current_date}", currentDate);

        try {
            String response = chatClient.prompt()
                    .system(systemPromptWithDate)
                    .user(userMessage)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();

            log.debug("Agent response [{}]: {}", conversationId, response);
            return response;
        } catch (Exception e) {
            log.error("AI processing error for conversation {}: {}", conversationId, e.getMessage(), e);
            return "I'm having trouble processing your request right now. Please try again in a moment.";
        }
    }
}
