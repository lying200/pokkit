package com.pokkit.tool;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import org.jspecify.annotations.NullMarked;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GlobTool implements Tool {

    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static final int MAX_RESULTS = 200;

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "Search for files matching a glob pattern. Returns a list of matching file paths. " +
               "Use this to find files in a directory tree. Examples: '**/*.java', 'src/**/*.ts'";
    }

    @Override
    public String parameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "pattern": {
                      "type": "string",
                      "description": "Glob pattern to match files (e.g. '**/*.java')"
                    },
                    "path": {
                      "type": "string",
                      "description": "Directory to search in (absolute path). Defaults to current directory if not specified."
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
            String pattern = (String) args.get("pattern");
            String pathStr = (String) args.getOrDefault("path", System.getProperty("user.dir"));

            Path root = Path.of(pathStr);
            if (!Files.isDirectory(root)) {
                return "Not a directory: " + pathStr;
            }

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<String> matches = new ArrayList<>();

            @NullMarked
            class GlobVisitor extends SimpleFileVisitor<Path> {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= MAX_RESULTS) return FileVisitResult.TERMINATE;
                    Path relative = root.relativize(file);
                    if (matcher.matches(relative)) {
                        matches.add(relative.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    // 跳过隐藏目录和常见的大目录
                    if (name.startsWith(".") || name.equals("node_modules") || name.equals("build")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            }
            Files.walkFileTree(root, new GlobVisitor());

            if (matches.isEmpty()) {
                return "No files found matching: " + pattern;
            }

            var sb = new StringBuilder();
            for (String match : matches) {
                sb.append(match).append("\n");
            }
            if (matches.size() >= MAX_RESULTS) {
                sb.append("[truncated: showing first ").append(MAX_RESULTS).append(" results]");
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return "Error searching files: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
