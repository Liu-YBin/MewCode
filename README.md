# MewCode

## 项目简介

MewCode 是一个面向本地软件研发场景的终端 Coding Agent，基于 Java 构建。用户可以通过自然语言描述开发任务，由大语言模型负责理解需求、规划步骤并选择工具，本地工具负责代码检索、文件读写、命令执行、项目操作以及外部服务调用，最终完成代码分析、修改与验证。

## 实现功能

- **ReAct Agent**：支持“模型思考—调用工具—获取结果—继续决策”的循环执行模式，适合交互式编码和即时问题处理。
- **Plan-and-Execute**：将复杂任务拆解为带依赖关系的执行计划，支持 DAG 调度、并行执行、失败跳过和有限重试。
- **Multi-Agent 协作**：提供 Planner、Worker、Reviewer 等角色，由 Orchestrator 统一编排复杂任务并进行结果审查。
- **代码操作工具**：支持文件读取、文件写入、目录浏览、代码搜索、命令执行和项目创建等本地开发能力。
- **代码理解与 RAG**：支持代码分块、AST 分析、向量索引、语义检索以及类、方法、继承、调用和导入关系分析。
- **Memory 上下文管理**：支持短期对话记忆、长期事实记忆、项目级记忆、上下文摘要、Token 预算和历史压缩。
- **多模型接入**：支持 GLM、DeepSeek、StepFun、Kimi、FreeLLMAPI 和讯飞 MaaS 等模型，并支持运行时切换。
- **MCP 扩展能力**：支持通过 stdio 和 Streamable HTTP 接入 MCP Server，动态发现工具、资源和提示，并统一注册到工具系统。
- **联网与浏览器能力**：支持 Web 搜索、网页正文抓取、HTML 内容提取、Chrome DevTools MCP 以及浏览器登录态复用。
- **图片输入**：支持 `@image:` 本地图片引用、剪贴板图片输入以及 MCP 图片内容回灌。
- **Skill 系统**：支持内置、用户级和项目级 Skill，通过 `SKILL.md` 为 Agent 提供可复用的场景化操作规范。
- **安全与人工确认**：提供路径围栏、命令拦截、危险操作审批、敏感浏览器页面保护和操作审计日志。
- **终端交互界面**：提供 inline 流式 TUI、Lanterna 全屏 TUI 和 plain 输出三种渲染模式，支持状态栏、工具调用折叠、代码高亮、行内 diff、命令补全和历史记录。

## 启动界面

MewCode 默认使用 inline 流式 TUI，启动后会展示模型、MCP、Skill、当前模式和核心能力等运行状态：

```text
   /\_/\    MewCode  v16.1.0
  ( o.o )    Model auto (glm)
   > ^ <      MCP 1/1 · 8 tools · 2/2 skills · ReAct
              ReAct · Plan · MCP · Browser · Image · Tools · Memory · RAG

Tips for getting started:
1. Type / for commands and Tab completion
2. Ask coding questions, edit code or run commands
3. Attach context with @path or @image:

* 请分析当前项目的代码结构
```

## 技术栈

- **开发语言与构建**：Java 17、Maven
- **终端交互**：JLine 4、Lanterna 3
- **大模型接入**：OpenAI-compatible API、GLM、DeepSeek、StepFun、Kimi、FreeLLMAPI、讯飞 MaaS
- **网络通信**：OkHttp
- **数据与序列化**：Jackson、SQLite、SLF4J、Logback
- **代码分析**：JavaParser、Jieba 中文分词
- **网页处理**：Jsoup
- **版本快照**：JGit
- **MCP 协议**：JSON-RPC、stdio transport、Streamable HTTP transport
- **图片与终端二维码**：Java AWT、ZXing
