# my-claude

一个基于 **Spring AI 2.0** + **DeepSeek** 的类 Claude Code CLI 工具。

它在终端中启动一个可交互的 AI Agent，支持流式对话、会话级记忆、长期文件化记忆、Skills 工具调用、本地 Shell / 文件 / Web 等能力。

---

## 特性

- **流式对话**：标准输入读消息、终端实时输出（基于 Reactor Flux）
- **会话级记忆**：通过 `SessionMemoryAdvisor` 按 `SESSION_ID` 维持当前会话上下文
- **长期文件化记忆**：通过 `AutoMemoryToolsAdvisor` 把记忆以 Markdown 文件落到本地，支持 `MEMORY.md` 索引、`MemoryView` / `MemoryCreate` / `MemoryStrReplace` / `MemoryInsert` / `MemoryDelete` / `MemoryRename` 等工具
- **自定义记忆巩固触发器**：内置「距上次评估超过 60 秒」或「检测到告别用语」两条触发条件，避免每次对话都强制整理记忆
- **Skills 机制**：从 `classpath:/.agents/skills` 加载技能包，让 Agent 按需调用（自带 `find-skill`、`java-development-manual`、`skill-creator`）
- **本地工具集**：Shell、文件系统、Grep、SmartWebFetch、AskUserQuestion 等开箱即用
- **MCP 客户端预留**：依赖已引入（`spring-ai-starter-mcp-client`），可通过 `mcp-servers-configuration.json` 启用

## 技术栈

| 组件 | 版本 |
|---|---|
| Java | 17 |
| Spring Boot | 4.1.0 |
| Spring AI (BOM) | 2.0.0 |
| spring-ai-agent-utils (BOM) | 0.10.0 |
| spring-ai-session (BOM) | 0.7.0 |
| 构建工具 | Maven |
| 模型后端 | DeepSeek（`deepseek-v4-flash`） |

## 快速开始

### 1. 准备环境变量

```bash
# PowerShell
$env:DEEPSEEK_API_KEY = "sk-xxxxxxxxxxxxxxxxxxxxxxxx"
```

```bash
# bash / zsh
export DEEPSEEK_API_KEY="sk-xxxxxxxxxxxxxxxxxxxxxxxx"
```

### 2. 启动

```bash
mvn spring-boot:run
```

或者打成 jar 包运行：

```bash
mvn clean package
java -jar target/my-claude-1.0.jar
```

### 3. 对话

应用启动后会进入 REPL 模式，提示 `用户：`，直接输入消息回车即可。

```
用户：帮我看下当前项目的依赖
Claude：[流式输出...]
用户：再见
Claude：[流式输出...]
[记忆巩固] 检测到告别用语，触发异步整理
```

> 当前 `ClaudeApplication.java` 里把会话 ID 硬编码为 `111`（`SESSION_ID_CONTEXT_KEY`），所有会话共享同一段上下文；如果你想做多会话隔离，把它替换成动态 ID 即可。

## 配置

所有可配置项集中在 `src/main/resources/application.yaml`：

```yaml
spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        model: deepseek-v4-flash
    skills:
      paths: classpath:/.agents/skills
    memory:
      path: src/main/resources/.agents/memory
    # mcp:
    #   client:
    #     stdio:
    #       servers-configuration: 'classpath:mcp-servers-configuration.json'
```

| 配置项 | 说明 |
|---|---|
| `spring.ai.deepseek.api-key` | DeepSeek API Key，从环境变量 `DEEPSEEK_API_KEY` 注入 |
| `spring.ai.deepseek.chat.model` | 调用的模型名，默认 `deepseek-v4-flash` |
| `spring.ai.skills.paths` | Skills 资源路径，使用 `classpath:/.agents/skills` 加载打包到 jar 内的技能 |
| `spring.ai.memory.path` | 长期记忆落地目录，运行时会在此目录创建 `MEMORY.md` 等文件 |
| `logging.threshold.console` | 控制台日志级别，默认 `ERROR`，调试 Advisor 可改为 `DEBUG` |

### 启用 MCP 客户端

