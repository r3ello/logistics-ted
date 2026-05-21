package com.bellgado.logistics_ted.agent;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class VoiceTranscriptionService {

    private final RestClient restClient;
    private final String botToken;
    private final String openAiApiKey;

    VoiceTranscriptionService(RestClient.Builder restClientBuilder,
                              AgentProperties agentProperties,
                              @Value("${spring.ai.openai.api-key}") String openAiApiKey) {
        this.restClient = restClientBuilder.build();
        this.botToken = agentProperties.getTelegram().getBotToken();
        this.openAiApiKey = openAiApiKey;
    }

    public String transcribe(String filePath) {
        byte[] audioBytes = downloadFile(filePath);
        return callWhisper(audioBytes);
    }

    private byte[] downloadFile(String filePath) {
        String url = "https://api.telegram.org/file/bot" + botToken + "/" + filePath;
        log.debug("Downloading voice file from Telegram: {}", filePath);

        return restClient.get()
                .uri(url)
                .retrieve()
                .body(byte[].class);
    }

    @SuppressWarnings("unchecked")
    private String callWhisper(byte[] audioBytes) {
        log.debug("Sending {} bytes to Whisper API", audioBytes.length);

        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("file", new ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() {
                return "voice.ogg";
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);
        bodyBuilder.part("model", "whisper-1");

        Map<String, String> response = restClient.post()
                .uri("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer " + openAiApiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(bodyBuilder.build())
                .retrieve()
                .body(Map.class);

        String text = response != null ? response.get("text") : null;
        log.debug("Whisper transcript: {}", text);
        return text;
    }
}
