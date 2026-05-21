package com.bellgado.logistics_ted.agent.telegram;

import com.bellgado.logistics_ted.agent.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
public class LogisticsLongPollingBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private final AgentProperties agentProperties;
    private final TelegramUpdateHandler updateHandler;
    private final TelegramClient telegramClient;

    public LogisticsLongPollingBot(AgentProperties agentProperties,
                                   TelegramUpdateHandler updateHandler,
                                   TelegramClient telegramClient) {
        this.agentProperties = agentProperties;
        this.updateHandler = updateHandler;
        this.telegramClient = telegramClient;
    }

    @Override
    public String getBotToken() {
        return agentProperties.getTelegram().getBotToken();
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        updateHandler.handleUpdate(update, telegramClient);
    }
}
