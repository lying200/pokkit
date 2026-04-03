package com.pokkit.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息压缩器 — 参考 OpenCode 的 compaction 机制。
 * <p>
 * 两层压缩：
 * 1. Pruning：把旧的工具输出替换为占位文本
 * 2. Summarization：调 LLM 总结旧对话，用摘要替换旧消息
 * <p>
 * Token 估算用 text.length / 4，与 OpenCode 一致。
 */
public class MessageCompactor {

    /** 触发压缩的 token 阈值 */
    static final int TOKEN_LIMIT = 80_000;

    /** 保护最近这么多 token 的工具输出不被修剪 */
    private static final int PRUNE_PROTECT = 10_000;

    /** 至少节省这么多 token 才执行修剪 */
    private static final int PRUNE_MINIMUM = 5_000;

    /** 被修剪的工具输出替换为这段文本 */
    private static final String PRUNED_PLACEHOLDER = "[已清理的旧工具输出]";

    /** 摘要请求 prompt — 参考 OpenCode 的 compaction prompt */
    private static final String SUMMARIZE_PROMPT = """
            Provide a detailed summary of our conversation so far for continuing.
            Focus on:
            - **Goal**: What the user is trying to accomplish
            - **Discoveries**: Notable findings about the code or system
            - **Accomplished**: What has been completed, what's in progress, what's remaining
            - **Relevant files**: Key files and directories involved

            Be concise but preserve all information needed to continue the conversation effectively.
            """;

    private final ChatModel chatModel;

    /** 标记本轮是否执行了压缩（Repl 据此决定是否重写 DB） */
    private boolean compacted;

    public MessageCompactor(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public boolean wasCompacted() {
        return compacted;
    }

    public void resetCompacted() {
        this.compacted = false;
    }

    /**
     * 检查并执行压缩。直接修改传入的 history 列表。
     */
    public void compactIfNeeded(List<Message> history) {
        int tokens = estimateTokens(history);
        if (tokens <= TOKEN_LIMIT) return;

        System.out.println("[compaction] estimated " + tokens + " tokens, exceeds limit " + TOKEN_LIMIT);

        // 第一层：Pruning
        int saved = prune(history);
        if (saved > 0) {
            System.out.println("[compaction] pruned " + saved + " tokens");
        }

        // Pruning 后重新估算
        tokens = estimateTokens(history);
        if (tokens <= TOKEN_LIMIT) {
            compacted = true;
            return;
        }

        // 第二层：Summarization
        System.out.println("[compaction] still " + tokens + " tokens after pruning, summarizing...");
        summarize(history);
        compacted = true;

        tokens = estimateTokens(history);
        System.out.println("[compaction] after summarization: " + tokens + " tokens");
    }

    // ==================== Token 估算 ====================

    public static int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message msg : messages) {
            total += estimateTokens(msg.getText());

            if (msg instanceof AssistantMessage assistant) {
                for (var tc : assistant.getToolCalls()) {
                    total += estimateTokens(tc.arguments());
                }
            }
            if (msg instanceof ToolResponseMessage toolResp) {
                for (var resp : toolResp.getResponses()) {
                    total += estimateTokens(resp.responseData());
                }
            }
        }
        return total;
    }

    static int estimateTokens(String text) {
        return text == null ? 0 : text.length() / 4;
    }

    // ==================== Pruning ====================

    /**
     * 修剪旧的工具输出。参考 OpenCode compaction.ts 的 prune() 函数。
     * <p>
     * 从后往前遍历，跳过最近 2 轮用户交互（保护区），
     * 累计工具输出 token，超过 PRUNE_PROTECT 后把更早的输出替换为占位文本。
     *
     * @return 节省的 token 数
     */
    private int prune(List<Message> history) {
        // 找到保护区边界：从后往前数 2 个 UserMessage
        int protectBoundary = findProtectBoundary(history, 2);
        if (protectBoundary <= 0) return 0; // 对话太短，不需要修剪

        // 从保护区边界往前扫描，累计工具输出 token
        int accumulatedTokens = 0;
        int savedTokens = 0;
        boolean pastProtect = false;

        for (int i = protectBoundary - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (!(msg instanceof ToolResponseMessage toolResp)) continue;

            int msgTokens = 0;
            for (var resp : toolResp.getResponses()) {
                msgTokens += estimateTokens(resp.responseData());
            }

            accumulatedTokens += msgTokens;
            if (!pastProtect && accumulatedTokens > PRUNE_PROTECT) {
                pastProtect = true;
            }

            if (pastProtect) {
                // 替换为占位文本
                List<ToolResponse> prunedResponses = toolResp.getResponses().stream()
                        .map(resp -> new ToolResponse(resp.id(), resp.name(),
                                "{\"result\":\"" + PRUNED_PLACEHOLDER + "\"}"))
                        .toList();
                history.set(i, ToolResponseMessage.builder()
                        .responses(prunedResponses)
                        .build());
                savedTokens += msgTokens;
            }
        }

        return savedTokens >= PRUNE_MINIMUM ? savedTokens : 0;
    }

    // ==================== Summarization ====================

    /**
     * 调 LLM 总结旧消息，替换为摘要。参考 OpenCode compaction.ts 的 create() 函数。
     */
    private void summarize(List<Message> history) {
        int protectBoundary = findProtectBoundary(history, 2);
        if (protectBoundary <= 0) return;

        // 构建要总结的旧消息
        List<Message> oldMessages = new ArrayList<>(history.subList(0, protectBoundary));

        // 加上总结请求
        List<Message> summarizeRequest = new ArrayList<>();
        summarizeRequest.add(new SystemMessage(
                "You are a conversation summarizer. Summarize the following conversation for context continuity."));
        summarizeRequest.addAll(oldMessages);
        summarizeRequest.add(new UserMessage(SUMMARIZE_PROMPT));

        // 调 LLM 生成摘要（非流式，无需打印）
        String summary;
        try {
            var response = chatModel.call(new Prompt(summarizeRequest));
            summary = response.getResult().getOutput().getText();
            if (summary == null || summary.isBlank()) {
                System.out.println("[compaction] LLM returned empty summary, skipping");
                return;
            }
        } catch (Exception e) {
            System.out.println("[compaction] summarization failed: " + e.getMessage());
            return;
        }

        // 用摘要替换旧消息
        // 保留保护区内的消息
        List<Message> protectedMessages = new ArrayList<>(history.subList(protectBoundary, history.size()));

        history.clear();
        history.add(new UserMessage("[以下是之前对话的摘要]"));
        history.add(AssistantMessage.builder()
                .content(summary)
                .build());
        history.addAll(protectedMessages);
    }

    // ==================== 辅助方法 ====================

    /**
     * 从后往前找第 N 个 UserMessage 的位置，作为保护区边界。
     */
    private int findProtectBoundary(List<Message> history, int userMessageCount) {
        int count = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof UserMessage) {
                count++;
                if (count >= userMessageCount) {
                    return i;
                }
            }
        }
        return -1; // 不够 N 轮用户交互
    }
}
