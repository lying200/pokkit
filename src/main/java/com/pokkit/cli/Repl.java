package com.pokkit.cli;

import com.pokkit.agent.AgenticLoop;
import com.pokkit.tool.BashTool;
import com.pokkit.tool.GlobTool;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

@NullMarked
@Component
public class Repl implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(Repl.class);
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
        registry.register(new GlobTool());

        Scanner scanner = new Scanner(System.in);
        AgenticLoop loop = new AgenticLoop(chatModel, registry, scanner);

        List<Message> history = new ArrayList<>();

        System.out.println("Pokkit Agent (type 'exit' to quit)");
        System.out.println("==================================");

        while (true) {
            System.out.print("\n> ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;
            if (input.equalsIgnoreCase("exit")) break;

            try {
                loop.run(input, history);
            } catch (Exception e) {
                log.error("Agent error", e);
            }
        }
    }
}
