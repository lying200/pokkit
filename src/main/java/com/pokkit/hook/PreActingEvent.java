package com.pokkit.hook;

/**
 * 工具执行前触发。Hook 可跳过执行并返回自定义结果（如权限拒绝）。
 */
public final class PreActingEvent extends HookEvent {

    private final String toolName;
    private final String arguments;
    private final String argsPreview;
    private boolean skipped;
    private String skipResult;

    public PreActingEvent(String toolName, String arguments, String argsPreview) {
        this.toolName = toolName;
        this.arguments = arguments;
        this.argsPreview = argsPreview;
    }

    /** 跳过工具执行，使用自定义结果替代 */
    public void skipTool(String result) {
        this.skipped = true;
        this.skipResult = result;
    }

    public String toolName() {
        return toolName;
    }

    public String arguments() {
        return arguments;
    }

    public String argsPreview() {
        return argsPreview;
    }

    public boolean isSkipped() {
        return skipped;
    }

    public String getSkipResult() {
        return skipResult;
    }
}
