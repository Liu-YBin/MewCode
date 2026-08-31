package com.mewcode.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mewcode.tool.ToolRegistry.Tool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.mewcode.common.Validation.requireNonBlank;

/**
 * 智谱 GLM Chat Completions API 的轻量封装。
 *
 * <p>当前实现只提供同步非流式调用，适合作为 Agent 的底层 LLM 适配器。</p>
 */
public final class GLMClient {
    private static final Logger log = LoggerFactory.getLogger(GLMClient.class);
    private static final String API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private final String apiKey;
    private final OkHttpClient httpClient;
    private static final String DEFAULT_MODEL = "glm-5.1";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final ObjectMapper objectMapper;

    private final String apiUrl;
    private final String model;

    /**
     * 使用默认智谱 API 地址和默认模型 glm-5.1。
     */
    public GLMClient(String apiKey) {
        this(apiKey, API_URL, DEFAULT_MODEL);
    }

    /**
     * 允许覆盖 API 地址和模型名，便于测试、代理或后续切换模型。
     */
    public GLMClient(String apiKey, String apiUrl, String model) {
        this(apiKey, apiUrl, model, defaultHttpClient(), defaultObjectMapper());
    }

    public GLMClient(
            String apiKey,
            String apiUrl,
            String model,
            OkHttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this.apiKey = requireNonBlank(apiKey, "apiKey");
        this.apiUrl = requireNonBlank(apiUrl, "apiUrl");
        this.model = requireNonBlank(model, "model");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * OpenAI 兼容格式的对话消息。
     */
    public record Message(
            String role,
            String content,
            @JsonProperty("tool_calls")
            List<ToolCall> toolCalls,
            @JsonProperty("tool_call_id")
            String toolCallId
    ) {
        public Message {
            requireNonBlank(role, "role");
        }

        public static Message system(String content) {
            return new Message("system", content, null, null);
        }

        public static Message user(String content) {
            return new Message("user", content, null, null);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content, null, null);
        }

        public static Message assistant(String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, toolCalls, null);
        }

        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, null, toolCallId);
        }
    }
    /**
     * 单轮对话的便捷入口。
     */
    public String chat(String userMessage) throws IOException {
        return chat(List.of(Message.user(userMessage)));
    }

    /**
     * 多轮对话入口，返回第一条候选回复的文本内容。
     */
    public String chat(List<Message> messages) throws IOException {
        ChatResponse response = chat(messages, List.of());
        if (response.choices() == null || response.choices().isEmpty()) {
            throw new GLMException("GLM response has no choices.");
        }

        Message message = response.choices().get(0).message();
        if (message == null || message.content() == null) {
            throw new GLMException("GLM response choice has no message content.");
        }
        return message.content();
    }


    public ChatResponse chat(List<Message> messages, List<Tool> tools)
            throws IOException {
        // 构建请求体
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);

        // 添加消息历史
        ArrayNode messagesArray = requestBody.putArray("messages");
        for (Message msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.role());
            msgNode.put("content", msg.content());

            // 如果有工具调用，序列化 tool_calls
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsArray = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = toolCallsArray.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode functionNode = tcNode.putObject("function");
                    functionNode.put("name", tc.function().name());
                    functionNode.put("arguments", tc.function().arguments());
                }
            }

            // 如果是工具结果，添加 tool_call_id
            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
        }

        // 添加工具定义
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.set("parameters", objectMapper.valueToTree(tool.parameters()));
            }
        }

        // 发送 HTTP 请求
        RequestBody body = RequestBody.create(
                requestBody.toString(),
                JSON
        );

        Request request = new Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        // 解析响应
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new GLMException("GLM request failed: HTTP " + response.code() + " " + responseBody);
            }

            JsonNode root = objectMapper.readTree(responseBody);
            return parseChatResponse(root);
        }
    }

    private ChatResponse parseChatResponse(JsonNode root) {
        JsonNode choicesNode = root.path("choices");
        JsonNode choiceNode = choicesNode.isArray() && !choicesNode.isEmpty() ? choicesNode.get(0) : objectMapper.createObjectNode();
        JsonNode messageNode = choiceNode.path("message");

        List<ToolCall> toolCalls = parseToolCalls(messageNode.path("tool_calls"));
        Message message = new Message(
                messageNode.path("role").asText("assistant"),
                textOrNull(messageNode.get("content")),
                toolCalls.isEmpty() ? null : toolCalls,
                textOrNull(messageNode.get("tool_call_id"))
        );

        Choice choice = new Choice(
                choiceNode.path("index").isMissingNode() ? null : choiceNode.path("index").asInt(),
                message,
                textOrNull(choiceNode.get("finish_reason"))
        );

        JsonNode usageNode = root.path("usage");
        Usage usage = new Usage(
                intOrNull(usageNode.get("prompt_tokens")),
                intOrNull(usageNode.get("completion_tokens")),
                intOrNull(usageNode.get("total_tokens"))
        );

        return new ChatResponse(
                textOrNull(root.get("id")),
                textOrNull(root.get("model")),
                List.of(choice),
                usage
        );
    }

    private List<ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return List.of();
        }

        return java.util.stream.StreamSupport.stream(toolCallsNode.spliterator(), false)
                .map(toolCallNode -> {
                    JsonNode functionNode = toolCallNode.path("function");
                    return new ToolCall(
                            textOrNull(toolCallNode.get("id")),
                            toolCallNode.path("type").asText("function"),
                            new FunctionCall(
                                    textOrNull(functionNode.get("name")),
                                    textOrNull(functionNode.get("arguments"))
                            )
                    );
                })
                .toList();
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    private static Integer intOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asInt();
    }


    /**
     * 从环境变量读取 API Key。
     *
     * <p>只读取项目根目录 .env 文件。</p>
     */
    public static GLMClient fromEnv() {
        String apiKey = firstNonBlank(
                readDotEnvValue("GLM_API_KEY").orElse(null),
                readDotEnvValue("ZHIPU_API_KEY").orElse(null),
                readDotEnvValue("BIGMODEL_API_KEY").orElse(null)
        );
        if (apiKey == null) {
            throw new IllegalStateException("Missing API key. Set GLM_API_KEY, ZHIPU_API_KEY or BIGMODEL_API_KEY in .env.");
        }
        return new GLMClient(apiKey);
    }

    /**
     * 发送原始 Chat Completions 请求，并返回完整响应对象。
     *
     * <p>需要自定义 max_tokens、temperature、thinking 或读取 usage 时，使用这个方法。</p>
     */
    public ChatResponse createChatCompletion(ChatRequest body) throws IOException {
        Objects.requireNonNull(body, "body");

        String requestJson = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestJson, JSON))
                .build();

        log.debug("Calling GLM model {}", body.model());

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new GLMException("GLM request failed: HTTP " + response.code() + " " + responseBody);
            }
            return objectMapper.readValue(responseBody, ChatResponse.class);
        }
    }

    private static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofMinutes(5))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
    }

    private static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Optional<String> readDotEnvValue(String key) {
        Path dotEnv = Path.of(".env");
        if (!Files.isRegularFile(dotEnv)) {
            return Optional.empty();
        }

        try {
            for (String line : Files.readAllLines(dotEnv)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                int separatorIndex = trimmed.indexOf('=');
                if (separatorIndex <= 0) {
                    continue;
                }

                String name = trimmed.substring(0, separatorIndex).trim();
                if (key.equals(name)) {
                    return Optional.of(stripOptionalQuotes(trimmed.substring(separatorIndex + 1).trim()));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read .env file.", e);
        }

        return Optional.empty();
    }

    private static String stripOptionalQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    public record ChatRequest(
            String model,
            List<Message> messages,
            Thinking thinking,
            Collection<?> tools,
            @JsonProperty("max_tokens") Integer maxTokens,
            Float temperature,
            Boolean stream
    ) {
        /**
         * 对应智谱 Chat Completions 请求体。
         */
        public ChatRequest {
            requireNonBlank(model, "model");
            if (messages == null || messages.isEmpty()) {
                throw new IllegalArgumentException("messages must not be empty.");
            }
        }

        /**
         * GLM-5.1 默认开启 thinking；当前客户端默认使用非流式响应。
         */
        public static ChatRequest defaults(String model, List<Message> messages) {
            return new ChatRequest(
                    model,
                    messages,
                    Thinking.enabled(),
                    null,
                    65536,
                    1.0f,
                    false
            );
        }

        public static ChatRequest withTools(String model, List<Message> messages, Collection<?> tools) {
            return new ChatRequest(
                    model,
                    messages,
                    Thinking.enabled(),
                    tools,
                    65536,
                    1.0f,
                    false
            );
        }
    }


    /**
     * GLM-5.1 的思考模式配置。
     */
    public record Thinking(String type) {
        public static Thinking enabled() {
            return new Thinking("enabled");
        }

        public static Thinking disabled() {
            return new Thinking("disabled");
        }
    }

    /**
     * 只建模当前代码会用到的响应字段，其他字段由 Jackson 忽略。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatResponse(
            String id,
            String model,
            List<Choice> choices,
            Usage usage
    ) {
        public String content() {
            Message message = choices.get(0).message();
            return message == null ? null : message.content();
        }

        public List<ToolCall> toolCalls() {
            Message message = choices.get(0).message();
            return message == null ? List.of() : message.toolCalls();
        }

        public boolean hasToolCalls() {
            List<ToolCall> toolCalls = toolCalls();
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            Integer index,
            Message message,
            @JsonProperty("finish_reason") String finishReason
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCall(
            String id,
            String type,
            FunctionCall function
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionCall(
            String name,
            String arguments
    ) {
    }

    /**
     * API 调用失败或响应结构不符合预期时抛出的异常。
     */
    public static final class GLMException extends IOException {
        public GLMException(String message) {
            super(message);
        }
    }
}
