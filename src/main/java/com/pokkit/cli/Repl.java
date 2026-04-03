package com.pokkit.cli;

import com.pokkit.agent.AgenticLoop;
import com.pokkit.session.Session;
import com.pokkit.session.SessionRepository;
import com.pokkit.tool.BashTool;
import com.pokkit.tool.EditTool;
import com.pokkit.tool.GlobTool;
import com.pokkit.tool.GrepTool;
import com.pokkit.tool.ReadTool;
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
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BashTool());
        registry.register(new ReadTool());
        registry.register(new WriteTool());
        registry.register(new EditTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());

        Scanner scanner = new Scanner(System.in);
        AgenticLoop loop = new AgenticLoop(chatModel, registry, scanner);

        // 打开数据库
        String dbPath = System.getProperty("pokkit.db",
                Path.of(System.getProperty("user.home"), ".pokkit", "data.db").toString());
        SessionRepository repo = new SessionRepository(dbPath);

        // 恢复最近会话或新建
        Session[] current = new Session[1];
        List<Message> history = new ArrayList<>();

        current[0] = repo.lastSession().orElseGet(() -> repo.createSession("新会话"));
        history.addAll(repo.loadMessages(current[0].id()));

        System.out.println("Pokkit Agent (输入 /help 查看命令，exit 退出)");
        System.out.println("会话: " + current[0].title());
        System.out.println("历史: " + history.size() + " 条消息已加载");
        System.out.println("==================================");

        while (true) {
            System.out.print("\n> ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;
            if (input.equalsIgnoreCase("exit")) break;

            // 命令分发
            if (input.startsWith("/")) {
                current[0] = handleCommand(input, repo, current[0], history);
                continue;
            }

            int beforeSize = history.size();
            try {
                loop.run(input, history);
            } catch (Exception e) {
                log.error("Agent error", e);
            }

            // 保存：如果发生了压缩，重写整个消息历史；否则增量保存
            if (loop.getCompactor().wasCompacted()) {
                repo.replaceMessages(current[0].id(), history);
                loop.getCompactor().resetCompacted();
            } else {
                for (int i = beforeSize; i < history.size(); i++) {
                    repo.saveMessage(current[0].id(), i, history.get(i));
                }
            }
            repo.touchSession(current[0].id());

            // 第一条用户消息时自动生成标题
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
                                  Session current, List<Message> history) {
        String cmd = input.split("\\s+")[0].toLowerCase();
        return switch (cmd) {
            case "/new" -> {
                history.clear();
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
        // 截断在词边界
        int cutoff = title.lastIndexOf(' ', 77);
        if (cutoff <= 0) cutoff = 77;
        return title.substring(0, cutoff) + "...";
    }
}
