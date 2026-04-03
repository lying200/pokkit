package com.pokkit.permission;

/**
 * 权限模型 — 参考 OpenCode 的 Permission 系统。
 * <p>
 * 三值逻辑：ALLOW（直接放行）/ DENY（直接拒绝）/ ASK（询问用户）。
 * Rule 基于工具名匹配，支持 "*" 通配符。
 */
public class Permission {

    public enum Action { ALLOW, DENY, ASK }

    /**
     * 权限规则。toolName 支持 "*" 匹配所有工具。
     */
    public record Rule(String toolName, Action action) {

        public boolean matches(String name) {
            return toolName.equals("*") || toolName.equals(name);
        }
    }

    /** 用户对 ASK 类型权限的响应 */
    public enum Reply { ONCE, ALWAYS, REJECT }
}
