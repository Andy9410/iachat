package com.academy.chatservice;

import com.academy.chatservice.config.ChatContextProperties;
import com.academy.chatservice.config.CloudflareProperties;
import com.academy.chatservice.config.DocumentServiceProperties;
import com.academy.chatservice.config.GroqProperties;
import com.academy.chatservice.config.OllamaProperties;
import com.academy.chatservice.config.OpenRouterProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({OllamaProperties.class, ChatContextProperties.class, GroqProperties.class, OpenRouterProperties.class, CloudflareProperties.class, DocumentServiceProperties.class})

public class ChatServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
