/**
 * 会话记忆与压缩相关的 Spring 配置。
 *
 * <p>本类集中声明本项目里会话级（in-session）记忆相关的 Bean：
 * <ul>
 *   <li>{@code ChatMemory}：基于「滑动窗口」的消息记忆，供 Spring AI 原生的 ChatMemoryAdvisor 使用</li>
 *   <li>{@code SessionMemoryAdvisor}：spring-ai-session 提供的「按会话隔离 + 自动压缩」高级 Advisor</li>
 * </ul>
 *
 * <p>注意：本项目中实际并未在 ChatClient 上装配原生的 ChatMemoryAdvisor，而是通过 SessionMemoryAdvisor
 * 实现更完整的能力（轮数触发压缩、递归总结压缩策略等）。
 */
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
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会话记忆与压缩策略配置类。
 */
@Configuration
public class ChatMemoryConfiguration {

    /**
     * 注册一个 Spring AI 原生 ChatMemory Bean：滑动窗口实现，最多保留 10 条消息。
     *
     * <p>这里依赖 ChatMemoryRepository 是为了后续可随时替换成 JDBC 等持久化实现；
     * 当前 spring-ai-starter 自动配置会注入一个 InMemoryChatMemoryRepository，
     * 因此本 Bean 即等价于「内存里保留最近 10 条消息」。
     *
     * @param chatMemoryRepository Spring AI 自动注入的消息仓库
     * @return 滑动窗口实现的 ChatMemory 实例
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        // 使用 MessageWindowChatMemory 的 Builder 构造：maxMessages 决定保留窗口大小，chatMemoryRepository 决定底层持久化
        return MessageWindowChatMemory.builder()
                // 滑动窗口大小：仅保留最近 10 条消息，超出后会自动丢弃最早消息
                .maxMessages(10)
                // 底层消息仓库（默认内存版，可替换为 JDBC 版以实现持久化）
                .chatMemoryRepository(chatMemoryRepository)
                // 构建不可变的 ChatMemory 实例
                .build();
    }

    /**
     * 注册会话级记忆 Advisor。
     *
     * <p>关键概念：
     * <ul>
     *   <li><b>DefaultSessionService</b>：spring-ai-session 提供的会话服务，统一管理会话元数据、消息存取与压缩调度</li>
     *   <li><b>InMemorySessionRepository</b>：基于内存的会话仓库（重启即清空），适合本地调试</li>
     *   <li><b>SessionMemoryAdvisor</b>：在 ChatClient 调用前后注入 / 读取会话上下文</li>
     *   <li><b>compactionTrigger</b>：决定什么时候触发压缩（这里用「轮数达到 10」作为触发条件）</li>
     *   <li><b>compactionStrategy</b>：具体压缩算法（这里用「递归总结」，调用 LLM 生成摘要）</li>
     * </ul>
     *
     * @param chatModel DeepSeek ChatModel，用于在递归总结时调用 LLM
     * @return 装配好的 SessionMemoryAdvisor
     */
    @Bean
    public SessionMemoryAdvisor sessionMemoryAdvisor(DeepSeekChatModel chatModel) {
        // 构建 DefaultSessionService：先选择会话仓库，再选压缩触发器与策略
        DefaultSessionService sessionService = DefaultSessionService.builder()
                .sessionRepository(
//                        配置记忆存储策略<这里暂时选用基于内存的记忆存储>
                        // InMemorySessionRepository：内存版会话仓库；重启进程后会话内容丢失（开发期可接受）
                        InMemorySessionRepository.builder().build())
                .build();

        // 用 builder 把 sessionService 装配到 Advisor，并配置触发器与压缩策略
        return SessionMemoryAdvisor.builder(sessionService)
                // 默认用户 ID：未显式传入 userId 的请求都会归属于 sonnie
                .defaultUserId("sonnie")
//                配置会话压缩的触发器，这里选用轮数触发器，即当会话轮数达到10轮时进行压缩
                // 压缩触发器：会话轮数达到 10 轮时触发压缩（避免长会话 token 爆炸）
                .compactionTrigger(new TurnCountTrigger(10))
//                配置会话压缩策略，这里选用递归总结策略，即通过递归总结会话内容，只保留关键信息，最大保留1个事件，除重大小为2
                // 压缩策略：递归总结 —— 调用 LLM 把历史消息浓缩为摘要，仅保留最近 10 个事件，前后重叠 2 个事件以保证上下文连贯
                .compactionStrategy(RecursiveSummarizationCompactionStrategy
                        // 递归总结需要调用 LLM，这里把 DeepSeek ChatModel 包装成 ChatClient 传入
                        .builder(ChatClient.builder(chatModel).build())
                        // 压缩后保留的最大事件数（最近的事件不进入摘要）
                        .maxEventsToKeep(10)
                        // 摘要与未摘要事件的重叠大小，避免压缩边界处丢失关键上下文
                        .overlapSize(2)
                        .build())
                .build();
    }
}
