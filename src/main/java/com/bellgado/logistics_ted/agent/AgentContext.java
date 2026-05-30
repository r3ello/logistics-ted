package com.bellgado.logistics_ted.agent;

/**
 * Thread-local carrier for per-conversation metadata that needs to reach {@link LogisticsAgentTools}
 * without leaking through every Spring AI {@code @Tool} parameter. The transport adapter
 * (Telegram, future Slack/web-chat) sets the relevant ids around its {@code AgentService.chat}
 * call and clears them in a {@code finally} block; the tools read them when persisting events.
 */
public final class AgentContext {

    private static final ThreadLocal<Long> TELEGRAM_CHAT_ID = new ThreadLocal<>();

    private AgentContext() {}

    public static void setTelegramChatId(Long chatId) {
        TELEGRAM_CHAT_ID.set(chatId);
    }

    public static Long getTelegramChatId() {
        return TELEGRAM_CHAT_ID.get();
    }

    public static void clear() {
        TELEGRAM_CHAT_ID.remove();
    }
}
