package com.pokkit.tool;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class ReadTool implements Tool {

    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static final int MAX_LINES = 2000;

    @Override
    public String name() {
        return "read";
    }

    @Override
    public String description() {
        return "Read the contents of a file. Returns the file content with line numbers. " +
               "Use this to understand code, check configurations, etc.";
    }

    @Override
    public String parameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "Absolute path to the file to read"
                    }
                  },
                  "required": ["path"]
                }
                """;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(String argumentsJson) {
        try {
            Map<String, Object> args = MAPPER.readValue(argumentsJson, Map.class);
            String path = (String) args.get("path");

            Path filePath = Path.of(path);
            if (!Files.exists(filePath)) {
                return "File not found: " + path;
            }
            if (Files.isDirectory(filePath)) {
                return "Path is a directory, not a file: " + path;
            }

            var lines = Files.readAllLines(filePath);
            var sb = new StringBuilder();
            int limit = Math.min(lines.size(), MAX_LINES);
            for (int i = 0; i < limit; i++) {
                sb.append(i + 1).append("\t").append(lines.get(i)).append("\n");
            }
            if (lines.size() > MAX_LINES) {
                sb.append("\n[truncated: showing first ").append(MAX_LINES)
                  .append(" of ").append(lines.size()).append(" lines]");
            }
            return sb.toString();
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
