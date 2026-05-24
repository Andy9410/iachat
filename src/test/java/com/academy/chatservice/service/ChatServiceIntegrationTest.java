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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;

// archive-threshold=6, window=2 (test application.yml)
@SpringBootTest
class ChatServiceIntegrationTest {

    private static final String USER_EMAIL = "test@test.com";
    private static final String FIRST_NAME = "Andy";

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
        when(embeddingClient.embed(anyString()))
                .thenReturn(List.of(0.1f, 0.2f, 0.3f));

        when(messageEmbeddingRepository.findSimilar(
                anyString(),
                anyLong(),
                anyInt()
        )).thenReturn(List.of());

        when(llmClient.generate(anyString())).thenReturn("Respuesta del tutor");
    }

    @AfterEach
    void cleanup() {
        createdIds.forEach(conversationRepository::deleteById);
        createdIds.clear();
    }

    private Long startConversation(String firstMessage) {
        var r = chatService.process(
                new ChatRequest(firstMessage, null, null, null, null, null),
                USER_EMAIL,
                FIRST_NAME
        );
        createdIds.add(r.conversationId());
        return r.conversationId();
    }

    @Test
    void archivado_guarda_contexto_en_BD_al_superar_umbral() {
        Long id = startConversation("¿Qué es POO?");

        chatService.process(new ChatRequest("¿Qué es herencia?", id, null, null, null, null), USER_EMAIL, FIRST_NAME);
        chatService.process(new ChatRequest("¿Qué es interfaz?", id, null, null, null, null), USER_EMAIL, FIRST_NAME);
        chatService.process(new ChatRequest("¿Qué es encapsulación?", id, null, null, null, null), USER_EMAIL, FIRST_NAME);
        chatService.process(new ChatRequest("¿Qué es polimorfismo?", id, null, null, null, null), USER_EMAIL, FIRST_NAME);

        var conv = conversationRepository.findById(id).orElseThrow();

        assertThat(conv.getArchivedContext()).isNotNull().isNotBlank();
        assertThat(conv.getArchivedMessageCount()).isEqualTo(6);
        assertThat(conv.getArchivedContext()).contains("¿Qué es POO?");
    }

    @Test
    void archivado_no_se_activa_antes_del_umbral() {
        Long id = startConversation("¿Qué es POO?");

        chatService.process(new ChatRequest("¿Qué es herencia?", id, null, null, null, null), USER_EMAIL, FIRST_NAME);
        chatService.process(new ChatRequest("¿Qué es interfaz?", id, null, null, null, null), USER_EMAIL, FIRST_NAME);

        var conv = conversationRepository.findById(id).orElseThrow();

        assertThat(conv.getArchivedContext()).isNull();
        assertThat(conv.getArchivedMessageCount()).isEqualTo(0);
    }

    @Test
    void archivado_ocurre_solo_una_vez_aunque_haya_mas_mensajes() {
        Long id = startConversation("pregunta 1");

        for (int i = 2; i <= 7; i++) {
            chatService.process(
                    new ChatRequest("pregunta " + i, id, null, null, null, null),
                    USER_EMAIL,
                    FIRST_NAME
            );
        }

        var conv = conversationRepository.findById(id).orElseThrow();

        // Archive should have triggered exactly once — always 6 messages
        assertThat(conv.getArchivedMessageCount()).isEqualTo(6);
        assertThat(conv.getArchivedContext()).isNotNull();
    }
}
