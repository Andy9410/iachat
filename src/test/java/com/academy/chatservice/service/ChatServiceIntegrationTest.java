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
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

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

    @MockBean
    private DocumentSearchClient documentSearchClient;

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
        when(documentSearchClient.search(anyString(), anyString(), anyLong()))
                .thenReturn(DocumentSearchClient.SearchResult.empty());
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

    @Test
    void sugerencias_se_persisten_y_se_retornan_al_cargar_mensajes() {
        Long id = startConversation("¿Qué es POO?");

        chatService.finalizeStream(id, "Respuesta", List.of("¿Qué es POO?", "¿Qué es herencia?"));

        var page = chatService.getConversationMessages(id, USER_EMAIL, 20, null);
        var lastAssistant = page.messages().stream()
                .filter(m -> "assistant".equals(m.role()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("No assistant message found"));

        assertThat(lastAssistant.suggestions())
                .containsExactly("¿Qué es POO?", "¿Qué es herencia?");
    }

    @Test
    void sugerencias_vacias_retornan_lista_vacia() {
        Long id = startConversation("¿Qué es encapsulación?");

        chatService.finalizeStream(id, "Respuesta", List.of());

        var page = chatService.getConversationMessages(id, USER_EMAIL, 20, null);
        var lastAssistant = page.messages().stream()
                .filter(m -> "assistant".equals(m.role()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("No assistant message found"));

        assertThat(lastAssistant.suggestions()).isNotNull().isEmpty();
    }

    @Test
    void sugerencias_no_se_incluyen_en_mensajes_de_usuario() {
        Long id = startConversation("¿Qué es polimorfismo?");

        var page = chatService.getConversationMessages(id, USER_EMAIL, 20, null);
        var userMessages = page.messages().stream()
                .filter(m -> "user".equals(m.role()))
                .toList();

        assertThat(userMessages).isNotEmpty();
        userMessages.forEach(m -> assertThat(m.suggestions()).isNotNull().isEmpty());
    }

    @Test
    void prompt_usa_memoria_como_fallback_cuando_documento_no_tiene_contexto() {
        when(llmClient.generate(anyString())).thenReturn("La herencia permite reutilizar comportamiento.");
        Long id = startConversation("Explicame herencia en POO");

        var prep = chatService.prepareStream(
                new ChatRequest("¿Me lo resumís?", id, 123L, null, null, null),
                USER_EMAIL,
                FIRST_NAME
        );

        assertThat(prep.prompt())
                .contains("no se encontró información relevante")
                .contains("memoria conversacional")
                .contains("No encontré esta información en los documentos adjuntos")
                .contains("La herencia permite reutilizar comportamiento.");
    }

    @Test
    void hyde_no_sesga_consulta_hacia_programacion_cuando_el_documento_es_de_otro_tema() {
        when(llmClient.generate(anyString()))
                .thenReturn("Montevideo es la capital de Uruguay.")
                .thenReturn("Respuesta del tutor");

        chatService.process(
                new ChatRequest("¿Cuál es la capital de Uruguay?", null, 99L, null, null, null),
                USER_EMAIL,
                FIRST_NAME
        );

        ArgumentCaptor<String> hydePromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generate(hydePromptCaptor.capture());

        assertThat(hydePromptCaptor.getValue())
                .contains("Conservá el dominio real de la pregunta")
                .contains("¿Cuál es la capital de Uruguay?")
                .doesNotContain("PROGRAMACIÓN/DESARROLLO DE SOFTWARE");
    }
}
