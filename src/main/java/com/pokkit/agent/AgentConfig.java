package com.pokkit.agent;

import com.pokkit.permission.Permission.Rule;

import java.util.List;

/**
 * Agent 配置 — 定义一个 Agent 的全部身份。
 * <p>
 * 同一个 AgenticLoop + 不同的 AgentConfig = 完全不同的 Agent 行为。
 */
public record AgentConfig(
        String name,
        String mode,
        String systemPrompt,
        List<Rule> permissionRules,
        int maxSteps
) {
    public boolean isPrimary() {
        return "primary".equals(mode);
    }

    public boolean isSubagent() {
        return "subagent".equals(mode);
    }
}
