/**
 * my-claude 项目的启动入口类。
 *
 * <p>这是一个基于 Spring AI + DeepSeek 的命令行 AI Agent 程序：
 * <ul>
 *   <li>启动后进入 REPL（Read-Eval-Print Loop）交互模式</li>
 *   <li>用户从标准输入发送消息，程序调用 DeepSeek 模型并把回复实时打印到终端</li>
 *   <li>为 ChatClient 装配了会话记忆、长期文件记忆、Skills、Shell、文件系统、Web 抓取等能力</li>
 * </ul>
 *
 * <p>核心概念速览：
 * <ul>
 *   <li><b>ChatClient</b>：Spring AI 用于与大模型对话的高阶客户端，支持同步、流式、工具调用、Advisor 拦截链</li>
 *   <li><b>Advisor</b>：拦截器链，可对请求/响应做增强（如注入系统提示词、记录日志、维护记忆），类比 AOP</li>
 *   <li><b>Tool / ToolCallback</b>：让 LLM 在推理过程中能主动调用的外部函数（如读文件、跑 shell）</li>
 *   <li><b>Reactor Flux</b>：响应式流，本项目里用于打印模型流式输出</li>
 * </ul>
 */
package com.sonnie.claude;

import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springaicommunity.agent.tools.*;
import org.springaicommunity.agent.utils.AgentEnvironment;
import org.springaicommunity.agent.utils.CommandLineQuestionHandler;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 应用主类。
 *
 * <p>@SpringBootApplication 触发自动装配（DeepSeekChatModel、ChatClient.Builder 等 Bean 由 spring-ai-starter 自动注册）。
 */
@SpringBootApplication
public class ClaudeApplication {
    /**
     * Java 进程入口；委托给 SpringApplication.run 启动 Spring 容器。
     *
     * @param args 命令行参数，会被 SpringApplication 用于配置覆盖（例如 --spring.profiles.active=xxx）
     */
    public static void main(String[] args) {
        SpringApplication.run(ClaudeApplication.class, args);
    }

