package com.pokkit.hook;

/**
 * Hook 接口 — Agent 的核心扩展机制。
 * <p>
 * 参考 AgentScope Java 的 Hook 设计。核心循环不动，通过 Hook 注入横切关注点。
 * 优先级数字越小越先执行，前一个 Hook 的修改对后一个可见。
 */
public interface Hook {

    /** 处理事件。直接修改事件对象来影响行为。 */
    void onEvent(HookEvent event);

    /** 优先级，数字越小越先执行。默认 100。 */
    default int priority() {
        return 100;
    }
}
