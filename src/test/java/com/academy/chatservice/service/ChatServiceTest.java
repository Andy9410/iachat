package com.academy.chatservice.service;

import com.academy.chatservice.model.ChatRequest;
import com.academy.chatservice.model.Conversation;
import com.academy.chatservice.repository.ConversationRepository;
import com.academy.chatservice.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private LLMClient llmClient;

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageRepository messageRepository;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(llmClient, conversationRepository, messageRepository);
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

    private Conversation conversationMock(Long id) {
        var conv = mock(Conversation.class);
        when(conv.getId()).thenReturn(id);
        return conv;
    }
}
