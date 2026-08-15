package com.sonnie.claude;

import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.ShellTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

import java.util.Scanner;

@SpringBootApplication
public class ClaudeApplication {
    public static void main(String[] args) {
        SpringApplication.run(ClaudeApplication.class, args);
    }

    @Bean
    CommandLineRunner commandLineRunner(DeepSeekChatModel chatModel, SessionMemoryAdvisor sessionMemoryAdvisor,
                                        ToolCallbackProvider toolCallbackProvider) {
        return args -> {
            ChatClient chatClient = ChatClient.builder(chatModel)
                    .defaultAdvisors(sessionMemoryAdvisor,
                            SimpleLoggerAdvisor.builder().build())
                    .defaultTools(
                            toolCallbackProvider,
                            ShellTools.builder().build(),
                            FileSystemTools.builder().build())
                    .build();

            while (true) {
                Scanner scanner = new Scanner(System.in);
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
        };
    }
}