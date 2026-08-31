package com.mewcode.agent;

import com.mewcode.llm.GLMClient;
import com.mewcode.llm.GLMClient.ChatResponse;
import com.mewcode.llm.GLMClient.Message;
import com.mewcode.llm.GLMClient.ToolCall;
import com.mewcode.tool.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

//Agent核心
public class Agent {
    private static final String SYSTEM_PROMPT = """
    你是一个智能编程助手，可以帮助用户完成各种任务。

    你可以使用以下工具来完成任务：
    1. read_file - 读取文件内容
    2. write_file - 写入文件内容
    3. list_dir - 列出目录内容
    4. execute_command - 执行Shell命令
    5. create_project - 创建新项目结构

    当需要操作文件、执行命令或创建项目时，请使用工具调用。
    使用工具后，根据工具返回的结果继续思考下一步行动。

    请用中文回复用户。
    """;
    private static final int MAX_ITERATIONS = 10;

    private final GLMClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<Message> conversationHistory;

    public Agent() {
        this(GLMClient.fromEnv(), new ToolRegistry());
    }

    public Agent(String apiKey) {
        this(new GLMClient(apiKey), new ToolRegistry());
    }

    public Agent(GLMClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.conversationHistory = new ArrayList<>();

        // 添加系统提示
        conversationHistory.add(Message.system(SYSTEM_PROMPT));
    }

    public String chat(String userMessage) throws IOException {
        conversationHistory.add(Message.user(userMessage));

        String reply = llmClient.chat(conversationHistory);
        conversationHistory.add(Message.assistant(reply));

        return reply;
    }

    public ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    public List<Message> conversationHistory() {
        return conversationHistory;
    }

    public void clearHistory() {
        conversationHistory.clear();
        conversationHistory.add(Message.system(SYSTEM_PROMPT));
    }

    public String run(String userInput) {
        // 添加用户输入
        conversationHistory.add(Message.user(userInput));

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            // 调用 LLM
            ChatResponse response;
            try {
                response = llmClient.chat(
                        conversationHistory,
                        toolRegistry.getTools()
                );
            } catch (IOException e) {
                return "调用模型失败: " + e.getMessage();
            }

            // 如果有工具调用
            if (response.hasToolCalls()) {
                // 记录助手消息
                conversationHistory.add(Message.assistant(response.content(), response.toolCalls()));

                // 执行每个工具调用
                for (ToolCall toolCall : response.toolCalls()) {
                    String toolName = toolCall.function().name();
                    String toolArguments = toolCall.function().arguments();

                    // 测试版本：显示工具调用信息
                    System.out.println("\n🔧 执行工具: " + toolName);
                    System.out.println("📥 参数: " + toolArguments);

                    String result = toolRegistry.executeTool(
                            toolName,
                            toolArguments
                    );

                    System.out.println("📤 结果摘要: " + summarizeToolResult(result));

                    // 记录工具结果
                    conversationHistory.add(
                            Message.tool(toolCall.id(), result)
                    );
                }
                // 继续循环，让 LLM 根据结果继续思考
                continue;
            } else {
                // 没有工具调用，任务完成
                conversationHistory.add(
                        Message.assistant(response.content())
                );
                return response.content();
            }
        }

        return "达到最大迭代次数限制";
    }

    /**
     * 仅用于终端展示，工具完整结果仍会保存在对话历史中并返回给模型。
     */
    private String summarizeToolResult(String result) {
        if (result == null || result.isBlank()) {
            return "无结果";
        }

        List<String> lines = result.lines()
                .filter(line -> !line.isBlank())
                .toList();
        int maxLines = 6;
        int maxLength = 1000;

        String summary = lines.stream()
                .limit(maxLines)
                .collect(Collectors.joining(System.lineSeparator()));
        if (summary.length() > maxLength) {
            summary = summary.substring(0, maxLength).stripTrailing();
        }

        boolean truncated = lines.size() > maxLines || result.length() > summary.length();
        return truncated ? summary + "\n...（终端仅展示摘要）" : summary;
    }
}
