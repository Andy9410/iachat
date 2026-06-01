package com.academy.chatservice.service.tools;

import com.academy.chatservice.model.tools.LLMToolResponse;
import com.academy.chatservice.model.tools.ToolCall;
import com.academy.chatservice.model.tools.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRegistryTest {

    // ── DefinitionsTest y ExecuteTest necesitan mocks ─────────────────────────

    @Nested
    class WithToolsTest {

        private ChatTool<?> toolA;
        private ChatTool<?> toolB;
        private ToolRegistry registry;

        @BeforeEach
        void setUp() {
            toolA = mock(ChatTool.class);
            toolB = mock(ChatTool.class);

            when(toolA.definition()).thenReturn(new ToolDefinition(
                    "tool_a", "Tool A description",
                    Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string")))
            ));
            when(toolA.argumentType()).thenAnswer(invocation -> Object.class);

            when(toolB.definition()).thenReturn(new ToolDefinition(
                    "tool_b", "Tool B description",
                    Map.of("type", "object", "properties", Map.of("id", Map.of("type", "number")))
            ));
            when(toolB.argumentType()).thenAnswer(invocation -> Object.class);

            registry = new ToolRegistry(new ObjectMapper(), List.of(toolA, toolB));
        }

        // ── definitions ───────────────────────────────────────────────────────

        @Nested
        class DefinitionsTest {

            @Test
            void devuelve_las_definiciones_de_todas_las_tools_registradas() {
                List<ToolDefinition> defs = registry.definitions();
                assertThat(defs).hasSize(2);
                assertThat(defs).extracting(ToolDefinition::name)
                        .containsExactly("tool_a", "tool_b");
            }

            @Test
            void devuelve_descripciones_correctas() {
                List<ToolDefinition> defs = registry.definitions();
                assertThat(defs.get(0).description()).isEqualTo("Tool A description");
                assertThat(defs.get(1).description()).isEqualTo("Tool B description");
            }

            @Test
            void mantiene_el_orden_de_registro() {
                List<ToolDefinition> defs = registry.definitions();
                assertThat(defs.get(0).name()).isEqualTo("tool_a");
                assertThat(defs.get(1).name()).isEqualTo("tool_b");
            }
        }

        // ── execute ──────────────────────────────────────────────────────────

        @Nested
        class ExecuteTest {

            @Test
            void ejecuta_tool_existente_con_argumentos_validos() {
                when(toolA.execute(any())).thenReturn("resultado");

                Object result = registry.execute("tool_a", "{\"name\":\"test\"}");
                assertThat(result).isEqualTo("resultado");
            }

            @Test
            void lanza_excepcion_si_tool_no_existe() {
                assertThatThrownBy(() -> registry.execute("tool_inexistente", "{}"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Tool no registrada");
            }

            @Test
            void lanza_excepcion_si_argumentos_son_invalidos_JSON() {
                assertThatThrownBy(() -> registry.execute("tool_a", "no-es-json"))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    // ── LLMToolResponse (no necesita mocks ni registry) ──────────────────────

    @Nested
    class LLMToolResponseTest {

        @Test
        void hasToolCalls_devuelve_true_si_hay_tool_calls() {
            var response = new LLMToolResponse("", List.of(new ToolCall("1", "tool_a", "{}")));
            assertThat(response.hasToolCalls()).isTrue();
        }

        @Test
        void hasToolCalls_devuelve_false_sin_tool_calls() {
            var response = new LLMToolResponse("contenido", List.of());
            assertThat(response.hasToolCalls()).isFalse();
        }

        @Test
        void hasToolCalls_devuelve_false_si_lista_es_null() {
            var response = new LLMToolResponse("contenido", null);
            assertThat(response.hasToolCalls()).isFalse();
        }
    }
}
