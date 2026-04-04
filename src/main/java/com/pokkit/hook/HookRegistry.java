package com.pokkit.hook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Hook 注册表 — 管理 Hook 列表，按优先级排序执行。
 */
public class HookRegistry {

    private final List<Hook> hooks = new ArrayList<>();

    public void add(Hook hook) {
        hooks.add(hook);
        hooks.sort(Comparator.comparingInt(Hook::priority));
    }

    /**
     * 按优先级依次触发所有 Hook。
     * 如果某个 Hook 调用了 event.stopAgent()，后续 Hook 仍会执行（让清理类 Hook 有机会处理），
     * 但调用方应在 fire() 返回后检查 event.isStopRequested()。
     */
    public void fire(HookEvent event) {
        for (Hook hook : hooks) {
            hook.onEvent(event);
        }
    }
}
