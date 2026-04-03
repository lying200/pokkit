package com.pokkit.tool;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.NullMarked;

/**
 * Grep 工具 — 在文件内容中搜索正则表达式。
 * <p>
 * 纯 Java 实现（不依赖 ripgrep），遍历文件树逐行匹配。
 * 返回匹配的文件路径、行号和行内容。
 */
public class GrepTool implements Tool {

    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static final int MAX_MATCHES = 100;
    private static final int MAX_LINE_LENGTH = 200;

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "Search file contents using a regex pattern. Returns matching lines with file paths and line numbers. " +
               "Use this to find code, function definitions, references, etc. " +
               "Supports Java regex syntax.";
    }

    @Override
    public String parameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "pattern": {
                      "type": "string",
                      "description": "Regex pattern to search for"
                    },
                    "path": {
                      "type": "string",
                      "description": "Directory to search in (absolute path). Defaults to current directory."
                    },
                    "include": {
                      "type": "string",
                      "description": "File glob pattern to filter files (e.g. '*.java', '*.{ts,tsx}')"
                    }
                  },
                  "required": ["pattern"]
                }
                """;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(String argumentsJson) {
        try {
            Map<String, Object> args = MAPPER.readValue(argumentsJson, Map.class);
            String patternStr = (String) args.get("pattern");
            String pathStr = (String) args.getOrDefault("path", System.getProperty("user.dir"));
            String include = (String) args.get("include");

            Pattern regex;
            try {
                regex = Pattern.compile(patternStr);
            } catch (PatternSyntaxException e) {
                return "Invalid regex pattern: " + e.getMessage();
            }

            Path root = Path.of(pathStr);
            if (!Files.isDirectory(root)) {
                return "Not a directory: " + pathStr;
            }

            // 编译 include 过滤器
            Pattern includePattern = include != null ? globToRegex(include) : null;

            List<String> results = new ArrayList<>();
            int[] totalMatches = {0};

            @NullMarked
            class GrepVisitor extends SimpleFileVisitor<Path> {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (totalMatches[0] >= MAX_MATCHES) return FileVisitResult.TERMINATE;

                    // 跳过二进制文件（粗略判断：大于 1MB 或无法读取为文本）
                    if (attrs.size() > 1_000_000) return FileVisitResult.CONTINUE;

                    String fileName = file.getFileName().toString();

                    // include 过滤
                    if (includePattern != null && !includePattern.matcher(fileName).matches()) {
                        return FileVisitResult.CONTINUE;
                    }

                    // 跳过常见的二进制扩展名
                    if (isBinaryFile(fileName)) return FileVisitResult.CONTINUE;

                    try {
                        List<String> lines = Files.readAllLines(file);
                        Path relative = root.relativize(file);

                        for (int i = 0; i < lines.size() && totalMatches[0] < MAX_MATCHES; i++) {
                            String line = lines.get(i);
                            Matcher matcher = regex.matcher(line);
                            if (matcher.find()) {
                                totalMatches[0]++;
                                String displayLine = line.length() > MAX_LINE_LENGTH
                                        ? line.substring(0, MAX_LINE_LENGTH) + "..."
                                        : line;
                                results.add(relative + ":" + (i + 1) + ": " + displayLine);
                            }
                        }
                    } catch (Exception ignored) {
                        // 跳过无法读取的文件（二进制文件等）
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (name.startsWith(".") || name.equals("node_modules")
                            || name.equals("build") || name.equals("target")
                            || name.equals("dist") || name.equals("__pycache__")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            }

            Files.walkFileTree(root, new GrepVisitor());

            if (results.isEmpty()) {
                return "No matches found for pattern: " + patternStr;
            }

            var sb = new StringBuilder();
            sb.append("Found ").append(totalMatches[0]).append(" match(es)");
            if (totalMatches[0] >= MAX_MATCHES) {
                sb.append(" (showing first ").append(MAX_MATCHES).append(", more may exist)");
            }
            sb.append("\n\n");

            for (String result : results) {
                sb.append(result).append("\n");
            }

            return sb.toString().trim();

        } catch (IOException e) {
            return "Error searching files: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 简单的 glob → regex 转换，支持 *.java 和 *.{ts,tsx} 这类模式。
     */
    private static Pattern globToRegex(String glob) {
        var sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append(".");
                case '.' -> sb.append("\\.");
                case '{' -> sb.append("(");
                case '}' -> sb.append(")");
                case ',' -> sb.append("|");
                default -> sb.append(c);
            }
        }
        sb.append("$");
        return Pattern.compile(sb.toString());
    }

    private static boolean isBinaryFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".jar") || lower.endsWith(".class")
                || lower.endsWith(".zip") || lower.endsWith(".gz") || lower.endsWith(".tar")
                || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".gif")
                || lower.endsWith(".ico") || lower.endsWith(".svg")
                || lower.endsWith(".woff") || lower.endsWith(".woff2") || lower.endsWith(".ttf")
                || lower.endsWith(".pdf") || lower.endsWith(".exe") || lower.endsWith(".dll")
                || lower.endsWith(".so") || lower.endsWith(".dylib")
                || lower.endsWith(".lock");
    }
}