取消 `application.yaml` 中 `mcp.client` 段的注释，并按 `mcp-servers-configuration.json` 的格式补全 stdio server 配置即可。

## 项目结构

```
my-claude/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/sonnie/claude/
│   │   │   └── ClaudeApplication.java          # 入口 + ChatClient 构建
│   │   └── resources/
│   │       ├── application.yaml                 # Spring AI / DeepSeek 配置
│   │       ├── my-claude-system-prompt.md       # Agent 主系统提示词
│   │       ├── my-claude-auto-memory-tools-system-prompt.md  # 记忆子系统提示词
│   │       ├── .agents/skills/                  # 打包进 jar 的 Skills
│   │       │   ├── find-skill/
│   │       │   ├── java-development-manual/
│   │       │   └── skill-creator/
│   │       ├── .agents/memory/                  # 运行时记忆落地（已被 .gitignore 排除）
│   │       ├── mcp-servers-configuration.json   # MCP 服务器配置（默认注释未启用）
│   │       ├── logback-spring.xml
│   │       └── banner.txt
│   └── test/
│       └── java/com/sonnie/claude/
│           └── TestDeepSeek.java
└── target/                                      # 构建产物
```

## 运行机制

`ClaudeApplication` 在 `CommandLineRunner` 里完成三件事：

1. **组装系统提示词**：从 `my-claude-system-prompt.md` 读取主提示，注入 `AgentEnvironment`（环境信息、Git 状态、模型名、知识截止时间、记忆根目录）等占位符。
2. **构建 `ChatClient`**：
   - `defaultAdvisors` 注册 `SessionMemoryAdvisor`（会话上下文）+ `SimpleLoggerAdvisor`（调试日志）+ `AutoMemoryToolsAdvisor`（记忆持久化，含自定义触发器）。
   - `defaultTools` 注册 `SkillsTool`、Shell、文件系统、SmartWebFetch、Grep、AskUserQuestion 等工具。
3. **进入 REPL 循环**：`Scanner` 读取用户输入，`ChatClient.prompt().stream().content()` 输出流式响应。

### 记忆巩固触发条件

`memoryConsolidationTrigger` 返回 `true` 时会触发记忆整理，规则为：

1. 距上次评估超过 60 秒
2. 用户消息包含「再见 / bye / goodbye / 拜拜 / 告辞 / quit / exit」之一

`false` 时本轮不整理记忆，跳过无意义的反复扫描。

## 注意事项

- **`.agents/memory/` 已在 `.gitignore` 中排除**，其中包含 `user_sonnie.md` 这类用户专属记忆，属于运行时隐私数据，**不要将其加入版本控制**。
- **`AutoMemoryToolsAdvisor` 必须通过 `defaultAdvisors(...)` 注册**，而不是 `defaultTools(...)`。后者会调用 `MethodToolCallbackProvider` 扫描 `@Tool` 注解方法，触发 `IllegalArgumentException`。
- **`AutoMemoryTools` 与 `AutoMemoryToolsAdvisor` 不应同时注册**，否则会出现工具重复。Advisor 已经封装了前者，无需重复。
- **DeepSeek 模型名会随官方更新调整**，如果 `deepseek-v4-flash` 不可用，请在 `application.yaml` 替换为当前可用的模型标识。
- **Skills 内容会打包进 jar**：修改 `src/main/resources/.agents/skills/` 后需要重新 `mvn package` 才能让打包后的产物生效；本地开发用 `mvn spring-boot:run` 即可热加载。

## 开发提示

- 修改入口逻辑集中在 `ClaudeApplication.java`，大多数行为调整（添加工具、调整提示词、改触发器）都在这一个文件里完成。
- 调试 Advisor 行为时，把 `logging.threshold.console` 临时改为 `DEBUG` 并取消 `org.springframework.ai.chat.client.advisor: DEBUG` 注释即可看到详细日志。
- 增加自定义 Skill：在 `src/main/resources/.agents/skills/<your-skill>/` 下创建 `SKILL.md` 和（可选的）`_meta.json` 即可被 `SkillsTool` 自动加载。