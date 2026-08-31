package com.mewcode.tool;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.mewcode.common.Validation.requireNonBlank;

// 工具注册入口，负责维护当前 Agent 可暴露给模型的工具列表。
public class ToolRegistry {
    private static final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Path workspaceRoot;
    private final ShellCommand shellCommand;

    public ToolRegistry() {
        this(Path.of(System.getProperty("user.dir")));
    }

    public ToolRegistry(Path workspaceRoot) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot")
                .toAbsolutePath()
                .normalize();
        this.shellCommand = detectShellCommand();
        registerReadFileTool();
        registerWriteFileTool();
        registerExecuteCommandTool();
    }

    public void register(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        tools.put(tool.name(), tool);
    }

    public List<Map<String, Object>> getToolDefinitions() {
        return tools.values().stream()
                .map(tool -> Map.<String, Object>of(
                        "type", "function",
                        "function", Map.of(
                                "name", tool.name(),
                                "description", tool.description(),
                                "parameters", tool.parameters()
                        )
                ))
                .toList();
    }

    public String executeTool(String name, String arguments) {
        Map<String, String> args = parseArguments(arguments);
        Tool tool = tools.get(name);
        return tool.toolExecutor().execute(args);
    }

    public List<Tool> getTools() {
        return tools.values().stream().toList();
    }

    private void registerReadFileTool() {
        register(new Tool(
                "read_file",
                "读取文件内容，用于查看代码、配置文件等",
                createParameters(new Param("path", "string", "文件路径", true)),
                args -> {
                    String path = args.get("path");
                    try {
                        String content = Files.readString(Path.of(path));
                        return "文件内容:\n" + content;
                    } catch (Exception e) {
                        return "读取文件失败: " + e.getMessage();
                    }
                }
        ));
    }

    private void registerExecuteCommandTool() {
        register(new Tool(
                "execute_command",
                "执行命令行命令，用于运行项目命令、查看目录、Git操作等",
                createParameters(new Param("command", "string", "要执行的命令", true)),
                args -> {
                    String command = args.get("command");
                    try {
                        Process process = new ProcessBuilder(shellCommand.toCommand(command))
                                .directory(workspaceRoot.toFile())
                                .redirectErrorStream(true)
                                .start();

                        String output = readProcessOutput(process.getInputStream());
                        int exitCode = process.waitFor();

                        return """
                                命令执行完成
                                exitCode:
                                %s
                                output:
                                %s
                                """.formatted(exitCode, output);
                    } catch (Exception e) {
                        return "执行命令失败: " + e.getMessage();
                    }
                }
        ));
    }

    private void registerWriteFileTool() {
        register(new Tool(
                "write_file",
                "写入文件内容",
                createParameters(
                        new Param("path", "string", "文件路径", true),
                        new Param("content", "string", "文件内容", true)
                ),
                args -> {
                    String path = args.get("path");
                    String content = args.get("content");
                    try {
                        Files.writeString(Path.of(path), content);
                        return "文件已写入: " + path;
                    } catch (Exception e) {
                        return "写入文件失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 模型可调用的工具定义。
     *
     * <p>parameters 是 JSON Schema，用来描述工具需要的参数名、参数类型和必填字段。</p>
     */
    public record Tool(
            String name,
            String description,
            Object parameters,
            @JsonIgnore ToolExecutor toolExecutor
    ) {
        public Tool {
            requireNonBlank(name, "name");
            requireNonBlank(description, "description");
            Objects.requireNonNull(parameters, "parameters");
            Objects.requireNonNull(toolExecutor, "toolExecutor");
        }
    }

    private record Param(String name, String type, String description, boolean required) {}

    private record ShellCommand(String executable, String option) {
        private List<String> toCommand(String command) {
            return List.of(executable, option, command);
        }
    }

    public interface ToolExecutor {
        String execute(Map<String, String> args);
    }

    /**
     * 创建参数定义
     */
    private JsonNode createParameters(Param... params) {
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");

        for (Param param : params) {
            ObjectNode prop = properties.putObject(param.name());
            prop.put("type", param.type());
            prop.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }

        return parameters;
    }

    private String readProcessOutput(InputStream inputStream) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }
        return output.toString();
    }

    private Map<String, String> parseArguments(String arguments) {
        try {
            Map<?, ?> rawArgs = mapper.readValue(arguments, Map.class);
            Map<String, String> args = new HashMap<>();
            rawArgs.forEach((key, value) -> args.put(String.valueOf(key), String.valueOf(value)));
            return args;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private ShellCommand detectShellCommand() {
        Optional<ShellCommand> currentShell = findCurrentShellCommand();
        if (currentShell.isPresent()) {
            return currentShell.get();
        }

        if (isCommandAvailable("bash", "-lc", "echo ok")) {
            return new ShellCommand("bash", "-lc");
        }
        if (isCommandAvailable("powershell", "-Command", "echo ok")) {
            return new ShellCommand("powershell", "-Command");
        }
        if (isCommandAvailable("cmd", "/c", "echo ok")) {
            return new ShellCommand("cmd", "/c");
        }
        return new ShellCommand("cmd", "/c");
    }

    private Optional<ShellCommand> findCurrentShellCommand() {
        Optional<ProcessHandle> process = ProcessHandle.current().parent();
        while (process.isPresent()) {
            Optional<String> command = process.get().info().command();
            if (command.isPresent()) {
                Optional<ShellCommand> shellCommand = toShellCommand(command.get());
                if (shellCommand.isPresent()) {
                    return shellCommand;
                }
            }
            process = process.get().parent();
        }
        return Optional.empty();
    }

    private Optional<ShellCommand> toShellCommand(String command) {
        String executableName = Path.of(command).getFileName().toString().toLowerCase();
        if (executableName.equals("bash") || executableName.equals("bash.exe")) {
            return Optional.of(new ShellCommand("bash", "-lc"));
        }
        if (executableName.equals("powershell") || executableName.equals("powershell.exe")) {
            return Optional.of(new ShellCommand("powershell", "-Command"));
        }
        if (executableName.equals("pwsh") || executableName.equals("pwsh.exe")) {
            return Optional.of(new ShellCommand("pwsh", "-Command"));
        }
        if (executableName.equals("cmd") || executableName.equals("cmd.exe")) {
            return Optional.of(new ShellCommand("cmd", "/c"));
        }
        return Optional.empty();
    }

    private boolean isCommandAvailable(String executable, String option, String command) {
        try {
            Process process = new ProcessBuilder(executable, option, command)
                    .directory(workspaceRoot.toFile())
                    .start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

}
