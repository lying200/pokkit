package com.pokkit.hook;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * LLM 调用前触发。Hook 可修改消息列表（如注入 RAG 上下文、计划状态）。
 */
public final class PreReasoningEvent extends HookEvent {

    private List<Message> messages;

    public PreReasoningEvent(List<Message> messages) {
        this.messages = messages;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }
}
