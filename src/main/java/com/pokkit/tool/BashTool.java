package com.pokkit.tool;

import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class BashTool implements Tool {

    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static final long TIMEOUT_SECONDS = 30;
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().startsWith("win");

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return IS_WINDOWS
                ? "Execute a PowerShell command and return its output (stdout + stderr). " +
                  "Use this to run system commands, check file status, run tests, etc. " +
                  "Commands run in PowerShell on Windows — use PowerShell syntax (e.g. Get-ChildItem, not ls -la)."
                : "Execute a shell command and return its output (stdout + stderr). " +
                  "Use this to run system commands, check file status, run tests, etc.";
    }

    @Override
    public String parameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "command": {
                      "type": "string",
                      "description": "The shell command to execute"
                    }
                  },
                  "required": ["command"]
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
            String command = (String) args.get("command");

            ProcessBuilder pb = IS_WINDOWS
                    ? new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", command)
                    : new ProcessBuilder("bash", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (var reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return output + "\n[TIMEOUT after " + TIMEOUT_SECONDS + "s]";
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return output + "\n[exit code: " + exitCode + "]";
            }
            return output.isEmpty()
                    ? "Command executed successfully with exit code 0, but produced no output."
                    : output;
        } catch (Exception e) {
            return "Error executing command: " + e.getMessage();
        }
    }
}
