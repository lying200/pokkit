package com.pokkit.agent;

import com.pokkit.permission.PermissionService;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agentic Loop — 整个项目的核心。
 * <p>
 * 接收 AgentConfig 来决定 system prompt、权限规则、最大步数。
 * 同一个循环 + 不同配置 = 不同 Agent 行为。
 */
public class AgenticLoop {

    private static final int DOOM_LOOP_THRESHOLD = 3;
    private static final int MAX_OUTPUT_LENGTH = 10000;

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final PermissionService permissionService;
    private final MessageCompactor compactor;
    private final AgentConfig agentConfig;

    public AgenticLoop(ChatModel chatModel, ToolRegistry toolRegistry,
                       PermissionService permissionService, AgentConfig agentConfig) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.permissionService = permissionService;
        this.compactor = new MessageCompactor(chatModel);
        this.agentConfig = agentConfig;
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

            if (step > agentConfig.maxSteps()) {
                System.out.println("\n[" + agentConfig.name() + "] max steps reached (" + agentConfig.maxSteps() + "), stopping");
                return;
            }

            compactor.compactIfNeeded(conversationHistory);

            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(agentConfig.systemPrompt()));
            messages.addAll(conversationHistory);

            ChatResponse response = streamAndPrint(new Prompt(messages, options));
            if (response == null) {
                System.out.println("\n[" + agentConfig.name() + "] empty response from LLM, stopping");
                return;
            }

            var generation = response.getResult();
            if (generation == null) {
                System.out.println("\n[" + agentConfig.name() + "] empty generation, stopping");
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
                System.out.println("[" + agentConfig.name() + ":tool] " + toolCall.name() + " " + argsPreview);

                String output;
                try {
                    Tool tool = toolRegistry.get(toolCall.name());
                    var permResult = permissionService.check(toolCall.name(), argsPreview);
                    if (permResult == PermissionService.CheckResult.DENIED) {
                        output = "Permission denied for this tool call by rule. Try a different approach.";
                    } else if (permResult == PermissionService.CheckResult.REJECTED) {
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

    static String quoteJson(String text) {
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
