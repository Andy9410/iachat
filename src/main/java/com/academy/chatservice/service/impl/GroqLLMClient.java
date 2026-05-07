package com.academy.chatservice.service.impl;

import com.academy.chatservice.config.GroqProperties;
import com.academy.chatservice.service.LLMClient;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "groq")
public class GroqLLMClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(GroqLLMClient.class);

    private final OpenAIClient client;
    private final GroqProperties props;

    public GroqLLMClient(GroqProperties props) {
        this.props = props;
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(props.apiKey())
                .baseUrl(props.baseUrl() + "/openai/v1")
                .build();
    }

    @Override
    public String generate(String prompt) {
        try {
            var params = ChatCompletionCreateParams.builder()
                    .model(props.model())
                    .addUserMessage(prompt)
                    .build();

            var completion = client.chat().completions().create(params);
            return completion.choices().get(0).message().content().orElse("");

        } catch (Exception e) {
            log.error("Error llamando a Groq: {}", e.getMessage(), e);
            throw new RuntimeException("Error al llamar a Groq", e);
        }
    }
}
