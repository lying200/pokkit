package com.pokkit.hook;

/**
 * 工具执行后触发。Hook 可修改工具输出结果。
 */
public final class PostActingEvent extends HookEvent {

    private final String toolName;
    private String output;

    public PostActingEvent(String toolName, String output) {
        this.toolName = toolName;
        this.output = output;
    }

    public String toolName() {
        return toolName;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }
}
