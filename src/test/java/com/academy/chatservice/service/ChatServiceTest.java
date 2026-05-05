package com.academy.chatservice.service;

import com.academy.chatservice.model.ChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private LLMClient llmClient;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(llmClient);
    }

    @Test
    void process_conMensajeValido_llamaAlLLMYRetornaRespuesta() {
        when(llmClient.generate(anyString())).thenReturn("Respuesta del LLM");

        var response = chatService.process(new ChatRequest("¿Qué es Java?"));

        assertThat(response.response()).isEqualTo("Respuesta del LLM");
        verify(llmClient, times(1)).generate(anyString());
    }

    @Test
    void process_verificaQueElPromptContieneElMensaje() {
        when(llmClient.generate(anyString())).thenReturn("ok");

        chatService.process(new ChatRequest("¿Qué es un bucle?"));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generate(captor.capture());

        assertThat(captor.getValue()).contains("¿Qué es un bucle?");
    }

    @Test
    void process_conMensajeSoloEspacios_lanzaExcepcion() {
        var request = new ChatRequest("   ");

        assertThatThrownBy(() -> chatService.process(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El mensaje no puede estar vacío");
    }

    @Test
    void process_conMensajeConEspacios_loTrimea() {
        when(llmClient.generate(anyString())).thenReturn("ok");

        var captor = ArgumentCaptor.forClass(String.class);
        chatService.process(new ChatRequest("  ¿Qué es un objeto?  "));

        verify(llmClient).generate(captor.capture());
        assertThat(captor.getValue()).contains("¿Qué es un objeto?");
    }
}