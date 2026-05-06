package com.academy.chatservice.service;

import com.academy.chatservice.config.ChatContextProperties;
import com.academy.chatservice.model.ChatRequest;
import com.academy.chatservice.model.Conversation;
import com.academy.chatservice.model.Message;
import com.academy.chatservice.repository.ConversationRepository;
import com.academy.chatservice.repository.MessageEmbeddingRepository;
import com.academy.chatservice.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private LLMClient llmClient;

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private MessageEmbeddingRepository messageEmbeddingRepository;

    private ChatService chatService;

    private final ChatContextProperties contextProps = new ChatContextProperties(4, 6);

    @BeforeEach
    void setUp() {
        lenient().when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1f, 0.2f, 0.3f));
        chatService = new ChatService(llmClient, embeddingClient, conversationRepository,
                messageRepository, messageEmbeddingRepository, contextProps);
    }

    @Test
    void process_conMensajeValido_creaConversacionYRetornaRespuesta() {
        var conv = conversationMock(1L);
        when(conversationRepository.save(any())).thenReturn(conv);
        when(llmClient.generate(anyString())).thenReturn("Respuesta del LLM");

        var response = chatService.process(new ChatRequest("¿Qué es Java?", null));

        assertThat(response.response()).isEqualTo("Respuesta del LLM");
        assertThat(response.conversationId()).isEqualTo(1L);
        verify(llmClient, times(1)).generate(anyString());
        verify(messageRepository, times(2)).save(any());
    }

    @Test
    void process_conConversacionExistente_reutilizaConversacion() {
        var conv = conversationMock(42L);
        when(conversationRepository.findById(42L)).thenReturn(Optional.of(conv));
        when(llmClient.generate(anyString())).thenReturn("ok");

        var response = chatService.process(new ChatRequest("¿Qué es herencia?", 42L));

        assertThat(response.conversationId()).isEqualTo(42L);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void process_conConversacionInexistente_lanzaExcepcion() {
        when(conversationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.process(new ChatRequest("hola", 99L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    @Test
    void process_verificaQueElPromptContieneElMensaje() {
        var conv = conversationMock(1L);
        when(conversationRepository.save(any())).thenReturn(conv);
        when(llmClient.generate(anyString())).thenReturn("ok");

        chatService.process(new ChatRequest("¿Qué es un bucle?", null));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generate(captor.capture());
        assertThat(captor.getValue()).contains("¿Qué es un bucle?");
    }

    @Test
    void process_conMensajeSoloEspacios_lanzaExcepcion() {
        assertThatThrownBy(() -> chatService.process(new ChatRequest("   ", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El mensaje no puede estar vacío");
    }

    @Test
    void process_conMensajeConEspacios_loTrimea() {
        var conv = conversationMock(1L);
        when(conversationRepository.save(any())).thenReturn(conv);
        when(llmClient.generate(anyString())).thenReturn("ok");

        chatService.process(new ChatRequest("  ¿Qué es un objeto?  ", null));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generate(captor.capture());
        assertThat(captor.getValue()).contains("¿Qué es un objeto?");
    }

    // --- Tests de compactación ---
    // contextProps: windowSize=4, compactionThreshold=6
    // Fórmula: messageCount > 6 AND (messageCount - 6) % 4 == 0
    // Primer disparo: messageCount=10, segundo: messageCount=14

    @Test
    void compactacion_seDispara_alSuperarUmbralEnPrimerCiclo() {
        // messageCount=10 → (10-6)%4=0 → se compacta, toSummarize=6
        var conv = conversationMock(10L);
        when(conversationRepository.findById(10L)).thenReturn(Optional.of(conv));
        when(messageRepository.countByConversationId(10L)).thenReturn(10L);
        when(messageRepository.findFirstN(eq(10L), eq(6))).thenReturn(List.of());
        when(messageRepository.findLastN(eq(10L), eq(4))).thenReturn(List.of());
        when(llmClient.generate(anyString())).thenReturn("resumen generado", "respuesta del tutor");

        var response = chatService.process(new ChatRequest("pregunta", 10L));

        verify(llmClient, times(2)).generate(anyString());
        verify(conv).setSummary("resumen generado");
        verify(conversationRepository).save(conv);
        assertThat(response.conversationId()).isEqualTo(10L);
    }

    @Test
    void compactacion_noSeDispara_conMensajesIgualesAlUmbral() {
        // messageCount=6 → 6 <= threshold → no se compacta
        var conv = conversationMock(10L);
        when(conversationRepository.findById(10L)).thenReturn(Optional.of(conv));
        when(messageRepository.countByConversationId(10L)).thenReturn(6L);
        when(llmClient.generate(anyString())).thenReturn("respuesta");

        chatService.process(new ChatRequest("pregunta", 10L));

        verify(llmClient, times(1)).generate(anyString());
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void compactacion_noSeDispara_fueraDelCicloDeCompactacion() {
        // messageCount=8 → (8-6)%4=2 ≠ 0 → no se compacta
        var conv = conversationMock(10L);
        when(conversationRepository.findById(10L)).thenReturn(Optional.of(conv));
        when(messageRepository.countByConversationId(10L)).thenReturn(8L);
        when(llmClient.generate(anyString())).thenReturn("respuesta");

        chatService.process(new ChatRequest("pregunta", 10L));

        verify(llmClient, times(1)).generate(anyString());
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void compactacion_seDispara_enSegundoCiclo() {
        // messageCount=14 → (14-6)%4=0 → se compacta, toSummarize=10
        var conv = conversationMock(10L);
        when(conversationRepository.findById(10L)).thenReturn(Optional.of(conv));
        when(messageRepository.countByConversationId(10L)).thenReturn(14L);
        when(messageRepository.findFirstN(eq(10L), eq(10))).thenReturn(List.of());
        when(messageRepository.findLastN(eq(10L), eq(4))).thenReturn(List.of());
        when(llmClient.generate(anyString())).thenReturn("resumen ciclo 2", "respuesta");

        chatService.process(new ChatRequest("pregunta", 10L));

        verify(llmClient, times(2)).generate(anyString());
        verify(conv).setSummary("resumen ciclo 2");
    }

    @Test
    void compactacion_elPromptDeResumenContieneElHistorial() {
        var conv = conversationMock(10L);
        when(conversationRepository.findById(10L)).thenReturn(Optional.of(conv));
        when(messageRepository.countByConversationId(10L)).thenReturn(10L);

        var msg = mock(Message.class);
        when(msg.getRole()).thenReturn(Message.Role.user);
        when(msg.getContent()).thenReturn("contenido de prueba");
        when(messageRepository.findFirstN(eq(10L), eq(6))).thenReturn(List.of(msg));
        when(messageRepository.findLastN(eq(10L), eq(4))).thenReturn(List.of());
        when(llmClient.generate(anyString())).thenReturn("resumen", "respuesta");

        chatService.process(new ChatRequest("pregunta", 10L));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(llmClient, times(2)).generate(captor.capture());
        assertThat(captor.getAllValues().get(0))
                .contains("Resume")
                .contains("contenido de prueba");
    }

    private Conversation conversationMock(Long id) {
        var conv = mock(Conversation.class);
        when(conv.getId()).thenReturn(id);
        return conv;
    }
}
