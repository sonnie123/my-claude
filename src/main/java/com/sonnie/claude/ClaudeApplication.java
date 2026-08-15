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

@SpringBootApplication
public class ClaudeApplication {
    public static void main(String[] args) {
        SpringApplication.run(ClaudeApplication.class, args);
    }

    @Bean
    CommandLineRunner commandLineRunner(DeepSeekChatModel chatModel, ChatClient.Builder chatClientBuilder, SessionMemoryAdvisor sessionMemoryAdvisor,
                                        ToolCallbackProvider toolCallbackProvider,
                                        @Value("classpath:my-claude-system-prompt.md") Resource systemPrompt,
                                        @Value("classpath:my-claude-auto-memory-tools-system-prompt.md") Resource autoMemorySystemPrompt,
                                        @Value("${spring.ai.skills.paths}") List<Resource> skillsResources,
                                        @Value("${spring.ai.memory.path}") String memoryPath
    ) throws IOException {
        String systemFullPrompt = systemPrompt.getContentAsString(Charset.defaultCharset()) +
//                autoMemorySystemPrompt.getContentAsString(Charset.defaultCharset()) +
                """
                        记住：一定记得在处理了长期记忆之后同时处理MEMORY.md
                        """;
        AtomicReference<Instant> lastConsolidationTime = new AtomicReference<>(Instant.now());

        return args -> {
            ChatClient chatClient = chatClientBuilder
                    .defaultSystem(p -> p.text(systemFullPrompt)
                            .param(AgentEnvironment.ENVIRONMENT_INFO_KEY, AgentEnvironment.info())
                            .param(AgentEnvironment.GIT_STATUS_KEY, AgentEnvironment.gitStatus())
                            .param(AgentEnvironment.AGENT_MODEL_KEY, chatModel)
                            .param(AgentEnvironment.AGENT_MODEL_KNOWLEDGE_CUTOFF_KEY, "unknown")
                            .param("MEMORIES_ROOT_DIERCTORY", memoryPath))
                    .defaultAdvisors(sessionMemoryAdvisor,
                            SimpleLoggerAdvisor.builder().build(),
//                            这里选用自定义长期记忆触发器<for learn>
                            AutoMemoryToolsAdvisor.builder()
                                    .memoriesRootDirectory(memoryPath)
                                    .memorySystemPrompt(autoMemorySystemPrompt)
                                    .memoryConsolidationTrigger((request, instant) -> {
                                        Instant previous = lastConsolidationTime.get();
                                        lastConsolidationTime.set(instant);

                                        if (instant.isAfter(previous.plusSeconds(60))) {
                                            System.out.println("[记忆巩固] 距上次评估已超过 60 秒，触发异步整理");
                                            return true;
                                        }

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
                                        return false;
                                    }).build())
                    .defaultTools(
                            toolCallbackProvider,
                            SkillsTool.builder().addSkillsResources(skillsResources).build(),
//                            默认每轮对话结束后，异步触发长期记忆
//                            AutoMemoryTools.builder().memoriesDir(memoryPath).build(),
                            ShellTools.builder().build(),
                            FileSystemTools.builder().build(),
                            SmartWebFetchTool.builder(chatClientBuilder.clone().build()).build(),
                            GrepTool.builder().build(),
                            AskUserQuestionTool.builder()
                                    .questionHandler(new CommandLineQuestionHandler())
                                    .answersValidation(true)
                                    .build())
                    .build();

            try (Scanner scanner = new Scanner(System.in)) {
                while (true) {
                    System.out.println("用户：");
                    String userMessage = scanner.nextLine();

                    System.out.println("Claude：");
                    Flux<String> chatResponse = chatClient.prompt()
                            .user(userMessage)
                            .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "111"))
                            .stream()
                            .content();
                    chatResponse.doOnNext(System.out::print).blockLast();
                    System.out.println();
                }
            }
        };
    }
}