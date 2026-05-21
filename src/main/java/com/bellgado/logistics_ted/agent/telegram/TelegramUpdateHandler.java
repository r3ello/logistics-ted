package com.bellgado.logistics_ted.agent.telegram;

import com.bellgado.logistics_ted.agent.AgentProperties;
import com.bellgado.logistics_ted.agent.AgentService;
import com.bellgado.logistics_ted.agent.VoiceTranscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class TelegramUpdateHandler {

    private final AgentService agentService;
    private final AgentProperties agentProperties;
    private final VoiceTranscriptionService voiceTranscriptionService;

    public void handleUpdate(Update update, TelegramClient telegramClient) {
        if (!update.hasMessage()) {
            return;
        }

        Message message = update.getMessage();
        boolean hasText = message.hasText();
        boolean hasVoice = message.hasVoice();

        if (!hasText && !hasVoice) {
            return;
        }

        long chatId = message.getChatId();
        log.info("Telegram update from chatId {}", chatId);

        if (!agentProperties.getTelegram().getAllowedChatIds().isEmpty()
                && !agentProperties.getTelegram().getAllowedChatIds().contains(chatId)) {
            log.warn("Unauthorized access attempt from chatId: {}", chatId);
            sendMessage(telegramClient, chatId, "Sorry, you are not authorized to use this bot.");
            return;
        }

        if (hasText && "/start".equals(message.getText())) {
            sendMessage(telegramClient, chatId,
                "Hello! I'm your Tedhouse logistics assistant.\n\n" +
                "Tell me what materials you need at which house, e.g.:\n" +
                "  \"I need 150 m2 of plywood for house 25, truck starts at house 12\"\n\n" +
                "I can also list houses, list materials, check inventory at a house, " +
                "or check global stock totals.");
            return;
        }

        String userText;
        if (hasVoice) {
            userText = transcribeVoice(message, telegramClient, chatId);
            if (userText == null) {
                return;
            }
        } else {
            userText = message.getText();
        }

        try {
            telegramClient.execute(SendChatAction.builder()
                .chatId(chatId)
                .action("typing")
                .build());

            String conversationId = "tg-" + chatId;
            String response = agentService.chat(conversationId, userText);

            sendLongMessage(telegramClient, chatId, response);

        } catch (TelegramApiException e) {
            log.error("Telegram API error for chatId {}: {}", chatId, e.getMessage(), e);
            sendMessage(telegramClient, chatId,
                "Sorry, something went wrong processing your request. Please try again.");
        }
    }

    private String transcribeVoice(Message message, TelegramClient telegramClient, long chatId) {
        try {
            String fileId = message.getVoice().getFileId();
            org.telegram.telegrambots.meta.api.objects.File telegramFile =
                telegramClient.execute(new GetFile(fileId));
            String filePath = telegramFile.getFilePath();

            String transcript = voiceTranscriptionService.transcribe(filePath);

            if (transcript == null || transcript.isBlank()) {
                sendMessage(telegramClient, chatId,
                    "I couldn't understand the voice message. Could you try again or type your request?");
                return null;
            }

            log.info("Voice transcript for chatId {}: {}", chatId, transcript);
            return transcript;

        } catch (Exception e) {
            log.error("Failed to process voice message for chatId {}: {}", chatId, e.getMessage(), e);
            sendMessage(telegramClient, chatId,
                "Sorry, I couldn't process your voice message. Please try again or type your request.");
            return null;
        }
    }

    private void sendMessage(TelegramClient telegramClient, long chatId, String text) {
        try {
            telegramClient.execute(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chatId {}: {}", chatId, e.getMessage());
        }
    }

    private void sendLongMessage(TelegramClient telegramClient, long chatId, String text) {
        if (text == null || text.isEmpty()) {
            sendMessage(telegramClient, chatId, "Done. No additional information to share.");
            return;
        }
        int maxLength = 4096;
        for (int i = 0; i < text.length(); i += maxLength) {
            String chunk = text.substring(i, Math.min(i + maxLength, text.length()));
            sendMessage(telegramClient, chatId, chunk);
        }
    }
}
