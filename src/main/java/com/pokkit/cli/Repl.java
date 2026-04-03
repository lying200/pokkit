package com.pokkit.cli;

import com.pokkit.agent.AgentConfig;
import com.pokkit.agent.AgentRegistry;
import com.pokkit.agent.AgenticLoop;
import com.pokkit.permission.Permission.Rule;
import com.pokkit.permission.PermissionService;
import com.pokkit.session.Session;
import com.pokkit.session.SessionRepository;
import com.pokkit.tool.BashTool;
import com.pokkit.tool.EditTool;
import com.pokkit.tool.GlobTool;
import com.pokkit.tool.GrepTool;
import com.pokkit.tool.ReadTool;
import com.pokkit.tool.TaskTool;
import com.pokkit.tool.ToolRegistry;
import com.pokkit.tool.WriteTool;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

@NullMarked
@Component
public class Repl implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(Repl.class);
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ChatModel chatModel;

    public Repl(@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public void run(String... args) {
        Scanner scanner = new Scanner(System.in);

        // Agent 注册表
        AgentRegistry agentRegistry = new AgentRegistry();
        AgentConfig primaryAgent = agentRegistry.defaultAgent();

        // 工具注册（包括 TaskTool）
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new BashTool());
        toolRegistry.register(new ReadTool());
        toolRegistry.register(new WriteTool());
        toolRegistry.register(new EditTool());
        toolRegistry.register(new GlobTool());
        toolRegistry.register(new GrepTool());
        toolRegistry.register(new TaskTool(chatModel, agentRegistry, toolRegistry, scanner));

        // 打开数据库
        String dbPath = System.getProperty("pokkit.db",
                Path.of(System.getProperty("user.home"), ".pokkit", "data.db").toString());
        SessionRepository repo = new SessionRepository(dbPath);

        // 恢复最近会话或新建
        Session[] current = new Session[1];
        current[0] = repo.lastSession().orElseGet(() -> repo.createSession("新会话"));
        List<Message> history = new ArrayList<>(repo.loadMessages(current[0].id()));

        // 恢复 session 级权限规则，base rules 来自主 Agent 配置
        List<Rule> restoredRules = repo.loadPermissions(current[0].id());
        PermissionService permissionService = new PermissionService(
                scanner, primaryAgent.permissionRules(), restoredRules);

        AgenticLoop loop = new AgenticLoop(chatModel, toolRegistry, permissionService, primaryAgent);

        System.out.println("Pokkit Agent [" + primaryAgent.name() + "] (输入 /help 查看命令，exit 退出)");
        System.out.println("会话: " + current[0].title());
        System.out.println("历史: " + history.size() + " 条消息已加载");
        if (!restoredRules.isEmpty()) {
            System.out.println("权限: " + restoredRules.size() + " 条 always 规则已恢复");
        }
        System.out.println("==================================");

        while (true) {
            System.out.print("\n> ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;
            if (input.equalsIgnoreCase("exit")) break;

            if (input.startsWith("/")) {
                current[0] = handleCommand(input, repo, current[0], history,
                        permissionService);
                continue;
            }

            int beforeSize = history.size();
            try {
                loop.run(input, history);
            } catch (Exception e) {
                log.error("Agent error", e);
            }

            // 保存
            if (loop.getCompactor().wasCompacted()) {
                repo.replaceMessages(current[0].id(), history);
                loop.getCompactor().resetCompacted();
            } else {
                for (int i = beforeSize; i < history.size(); i++) {
                    repo.saveMessage(current[0].id(), i, history.get(i));
                }
            }

            repo.savePermissions(current[0].id(), permissionService.getSessionRules());
            repo.touchSession(current[0].id());

            if (beforeSize == 0) {
                String title = generateTitle(input);
                repo.updateTitle(current[0].id(), title);
                current[0] = new Session(current[0].id(), title,
                        current[0].createdAt(), current[0].updatedAt());
            }
        }

        repo.close();
    }

    private Session handleCommand(String input, SessionRepository repo,
                                  Session current, List<Message> history,
                                  PermissionService permissionService) {
        String cmd = input.split("\\s+")[0].toLowerCase();
        return switch (cmd) {
            case "/new" -> {
                history.clear();
                permissionService.resetSessionRules();
                Session s = repo.createSession("新会话");
                System.out.println("已创建新会话");
                yield s;
            }
            case "/history" -> {
                List<Session> sessions = repo.listSessions();
                if (sessions.isEmpty()) {
                    System.out.println("暂无会话记录");
                } else {
                    System.out.println("会话列表:");
                    for (Session s : sessions) {
                        String marker = s.id().equals(current.id()) ? " ← 当前" : "";
                        int count = repo.messageCount(s.id());
                        System.out.printf("  %s  %-40s  %s  %d 条消息%s%n",
                                s.id().substring(0, 8),
                                s.title().length() > 40 ? s.title().substring(0, 37) + "..." : s.title(),
                                TIME_FMT.format(s.updatedAt()),
                                count, marker);
                    }
                }
                yield current;
            }
            case "/clear" -> {
                history.clear();
                permissionService.resetSessionRules();
                repo.deleteAllSessions();
                Session s = repo.createSession("新会话");
                System.out.println("已清除所有会话，重新开始");
                yield s;
            }
            case "/help" -> {
                System.out.println("""
                        命令:
                          /new      创建新会话
                          /history  查看所有会话
                          /clear    清除所有会话数据
                          /help     显示此帮助
                          exit      退出 Pokkit""");
                yield current;
            }
            default -> {
                System.out.println("未知命令: " + cmd + " (输入 /help 查看可用命令)");
                yield current;
            }
        };
    }

    private static String generateTitle(String firstMessage) {
        String title = firstMessage.replaceAll("[\\n\\r]+", " ").trim();
        if (title.length() <= 80) return title;
        int cutoff = title.lastIndexOf(' ', 77);
        if (cutoff <= 0) cutoff = 77;
        return title.substring(0, cutoff) + "...";
    }
}
