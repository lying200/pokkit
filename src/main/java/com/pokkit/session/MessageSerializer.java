package com.pokkit.session;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI Message ↔ 数据库列 的转换。
 * <p>
 * 把多态的 Message 类型拆成简单的字符串列：role, content, tool_calls, tool_responses。
 * 反序列化时根据 role 重建对应的 Message 子类。
 */
public class MessageSerializer {

    private static final JsonMapper MAPPER = JsonMapper.shared();

    // ==================== 序列化（Message → DB 列）====================

    public String role(Message message) {
        return switch (message) {
            case UserMessage _ -> "USER";
            case AssistantMessage _ -> "ASSISTANT";
            case ToolResponseMessage _ -> "TOOL_RESPONSE";
            default -> throw new IllegalArgumentException("Unsupported message type: " + message.getClass());
        };
    }

    public String content(Message message) {
        return message.getText();
    }

    public String toolCallsJson(Message message) {
        if (!(message instanceof AssistantMessage assistant)) return null;
        List<AssistantMessage.ToolCall> toolCalls = assistant.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) return null;

        List<Map<String, String>> list = toolCalls.stream()
                .map(tc -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("id", tc.id());
                    m.put("type", tc.type());
                    m.put("name", tc.name());
                    m.put("arguments", tc.arguments());
                    return m;
                })
                .toList();
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize tool calls", e);
        }
    }

    public String toolResponsesJson(Message message) {
        if (!(message instanceof ToolResponseMessage toolResponse)) return null;
        List<ToolResponse> responses = toolResponse.getResponses();
        if (responses == null || responses.isEmpty()) return null;

        List<Map<String, String>> list = responses.stream()
                .map(tr -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("id", tr.id());
                    m.put("name", tr.name());
                    m.put("responseData", tr.responseData());
                    return m;
                })
                .toList();
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize tool responses", e);
        }
    }

    // ==================== 反序列化（DB 列 → Message）====================

    @SuppressWarnings("unchecked")
    public Message toMessage(String role, String content, String toolCallsJson, String toolResponsesJson) {
        return switch (role) {
            case "USER" -> new UserMessage(content != null ? content : "");

            case "ASSISTANT" -> {
                List<AssistantMessage.ToolCall> toolCalls = List.of();
                if (toolCallsJson != null && !toolCallsJson.isEmpty()) {
                    try {
                        List<Map<String, String>> list = MAPPER.readValue(toolCallsJson, List.class);
                        toolCalls = list.stream()
                                .map(m -> new AssistantMessage.ToolCall(
                                        m.get("id"), m.get("type"), m.get("name"), m.get("arguments")))
                                .toList();
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to deserialize tool calls", e);
                    }
                }
                yield AssistantMessage.builder()
                        .content(content)
                        .toolCalls(toolCalls)
                        .build();
            }

            case "TOOL_RESPONSE" -> {
                List<ToolResponse> responses = new ArrayList<>();
                if (toolResponsesJson != null && !toolResponsesJson.isEmpty()) {
                    try {
                        List<Map<String, String>> list = MAPPER.readValue(toolResponsesJson, List.class);
                        responses = list.stream()
                                .map(m -> new ToolResponse(m.get("id"), m.get("name"), m.get("responseData")))
                                .toList();
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to deserialize tool responses", e);
                    }
                }
                yield ToolResponseMessage.builder().responses(responses).build();
            }

            default -> throw new IllegalArgumentException("Unknown role: " + role);
        };
    }
}
