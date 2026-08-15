package com.sonnie.claude;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.util.List;

@SpringBootTest
public class TestDeepSeek {
    @Test
    public void testDeepSeek(@Autowired DeepSeekChatModel model) {
        System.out.println(model.call("你好，请问你是谁？"));
    }

    @Test
    public void testDeepSeekStream(@Autowired DeepSeekChatModel model) {
        Flux<String> stream = model.stream("你好，请问你是谁？");
        stream.toIterable().forEach(System.out::print);
    }

    @Test
    public void testChatOptions(@Autowired DeepSeekChatModel model) {
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .temperature(1.0) // 温度越低，输出越保守严谨收敛；温度越高，输出越多变、富有惊喜但有风险
                .maxTokens(30) // 用于限制模型生成的最大token数，默认32k，最大64k
                .stop(List.of(",")) //用于截断输出内容的模版词
                .build();
        Prompt prompt = Prompt.builder()
                .chatOptions(options)
                .content("请为我写一首描述春天的诗")
                .build();
        ChatResponse response = model.call(prompt);
        System.out.println(response.getResult().getOutput().getText());
    }

    @Test
    public void testChatReasoning(@Autowired DeepSeekChatModel model) {
        Prompt prompt = Prompt.builder()
                .content("请为我写一首描述春天的诗")
                .build();
        ChatResponse response = model.call(prompt);
        DeepSeekAssistantMessage assistantMessage = (DeepSeekAssistantMessage) response.getResult().getOutput();
        System.out.println(assistantMessage.getReasoningContent());
        System.out.println("---------------------------------------------------");
        System.out.println(assistantMessage.getText());
    }

    @Test
    public void testChatReasoningStream(@Autowired DeepSeekChatModel model) {
        Prompt prompt = Prompt.builder()
                .content("请为我写一首描述春天的诗")
                .build();
        Flux<ChatResponse> stream = model.stream(prompt);
        stream.toIterable().forEach(chatResponse -> {
            DeepSeekAssistantMessage assistantMessage = (DeepSeekAssistantMessage) chatResponse.getResult().getOutput();
            System.out.print(assistantMessage.getReasoningContent());
        });
        System.out.println("---------------------------------------------------");
        stream.toIterable().forEach(chatResponse -> {
            DeepSeekAssistantMessage assistantMessage = (DeepSeekAssistantMessage) chatResponse.getResult().getOutput();
            System.out.print(assistantMessage.getText());
        });
    }
}
