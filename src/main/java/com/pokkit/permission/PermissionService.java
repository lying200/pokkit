package com.pokkit.permission;

import com.pokkit.permission.Permission.Action;
import com.pokkit.permission.Permission.Reply;
import com.pokkit.permission.Permission.Rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * 权限服务 — 参考 OpenCode 的 Permission.evaluate() + Permission.ask()。
 * <p>
 * 规则评估用 "last match wins"（与 OpenCode 一致），
 * 用户选 "always" 后添加 session 级规则，后续不再询问。
 */
public class PermissionService {

    /** 内置默认规则：读操作 allow，写操作 ask */
    private static final List<Rule> DEFAULTS = List.of(
            new Rule("read", Action.ALLOW),
            new Rule("glob", Action.ALLOW),
            new Rule("grep", Action.ALLOW),
            new Rule("bash", Action.ASK),
            new Rule("write", Action.ASK),
            new Rule("edit", Action.ASK),
            new Rule("*", Action.ASK)
    );

    private final Scanner scanner;
    private final List<Rule> sessionRules;

    public PermissionService(Scanner scanner) {
        this(scanner, new ArrayList<>());
    }

    public PermissionService(Scanner scanner, List<Rule> restoredRules) {
        this.scanner = scanner;
        this.sessionRules = new ArrayList<>(restoredRules);
    }

    /**
     * 检查工具是否有权限执行。
     *
     * @return CheckResult — ALLOWED / DENIED / REJECTED
     */
    public CheckResult check(String toolName, String argsPreview) {
        Action action = evaluate(toolName);

        return switch (action) {
            case ALLOW -> CheckResult.ALLOWED;
            case DENY -> {
                System.out.println("[permission] " + toolName + " is denied by rule");
                yield CheckResult.DENIED;
            }
            case ASK -> {
                Reply reply = askUser(toolName, argsPreview);
                yield switch (reply) {
                    case ONCE -> CheckResult.ALLOWED;
                    case ALWAYS -> {
                        sessionRules.add(new Rule(toolName, Action.ALLOW));
                        System.out.println("[permission] " + toolName + " will be auto-approved for this session");
                        yield CheckResult.ALLOWED;
                    }
                    case REJECT -> CheckResult.REJECTED;
                };
            }
        };
    }

    /**
     * 规则评估 — last match wins，参考 OpenCode 的 evaluate()。
     */
    Action evaluate(String toolName) {
        // 合并 defaults + sessionRules，后者优先级更高
        List<Rule> allRules = new ArrayList<>(DEFAULTS);
        allRules.addAll(sessionRules);

        // findLast：从后往前找第一个匹配的
        for (int i = allRules.size() - 1; i >= 0; i--) {
            Rule rule = allRules.get(i);
            if (rule.matches(toolName)) {
                return rule.action();
            }
        }

        return Action.ASK; // fallback
    }

    /**
     * 获取当前 session 的 approved 规则（用于持久化）。
     */
    public List<Rule> getSessionRules() {
        return List.copyOf(sessionRules);
    }

    /**
     * 重置 session 规则（新建会话时调用）。
     */
    public void resetSessionRules() {
        sessionRules.clear();
    }

    private Reply askUser(String toolName, String argsPreview) {
        System.out.print("[permission] allow " + toolName + ": " + argsPreview + "? (y=once/a=always/n=reject): ");
        System.out.flush();
        String answer = scanner.nextLine().trim().toLowerCase();
        return switch (answer) {
            case "y", "yes" -> Reply.ONCE;
            case "a", "always" -> Reply.ALWAYS;
            default -> {
                if (!answer.equals("n") && !answer.equals("no") && !answer.isEmpty()) {
                    System.out.println("[permission] unrecognized input '" + answer + "', treating as reject");
                }
                yield Reply.REJECT;
            }
        };
    }

    public enum CheckResult { ALLOWED, DENIED, REJECTED }
}
