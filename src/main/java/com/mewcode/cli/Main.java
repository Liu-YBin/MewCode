package com.mewcode.cli;

import com.mewcode.agent.Agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        printBanner();

        // 加载 API Key
        String apiKey = loadApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("❌ 错误: 未在 .env 中找到 GLM_API_KEY、ZHIPU_API_KEY 或 BIGMODEL_API_KEY");
            System.exit(1);
        }

        // 创建 Agent
        Agent agent = new Agent(apiKey);

        // 交互式循环
        Scanner scanner = new Scanner(System.in);
        System.out.println("💡 提示: 输入 'clear' 清空历史, 'exit' 退出\n");

        while (true) {
            System.out.print("👤 你: ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;
            if (input.equalsIgnoreCase("exit")) break;
            if (input.equalsIgnoreCase("clear")) {
                agent.clearHistory();
                System.out.println("🗑️ 历史已清空\n");
                continue;
            }

            // 运行 Agent
            String response = agent.run(input);
            System.out.println("🤖 Agent: " + response + "\n");
        }
    }

    private static void printBanner() {
        System.out.println("""
        ╔════════════════════════════════════════════════════════════════════╗
        ║  ███╗   ███╗███████╗██╗    ██╗ ██████╗ ██████╗ ██████╗ ███████╗    ║
        ║  ████╗ ████║██╔════╝██║    ██║██╔════╝██╔═══██╗██╔══██╗██╔════╝    ║
        ║  ██╔████╔██║█████╗  ██║ █╗ ██║██║     ██║   ██║██║  ██║█████╗      ║
        ║  ██║╚██╔╝██║██╔══╝  ██║███╗██║██║     ██║   ██║██║  ██║██╔══╝      ║
        ║  ██║ ╚═╝ ██║███████╗╚███╔███╔╝╚██████╗╚██████╔╝██████╔╝███████╗    ║
        ║  ╚═╝     ╚═╝╚══════╝ ╚══╝╚══╝  ╚═════╝ ╚═════╝ ╚═════╝ ╚══════╝    ║
        ║                              Mewcode                               ║
        ╚════════════════════════════════════════════════════════════════════╝
        """);
    }

    private static String loadApiKey() {
        Path dotEnv = Path.of(".env");

        try {
            for (String line : Files.readAllLines(dotEnv)) {
                String trimmed = line.trim();
                int separatorIndex = trimmed.indexOf('=');
                if (separatorIndex <= 0) {
                    continue;
                }

                String key = trimmed.substring(0, separatorIndex).trim();
                String value = stripOptionalQuotes(trimmed.substring(separatorIndex + 1).trim());
                if (key.equals("GLM_API_KEY") || key.equals("ZHIPU_API_KEY") || key.equals("BIGMODEL_API_KEY")) {
                    return value;
                }
            }
        } catch (IOException e) {
            return null;
        }

        return null;
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
}
