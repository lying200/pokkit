package com.pokkit.tool;

import com.pokkit.agent.AgentConfig;
import com.pokkit.agent.AgentRegistry;
import com.pokkit.agent.AgenticLoop;
import com.pokkit.permission.PermissionService;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;

/**
 * Task Tool — 多 Agent 编排的核心。
 * <p>
 * 就是一个普通的 Tool，LLM 自己决定什么时候委派子任务。
 * 执行时创建子 Agent 的 AgenticLoop，独立消息历史，跑完返回结果。
 */
public class TaskTool implements Tool {

    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final ChatModel chatModel;
    private final AgentRegistry agentRegistry;
    private final ToolRegistry toolRegistry;
    private final Scanner scanner;

    public TaskTool(ChatModel chatModel, AgentRegistry agentRegistry,
                    ToolRegistry toolRegistry, Scanner scanner) {
        this.chatModel = chatModel;
        this.agentRegistry = agentRegistry;
        this.toolRegistry = toolRegistry;
        this.scanner = scanner;
    }

    @Override
    public String name() {
        return "task";
    }

    @Override
    public String description() {
        Collection<AgentConfig> subagents = agentRegistry.subagents();
        String agentList = subagents.stream()
                .map(a -> a.name() + " — " + describeAgent(a))
                .collect(Collectors.joining("\n  "));

        return "Delegate a task to a specialized subagent. " +
               "The subagent runs independently with its own context and tools, " +
               "then returns its findings.\n\nAvailable subagents:\n  " + agentList;
    }

    @Override
    public String parameterSchema() {
        Collection<AgentConfig> subagents = agentRegistry.subagents();
        String names = subagents.stream()
                .map(a -> "\"" + a.name() + "\"")
                .collect(Collectors.joining(", "));

        return """
                {
                  "type": "object",
                  "properties": {
                    "agent": {
                      "type": "string",
                      "description": "The subagent to delegate to",
                      "enum": [%s]
                    },
                    "prompt": {
                      "type": "string",
                      "description": "Detailed instruction for the subagent. Be specific about what you want."
                    }
                  },
                  "required": ["agent", "prompt"]
                }
                """.formatted(names);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(String argumentsJson) {
        try {
            Map<String, Object> args = MAPPER.readValue(argumentsJson, Map.class);
            String agentName = (String) args.get("agent");
            String prompt = (String) args.get("prompt");

            AgentConfig subagentConfig = agentRegistry.get(agentName);
            if (!subagentConfig.isSubagent()) {
                return "Error: " + agentName + " is not a subagent, cannot be delegated to.";
            }

            System.out.println("[task] delegating to " + agentName + ": " + prompt);

            // 为子 Agent 构建受限的 ToolRegistry（排除 task 工具本身，防止嵌套）
            ToolRegistry childTools = new ToolRegistry();
            for (Tool tool : toolRegistry.all()) {
                if (!tool.name().equals("task")) {
                    childTools.register(tool);
                }
            }

            // 子 Agent 用自己的权限规则，不继承父 Agent 的 session 级 always 规则
            PermissionService childPermission = new PermissionService(scanner, subagentConfig.permissionRules());

            // 独立的消息历史
            List<Message> childHistory = new ArrayList<>();

            AgenticLoop childLoop = new AgenticLoop(chatModel, childTools, childPermission, subagentConfig);
            childLoop.run(prompt, childHistory);

            // 提取子 Agent 的最终文本回复
            String result = extractLastAssistantText(childHistory);
            if (result == null || result.isBlank()) {
                result = "[subagent " + agentName + " completed but produced no text output]";
            }

            System.out.println("[task] " + agentName + " completed");
            return result;
        } catch (Exception e) {
            return "Error delegating task: " + e.getMessage();
        }
    }

    private static String extractLastAssistantText(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg instanceof org.springframework.ai.chat.messages.AssistantMessage assistant) {
                String text = assistant.getText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static String describeAgent(AgentConfig agent) {
        return switch (agent.name()) {
            case "explore" -> "fast read-only code exploration (grep, glob, read, bash)";
            case "planner" -> "read-only analysis and planning (read, glob, grep only)";
            default -> agent.mode() + " agent";
        };
    }
}
