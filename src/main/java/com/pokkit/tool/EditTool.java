package com.pokkit.tool;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Edit 工具 — 基于精确字符串匹配的文件编辑。
 * <p>
 * 用 old_string → new_string 做替换，不是整文件覆盖。
 * 比 write 安全得多：只改你想改的部分，其余内容不动。
 */
public class EditTool implements Tool {

    private static final JsonMapper MAPPER = JsonMapper.shared();

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String description() {
        return "Edit a file by replacing exact string matches. " +
               "Specify old_string (text to find) and new_string (replacement). " +
               "The edit fails if old_string is not found or matches multiple locations (use replace_all=true for that). " +
               "To create a new file, set old_string to empty string.";
    }

    @Override
    public String parameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "file_path": {
                      "type": "string",
                      "description": "Absolute path to the file to edit"
                    },
                    "old_string": {
                      "type": "string",
                      "description": "The exact text to find and replace. Use empty string to create a new file."
                    },
                    "new_string": {
                      "type": "string",
                      "description": "The text to replace old_string with"
                    },
                    "replace_all": {
                      "type": "boolean",
                      "description": "If true, replace all occurrences of old_string. Default: false"
                    }
                  },
                  "required": ["file_path", "old_string", "new_string"]
                }
                """;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(String argumentsJson) {
        try {
            Map<String, Object> args = MAPPER.readValue(argumentsJson, Map.class);
            String filePath = (String) args.get("file_path");
            String oldString = (String) args.get("old_string");
            String newString = (String) args.get("new_string");
            boolean replaceAll = Boolean.TRUE.equals(args.get("replace_all"));

            Path path = Path.of(filePath);

            // 空 old_string = 创建新文件
            if (oldString.isEmpty()) {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, newString);
                return "Created new file: " + filePath + " (" + newString.length() + " chars)";
            }

            if (!Files.exists(path)) {
                return "File not found: " + filePath;
            }
            if (Files.isDirectory(path)) {
                return "Path is a directory, not a file: " + filePath;
            }

            String content = Files.readString(path);

            // 检查 old_string 是否存在
            int firstIndex = content.indexOf(oldString);
            if (firstIndex == -1) {
                return "old_string not found in file. Make sure the text matches exactly, including whitespace and indentation.";
            }

            if (!replaceAll) {
                // 检查是否有多个匹配
                int secondIndex = content.indexOf(oldString, firstIndex + oldString.length());
                if (secondIndex != -1) {
                    int count = countOccurrences(content, oldString);
                    return "old_string matches " + count + " locations. " +
                           "Provide more context to make it unique, or set replace_all=true to replace all occurrences.";
                }
            }

            // 执行替换
            String newContent;
            int replacements;
            if (replaceAll) {
                replacements = countOccurrences(content, oldString);
                newContent = content.replace(oldString, newString);
            } else {
                replacements = 1;
                newContent = content.substring(0, firstIndex) + newString
                           + content.substring(firstIndex + oldString.length());
            }

            Files.writeString(path, newContent);

            // 计算变更行数
            int oldLines = oldString.split("\n", -1).length;
            int newLines = newString.split("\n", -1).length;
            String summary = replacements + " replacement(s), " +
                           "−" + oldLines + " +" + newLines + " lines";
            return "Edit applied: " + filePath + " (" + summary + ")";

        } catch (IOException e) {
            return "Error editing file: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private static int countOccurrences(String text, String target) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(target, index)) != -1) {
            count++;
            index += target.length();
        }
        return count;
    }
}
