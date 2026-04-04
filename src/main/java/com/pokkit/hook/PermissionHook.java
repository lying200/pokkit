package com.pokkit.hook;

import com.pokkit.permission.PermissionService;

/**
 * 权限 Hook — 在工具执行前检查权限。
 * <p>
 * 从 AgenticLoop 的硬编码逻辑迁移而来，优先级 50（在普通 Hook 之前执行）。
 */
public class PermissionHook implements Hook {

    private final PermissionService permissionService;

    public PermissionHook(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public void onEvent(HookEvent event) {
        if (event instanceof PreActingEvent e) {
            var result = permissionService.check(e.toolName(), e.argsPreview());
            switch (result) {
                case DENIED -> e.skipTool("Permission denied for this tool call by rule. Try a different approach.");
                case REJECTED -> e.skipTool("User rejected this tool call. Adjust your approach or ask the user what they'd prefer.");
                case ALLOWED -> { /* 放行 */ }
            }
        }
    }

    @Override
    public int priority() {
        return 50; // 高优先级，在其他 Hook 之前执行
    }
}
