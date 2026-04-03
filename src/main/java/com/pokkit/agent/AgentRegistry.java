package com.pokkit.agent;

import com.pokkit.permission.Permission.Action;
import com.pokkit.permission.Permission.Rule;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 注册表 — 管理所有可用的 Agent 配置。
 */
public class AgentRegistry {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().startsWith("win");

    private static final String ENV_HINT = IS_WINDOWS
            ? "ENVIRONMENT: You are running on Windows. The bash tool executes PowerShell commands. " +
              "Use PowerShell syntax (Get-ChildItem, Get-Content, etc.) and Windows-style paths."
            : "ENVIRONMENT: You are running on a Unix-like system. The bash tool executes Bash commands. " +
              "Use standard Unix commands and paths.";

    private final Map<String, AgentConfig> agents = new LinkedHashMap<>();

    public AgentRegistry() {
        register(coderAgent());
        register(exploreAgent());
        register(plannerAgent());
    }

    public void register(AgentConfig agent) {
        agents.put(agent.name(), agent);
    }

    public AgentConfig get(String name) {
        AgentConfig agent = agents.get(name);
        if (agent == null) {
            throw new IllegalArgumentException("Unknown agent: " + name + ". Available: " + agents.keySet());
        }
        return agent;
    }

    public AgentConfig defaultAgent() {
        return agents.values().stream()
                .filter(AgentConfig::isPrimary)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No primary agent registered"));
    }

    /** 获取所有 subagent（用于 TaskTool 的描述） */
    public Collection<AgentConfig> subagents() {
        return agents.values().stream()
                .filter(AgentConfig::isSubagent)
                .toList();
    }

    private static AgentConfig coderAgent() {
        return new AgentConfig(
                "coder",
                "primary",
                """
                You are a helpful coding assistant. You have access to tools that let you \
                read files, edit files, write files, search for files and file contents, \
                and execute shell commands. Use them to help the user with their tasks.

                When you need to explore code or run commands, use the available tools. \
                Think step by step and use tools as needed to accomplish the task.

                Prefer the edit tool over write for modifying existing files — \
                it only changes the specific text you target, which is safer than rewriting the entire file.
                Use the grep tool to search for code patterns before making changes.

                For complex tasks that involve searching large codebases or analyzing many files, \
                consider delegating to a subagent using the task tool — \
                the explore agent is optimized for fast read-only exploration.

                IMPORTANT: You are the assistant. Only generate your own response. \
                Never generate or simulate user messages.

                """ + ENV_HINT,
                List.of(
                        new Rule("read", Action.ALLOW),
                        new Rule("glob", Action.ALLOW),
                        new Rule("grep", Action.ALLOW),
                        new Rule("task", Action.ALLOW),
                        new Rule("bash", Action.ASK),
                        new Rule("write", Action.ASK),
                        new Rule("edit", Action.ASK),
                        new Rule("*", Action.ASK)
                ),
                20
        );
    }

    private static AgentConfig exploreAgent() {
        return new AgentConfig(
                "explore",
                "subagent",
                """
                You are a fast code exploration agent. Your job is to search, read, and analyze \
                code to answer questions or gather information.

                You have read-only tools: read, glob, grep, bash. \
                Use them efficiently — search first with grep/glob, then read specific files.

                Do NOT attempt to edit, write, or modify any files. \
                Focus on providing accurate, concise answers based on what you find in the code.

                """ + ENV_HINT,
                List.of(
                        new Rule("read", Action.ALLOW),
                        new Rule("glob", Action.ALLOW),
                        new Rule("grep", Action.ALLOW),
                        new Rule("bash", Action.ASK),
                        new Rule("task", Action.DENY),
                        new Rule("write", Action.DENY),
                        new Rule("edit", Action.DENY),
                        new Rule("*", Action.DENY)
                ),
                15
        );
    }

    private static AgentConfig plannerAgent() {
        return new AgentConfig(
                "planner",
                "subagent",
                """
                You are a planning and analysis agent. Your job is to analyze code, \
                understand architecture, and create plans.

                You have read-only tools: read, glob, grep. \
                You CANNOT execute commands, edit files, or write files.

                Provide clear, structured analysis and actionable plans.
                """,
                List.of(
                        new Rule("read", Action.ALLOW),
                        new Rule("glob", Action.ALLOW),
                        new Rule("grep", Action.ALLOW),
                        new Rule("*", Action.DENY)
                ),
                10
        );
    }
}
