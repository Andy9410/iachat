package com.academy.chatservice.controller;

import com.academy.chatservice.config.SecurityConfig;
import com.academy.chatservice.model.ChatRequest;
import com.academy.chatservice.model.ChatResponse;
import com.academy.chatservice.service.ChatService;
import com.academy.chatservice.service.LLMClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
@Import(SecurityConfig.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ChatService chatService;

    @MockBean
    private LLMClient llmClient;

    @Test
    void health_debeRetornarOK() throws Exception {

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));
    }

    @Test
    @WithMockUser
    void chat_conMensajeValido_debeRetornarRespuesta() throws Exception {

        var request = new ChatRequest(
                "¿Qué es una variable?",
                null,
                null,
                null,
                null
        );

        var response = new ChatResponse(
                "Una variable es un espacio en memoria...",
                1L
        );

        when(chatService.process(
                eq(request),
                any(),
                any()
        )).thenReturn(response);

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response")
                        .value("Una variable es un espacio en memoria..."))
                .andExpect(jsonPath("$.conversationId")
                        .value(1));
    }

    @Test
    @WithMockUser
    void chat_conConversacionExistente_debeRetornarMismoId() throws Exception {

        var request = new ChatRequest(
                "¿Qué es herencia?",
                5L,
                null,
                null,
                null
        );

        var response = new ChatResponse(
                "La herencia es...",
                5L
        );

        when(chatService.process(
                eq(request),
                any(),
                any()
        )).thenReturn(response);

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId")
                        .value(5));
    }

    @Test
    @WithMockUser
    void chat_conBodyVacio_debeRetornar400() throws Exception {

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}