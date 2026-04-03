package com.pokkit.agent;

import com.pokkit.tool.Tool;
import com.pokkit.tool.ToolRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agentic Loop — 整个项目的核心。
 * <p>
 * 流式 while(true) 循环：
 * 1. 流式调用 LLM，逐 token 打印到终端
 * 2. 流结束后检查聚合结果：有 tool call → 确认 → 执行 → 继续；没有 → break
 */
public class AgenticLoop {

    private static final int DEFAULT_MAX_STEPS = 20;
    private static final int DOOM_LOOP_THRESHOLD = 3;
    private static final int MAX_OUTPUT_LENGTH = 10000;
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().startsWith("win");

    private static final String SYSTEM_PROMPT = """
            You are a helpful coding assistant. You have access to tools that let you \
            read files, edit files, write files, search for files and file contents, \
            and execute shell commands. Use them to help the user with their tasks.

            When you need to explore code or run commands, use the available tools. \
            Think step by step and use tools as needed to accomplish the task.

            Prefer the edit tool over write for modifying existing files — \
            it only changes the specific text you target, which is safer than rewriting the entire file.
            Use the grep tool to search for code patterns, function definitions, or references before making changes.

            """ + (IS_WINDOWS
            ? "ENVIRONMENT: You are running on Windows. The bash tool executes PowerShell commands. " +
              "Use PowerShell syntax (Get-ChildItem, Get-Content, etc.) and Windows-style paths (e.g. D:\\path\\to\\file)."
            : "ENVIRONMENT: You are running on a Unix-like system. The bash tool executes Bash commands. " +
              "Use standard Unix commands and paths.");

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final Scanner scanner;
    private final MessageCompactor compactor;
    private final int maxSteps;

    public AgenticLoop(ChatModel chatModel, ToolRegistry toolRegistry, Scanner scanner) {
        this(chatModel, toolRegistry, scanner, DEFAULT_MAX_STEPS);
    }

    public AgenticLoop(ChatModel chatModel, ToolRegistry toolRegistry, Scanner scanner, int maxSteps) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.scanner = scanner;
        this.compactor = new MessageCompactor(chatModel);
        this.maxSteps = maxSteps;
    }

    public MessageCompactor getCompactor() {
        return compactor;
    }

    public void run(String userInput, List<Message> conversationHistory) {
        conversationHistory.add(new UserMessage(userInput));

        List<ToolCallback> toolCallbacks = toolRegistry.all().stream()
                .map(this::toToolCallback)
                .toList();

        var options = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .toolCallbacks(toolCallbacks)
                .build();

        LinkedList<String> recentToolCalls = new LinkedList<>();

        int step = 0;
        while (true) {
            step++;

            if (step > maxSteps) {
                System.out.println("\n[loop] max steps reached (" + maxSteps + "), stopping");
                return;
            }

            // 压缩检查：在发给 LLM 之前确保不超限
            compactor.compactIfNeeded(conversationHistory);

            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(SYSTEM_PROMPT));
            messages.addAll(conversationHistory);

            // 流式调用 LLM，逐 token 打印
            ChatResponse response = streamAndPrint(new Prompt(messages, options));
            if (response == null) {
                System.out.println("\n[loop] empty response from LLM, stopping");
                return;
            }

            var generation = response.getResult();
            if (generation == null) {
                System.out.println("\n[loop] empty generation, stopping");
                return;
            }
            AssistantMessage assistant = generation.getOutput();
            conversationHistory.add(assistant);

            if (!response.hasToolCalls()) {
                System.out.println();
                return;
            }

            System.out.println();
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                // Doom loop 检测
                String signature = toolCall.name() + ":" + toolCall.arguments();
                recentToolCalls.addLast(signature);
                if (recentToolCalls.size() > DOOM_LOOP_THRESHOLD) {
                    recentToolCalls.removeFirst();
                }
                if (recentToolCalls.size() == DOOM_LOOP_THRESHOLD
                        && recentToolCalls.stream().distinct().count() == 1) {
                    System.out.println("[doom loop] same tool call repeated " + DOOM_LOOP_THRESHOLD + " times, stopping");
                    return;
                }

                String argsPreview = toolCall.arguments().length() <= 120
                        ? toolCall.arguments() : toolCall.arguments().substring(0, 120) + "...";
                System.out.println("[tool] " + toolCall.name() + " " + argsPreview);

                String output;
                try {
                    Tool tool = toolRegistry.get(toolCall.name());
                    if (tool.requiresConfirmation() && !askConfirmation(toolCall.name(), argsPreview)) {
                        output = "User rejected this tool call. Adjust your approach or ask the user what they'd prefer.";
                    } else {
                        output = tool.execute(toolCall.arguments());
                    }
                } catch (Exception e) {
                    output = "Error: " + e.getMessage();
                }

                if (output.length() > MAX_OUTPUT_LENGTH) {
                    output = output.substring(0, MAX_OUTPUT_LENGTH) + "\n[output truncated]";
                }

                // 打印工具执行结果
                String outputPreview = output.length() <= 500
                        ? output : output.substring(0, 500) + "\n... [truncated, total " + output.length() + " chars]";
                System.out.println("\n[result] " + toolCall.name());
                System.out.println(outputPreview);
                System.out.println();
                String jsonOutput = "{\"result\":" + quoteJson(output) + "}";
                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolCall.name(), jsonOutput));
            }

            conversationHistory.add(ToolResponseMessage.builder()
                    .responses(toolResponses)
                    .build());
        }
    }

    private ChatResponse streamAndPrint(Prompt prompt) {
        AtomicReference<ChatResponse> aggregated = new AtomicReference<>();

        var flux = chatModel.stream(prompt);
        flux = new MessageAggregator().aggregate(flux, aggregated::set);

        flux.doOnNext(chunk -> {
            if (chunk.getResult() != null) {
                String text = chunk.getResult().getOutput().getText();
                if (text != null) {
                    System.out.print(text);
                }
            }
        }).blockLast();

        return aggregated.get();
    }

    private boolean askConfirmation(String toolName, String argsPreview) {
        System.out.print("[confirm] allow " + toolName + ": " + argsPreview + "? (y/n): ");
        System.out.flush();
        String answer = scanner.nextLine().trim().toLowerCase();
        boolean allowed = answer.equals("y") || answer.equals("yes");
        if (!allowed && !answer.equals("n") && !answer.equals("no")) {
            System.out.println("[confirm] unrecognized input '" + answer + "', treating as reject");
        }
        return allowed;
    }

    private ToolCallback toToolCallback(Tool tool) {
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(tool.parameterSchema())
                .build();

        @NullMarked
        class ToolCallbackAdapter implements ToolCallback {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return tool.execute(toolInput);
            }
        }
        return new ToolCallbackAdapter();
    }

    private static String quoteJson(String text) {
        var sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
