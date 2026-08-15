package com.sonnie.claude.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.compaction.RecursiveSummarizationCompactionStrategy;
import org.springframework.ai.session.compaction.SlidingWindowCompactionStrategy;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatMemoryConfiguration {

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .maxMessages(10)
                .chatMemoryRepository(chatMemoryRepository)
                .build();
    }

    @Bean
    public SessionMemoryAdvisor sessionMemoryAdvisor(DeepSeekChatModel chatModel) {
        DefaultSessionService sessionService = DefaultSessionService.builder()
                .sessionRepository(
//                        配置记忆存储策略<这里暂时选用基于内存的记忆存储>
                        InMemorySessionRepository.builder().build())
                .build();

        return SessionMemoryAdvisor.builder(sessionService)
                .defaultUserId("sonnie")
//                配置会话压缩的触发器，这里选用轮数触发器，即当会话轮数达到10轮时进行压缩
                .compactionTrigger(new TurnCountTrigger(10))
//                配置会话压缩策略，这里选用递归总结策略，即通过递归总结会话内容，只保留关键信息，最大保留1个事件，除重大小为2
                .compactionStrategy(RecursiveSummarizationCompactionStrategy
                        .builder(ChatClient.builder(chatModel).build())
                        .maxEventsToKeep(10)
                        .overlapSize(2)
                        .build())
                .build();
    }
}
