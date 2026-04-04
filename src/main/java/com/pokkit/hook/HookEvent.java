package com.pokkit.hook;

/**
 * Hook 事件基类。所有事件都可以通过 stopAgent() 终止循环。
 */
public abstract sealed class HookEvent
        permits PreReasoningEvent, PostReasoningEvent, PreActingEvent, PostActingEvent {

    private boolean stopRequested;

    /** 请求终止 Agent 循环 */
    public void stopAgent() {
        this.stopRequested = true;
    }

    public boolean isStopRequested() {
        return stopRequested;
    }
}
