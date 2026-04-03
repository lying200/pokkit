package com.pokkit.tool;

/**
 * 工具接口 — Agent 通过工具与外部世界交互。
 * LLM 看到工具的 name + description + parameterSchema，决定什么时候调用。
 * 我们拿到 LLM 传来的 JSON 参数，执行工具，把文本结果喂回 LLM。
 */
public interface Tool {

    /** 工具名称，LLM 通过这个名字来调用 */
    String name();

    /** 工具描述，给 LLM 看的，它靠这个决定什么时候用这个工具 */
    String description();

    /** 参数的 JSON Schema 字符串，告诉 LLM 入参长什么样 */
    String parameterSchema();

    /** 执行工具。入参是 LLM 传过来的 JSON string，返回文本结果喂回 LLM */
    String execute(String argumentsJson);
}
