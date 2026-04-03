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
 * <p>
 * baseRules 来自 AgentConfig，sessionRules 来自用户的 always 选择或 DB 恢复。
 */
public class PermissionService {

    private final Scanner scanner;
    private final List<Rule> baseRules;
    private final List<Rule> sessionRules;

    public PermissionService(Scanner scanner, List<Rule> baseRules) {
        this(scanner, baseRules, new ArrayList<>());
    }

    public PermissionService(Scanner scanner, List<Rule> baseRules, List<Rule> restoredSessionRules) {
        this.scanner = scanner;
        this.baseRules = List.copyOf(baseRules);
        this.sessionRules = new ArrayList<>(restoredSessionRules);
    }

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

    Action evaluate(String toolName) {
        List<Rule> allRules = new ArrayList<>(baseRules);
        allRules.addAll(sessionRules);

        for (int i = allRules.size() - 1; i >= 0; i--) {
            Rule rule = allRules.get(i);
            if (rule.matches(toolName)) {
                return rule.action();
            }
        }

        return Action.ASK;
    }

    public List<Rule> getSessionRules() {
        return List.copyOf(sessionRules);
    }

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
