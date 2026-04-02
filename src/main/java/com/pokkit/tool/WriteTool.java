package com.pokkit.tool;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class WriteTool implements Tool {

    private static final JsonMapper MAPPER = JsonMapper.shared();

    @Override
    public String name() {
        return "write";
    }

    @Override
    public String description() {
        return "Write content to a file. Creates the file and parent directories if they don't exist. " +
               "Overwrites the file if it already exists. Use this to create or modify files.";
    }

    @Override
    public String parameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "Absolute path to the file to write"
                    },
                    "content": {
                      "type": "string",
                      "description": "The content to write to the file"
                    }
                  },
                  "required": ["path", "content"]
                }
                """;
    }

    @Override
    public boolean requiresConfirmation() { return true; }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(String argumentsJson) {
        try {
            Map<String, Object> args = MAPPER.readValue(argumentsJson, Map.class);
            String path = (String) args.get("path");
            String content = (String) args.get("content");

            Path filePath = Path.of(path);
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(filePath, content);
            return "Successfully wrote " + content.length() + " characters to " + path;
        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
