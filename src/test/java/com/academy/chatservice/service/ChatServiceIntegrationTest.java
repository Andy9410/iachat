package com.academy.chatservice.service;

import com.academy.chatservice.model.ChatRequest;
import com.academy.chatservice.repository.ConversationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;

// threshold=6, window=2 (application.yml)
// Mensajes en pares (user+assistant): count=0,2,4,6,8,10...
// Fórmula: count > 6 → primer disparo al 5to request (count=8), y en cada request siguiente
@SpringBootTest
class ChatServiceIntegrationTest {

    @MockBean
    private LLMClient llmClient;

    @MockBean
    private EmbeddingClient embeddingClient;

    @MockBean
    private com.academy.chatservice.repository.MessageEmbeddingRepository messageEmbeddingRepository;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ConversationRepository conversationRepository;

    private final List<Long> createdIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1f, 0.2f, 0.3f));
    }

    @AfterEach
    void cleanup() {
        createdIds.forEach(conversationRepository::deleteById);
        createdIds.clear();
    }

    private Long startConversation(String firstMessage) {
        var r = chatService.process(new ChatRequest(firstMessage, null));
        createdIds.add(r.conversationId());
        return r.conversationId();
    }

    @Test
    void compactacion_guarda_summary_en_BD_al_quinto_request() {
        when(llmClient.generate(anyString())).thenReturn("Respuesta del tutor");
        when(llmClient.generate(argThat(p -> p != null && p.startsWith("Resume"))))
                .thenReturn("Resumen: POO incluye herencia, encapsulación e interfaces.");

        Long id = startConversation("¿Qué es POO?");
        chatService.process(new ChatRequest("¿Qué es herencia?", id));
        chatService.process(new ChatRequest("¿Qué es interfaz?", id));
        chatService.process(new ChatRequest("¿Qué es encapsulación?", id));
        chatService.process(new ChatRequest("¿Qué es polimorfismo?", id));  // count=8 → compacta

        var conv = conversationRepository.findById(id).orElseThrow();
        assertThat(conv.getSummary())
                .isNotNull()
                .isEqualTo("Resumen: POO incluye herencia, encapsulación e interfaces.");
    }

    @Test
    void compactacion_no_se_activa_con_cuatro_requests() {
        when(llmClient.generate(anyString())).thenReturn("Respuesta");

        Long id = startConversation("¿Qué es POO?");
        chatService.process(new ChatRequest("¿Qué es herencia?", id));
        chatService.process(new ChatRequest("¿Qué es interfaz?", id));
        chatService.process(new ChatRequest("¿Qué es encapsulación?", id));  // count=6 → no compacta

        var conv = conversationRepository.findById(id).orElseThrow();
        assertThat(conv.getSummary()).isNull();
    }

    @Test
    void compactacion_actualiza_summary_en_cada_request_posterior_al_umbral() {
        when(llmClient.generate(anyString())).thenReturn("Respuesta del tutor");
        when(llmClient.generate(argThat(p -> p != null && p.startsWith("Resume"))))
                .thenReturn("Resumen request 5", "Resumen request 6");

        Long id = startConversation("pregunta 1");
        for (int i = 2; i <= 6; i++) {
            chatService.process(new ChatRequest("pregunta " + i, id));
        }
        // req 5 → count=8 → compacta; req 6 → count=10 → compacta de nuevo

        var conv = conversationRepository.findById(id).orElseThrow();
        assertThat(conv.getSummary()).isEqualTo("Resumen request 6");
    }
}
