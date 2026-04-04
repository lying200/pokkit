package com.pokkit.hook;

import org.springframework.ai.chat.model.ChatResponse;

/**
 * LLM 返回后触发。Hook 可检查响应，决定是否终止循环。
 */
public final class PostReasoningEvent extends HookEvent {

    private final ChatResponse response;

    public PostReasoningEvent(ChatResponse response) {
        this.response = response;
    }

    public ChatResponse getResponse() {
        return response;
    }
}