    /**
     * 注册一个 CommandLineRunner Bean：Spring Boot 上下文启动完成后会执行此方法返回的 Runner，
     * 这里用它的 args -> {...} lambda 启动一个 REPL。
     *
     * <p>依赖的参数都会由 Spring 容器按类型或 @Value 注入：
     * <ul>
     *   <li>{@code DeepSeekChatModel chatModel}：DeepSeek 适配的 ChatModel</li>
     *   <li>{@code ChatClient.Builder chatClientBuilder}：Spring AI 自动装配的 Builder，用于构造 ChatClient</li>
     *   <li>{@code SessionMemoryAdvisor sessionMemoryAdvisor}：会话记忆拦截器</li>
     *   <li>{@code ToolCallbackProvider toolCallbackProvider}：spring-ai-agent-utils 注入的工具集合</li>
     *   <li>{@code systemPrompt} / {@code autoMemorySystemPrompt}：两个系统提示词 Resource</li>
     *   <li>{@code skillsResources}：从 yaml 的 spring.ai.skills.paths 注入的 Skills 资源列表</li>
     *   <li>{@code memoryPath}：从 yaml 的 spring.ai.memory.path 注入的记忆根目录</li>
     * </ul>
     */
    @Bean
    CommandLineRunner commandLineRunner(DeepSeekChatModel chatModel, ChatClient.Builder chatClientBuilder, SessionMemoryAdvisor sessionMemoryAdvisor,
                                        ToolCallbackProvider toolCallbackProvider,
                                        @Value("classpath:my-claude-system-prompt.md") Resource systemPrompt,
                                        @Value("classpath:my-claude-auto-memory-tools-system-prompt.md") Resource autoMemorySystemPrompt,
                                        @Value("${spring.ai.skills.paths}") List<Resource> skillsResources,
                                        @Value("${spring.ai.memory.path}") String memoryPath
    ) throws IOException {
        // 拼接主系统提示词：先把 my-claude-system-prompt.md 读到字符串，后面再追加一句补充说明（文本块语法）
        String systemFullPrompt = systemPrompt.getContentAsString(Charset.defaultCharset()) +
//                autoMemorySystemPrompt.getContentAsString(Charset.defaultCharset()) +
                """
                        记住：一定记得在处理了长期记忆之后同时处理MEMORY.md
                        """;
        // 用 AtomicReference 包装上次记忆评估时间戳：lambda 内部需要修改这个外部变量，所以必须是 effectively final 的包装类型
        AtomicReference<Instant> lastConsolidationTime = new AtomicReference<>(Instant.now());

        // 返回的 Runner lambda 在 Spring Boot 启动完成后会被调用
        return args -> {
            // 构建 ChatClient：使用 Builder 的链式调用先设置 defaultSystem（全局系统提示词）、defaultAdvisors（拦截器链）、defaultTools（默认工具集），最后 .build() 得到不可变实例
            ChatClient chatClient = chatClientBuilder
                    // defaultSystem(...) 接受一个 PromptTemplate 回调，便于在系统提示词里填充变量（如环境信息、模型名）
                    .defaultSystem(p -> p.text(systemFullPrompt)
                            // AgentEnvironment.ENVIRONMENT_INFO_KEY：注入操作系统、JDK 版本、工作目录、用户名等环境变量占位符
                            .param(AgentEnvironment.ENVIRONMENT_INFO_KEY, AgentEnvironment.info())
                            // AgentEnvironment.GIT_STATUS_KEY：注入当前仓库的 git status，帮助 Agent 感知项目变更
                            .param(AgentEnvironment.GIT_STATUS_KEY, AgentEnvironment.gitStatus())
                            // AgentEnvironment.AGENT_MODEL_KEY：告诉 Agent 当前使用的模型（供 Agent 调整回答风格）
                            .param(AgentEnvironment.AGENT_MODEL_KEY, chatModel)
                            // AgentEnvironment.AGENT_MODEL_KNOWLEDGE_CUTOFF_KEY：告诉 Agent 模型的知识截止时间（本项目里填 unknown）
                            .param(AgentEnvironment.AGENT_MODEL_KNOWLEDGE_CUTOFF_KEY, "unknown")
                            // 自定义占位符 MEMORIES_ROOT_DIERCTORY（原拼写带 typo，这里也保持一致）告诉 Agent 记忆根目录的绝对路径
                            .param("MEMORIES_ROOT_DIERCTORY", memoryPath))
                    // defaultAdvisors(...) 装配拦截器链：
                    //   - SessionMemoryAdvisor：按 SESSION_ID 维护当前会话上下文
                    //   - SimpleLoggerAdvisor：调试时打印 prompt / response
                    //   - AutoMemoryToolsAdvisor：每轮对话后根据触发器决定是否整理长期记忆文件
                    .defaultAdvisors(sessionMemoryAdvisor,
                            SimpleLoggerAdvisor.builder().build(),
//                            这里选用自定义长期记忆触发器<for learn>
                            AutoMemoryToolsAdvisor.builder()
                                    // 记忆根目录：所有 Memory 工具的相对路径都会基于此目录解析
                                    .memoriesRootDirectory(memoryPath)
                                    // 记忆子系统提示词：定义可用的 Memory 工具与记忆分类（user/feedback/project/reference）
                                    .memorySystemPrompt(autoMemorySystemPrompt)
                                    // 自定义触发器：BiFunction<ChatClientRequest, Instant, Boolean>
                                    //   - request：当前 ChatClient 请求
                                    //   - instant：当前请求的墙钟时间
                                    //   - 返回 true 时本轮触发记忆整理
                                    .memoryConsolidationTrigger((request, instant) -> {
                                        // 先读取并刷新上次触发时间；AtomicReference 保证 lambda 内对外部变量的写入是线程安全的
                                        Instant previous = lastConsolidationTime.get();
                                        lastConsolidationTime.set(instant);

                                        // 条件 1：距上次评估超过 60 秒则触发，避免每次对话都强制做记忆整理
                                        if (instant.isAfter(previous.plusSeconds(60))) {
                                            System.out.println("[记忆巩固] 距上次评估已超过 60 秒，触发异步整理");
                                            return true;
                                        }

                                        // 条件 2：检测告别用语则触发，让用户能主动触发记忆整理
                                        String userText = request.prompt()
                                                .getLastUserOrToolResponseMessage()
                                                .getText();
                                        if (userText != null) {
                                            String normalized = userText.toLowerCase();
                                            boolean matched = normalized.contains("再见")
                                                    || normalized.contains("bye") || normalized.contains("goodbye")
                                                    || normalized.contains("拜拜") || normalized.contains("告辞")
                                                    || normalized.contains("quit") || normalized.contains("exit");
                                            if (matched) {
                                                System.out.println("[记忆巩固] 检测到告别用语，触发异步整理");
                                                return true;
                                            }
                                        }
                                        // 其它情况跳过整理，节省 token 与文件 IO
                                        return false;
                                    }).build())
                    // defaultTools(...) 装配默认工具集：所有列出的对象都会被转换为 ToolCallback，LLM 可以在推理时主动调用
                    .defaultTools(
                            // toolCallbackProvider：spring-ai-agent-utils 自动暴露的 6 个 Memory 工具（MemoryView / Create / StrReplace / Insert / Delete / Rename）
                            toolCallbackProvider,
                            // SkillsTool：让 LLM 能从 skillsResources 中加载 Skill（技能包），如 find-skill、java-development-manual、skill-creator
                            SkillsTool.builder().addSkillsResources(skillsResources).build(),
//                            默认每轮对话结束后，异步触发长期记忆
//                            AutoMemoryTools.builder().memoriesDir(memoryPath).build(),
                            // ShellTools：提供受限的本地 shell 执行能力（默认有安全策略，避免高危命令）
                            ShellTools.builder().build(),
                            // FileSystemTools：提供文件 / 目录的读写、列举、删除、移动等文件操作
                            FileSystemTools.builder().build(),
                            // SmartWebFetchTool：抓取网页内容并提取主体文本；通过 .clone() 复用同一个 ChatClient.Builder 以避免重新装配
                            SmartWebFetchTool.builder(chatClientBuilder.clone().build()).build(),
                            // GrepTool：在项目内对文件做正则搜索
                            GrepTool.builder().build(),
                            // AskUserQuestionTool：当 LLM 决策过程需要用户输入时调用，这里把交互委托给控制台问答处理器
                            AskUserQuestionTool.builder()
                                    .questionHandler(new CommandLineQuestionHandler())
                                    // answersValidation(true)：要求用户输入必须从候选答案中选择，不接受自由文本
                                    .answersValidation(true)
                                    .build())
                    // build() 冻结配置、生成不可变的 ChatClient 实例
                    .build();

            // 使用 try-with-resources 确保 Scanner 在循环退出时关闭，避免 stdin 资源泄露
            try (Scanner scanner = new Scanner(System.in)) {
                // REPL 主循环：每轮读取一行用户输入、调用模型流式输出、刷新提示符
                while (true) {
                    System.out.println("用户：");
                    // 同步阻塞读取一行输入；用户回车后才会继续
                    String userMessage = scanner.nextLine();

                    System.out.println("Claude：");
                    // 构建本轮 prompt：.user(...) 设置用户输入，.advisors(...) 给 Advisor 注入临时参数，.stream() 启用流式返回
                    Flux<String> chatResponse = chatClient.prompt()
                            .user(userMessage)
                            // 给当前请求的 Advisor 链注入参数；这里指定会话 ID 为 "111"，所有消息共享同一会话上下文
                            .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "111"))
                            // stream() 返回 Flux<ChatResponse>，再 .content() 取纯文本内容流
                            .stream()
                            .content();
                    // doOnNext 订阅副作用：每收到一段文本就原样打印到终端；blockLast() 阻塞直到流结束，保证换行在响应结束后打印
                    chatResponse.doOnNext(System.out::print).blockLast();
                    // 在响应结束后补一个换行，让下一个 "用户：" 提示符另起一行
                    System.out.println();
                }
            }
        };
    }
}