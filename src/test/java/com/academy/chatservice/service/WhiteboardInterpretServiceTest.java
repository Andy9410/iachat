package com.academy.chatservice.service;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class WhiteboardInterpretServiceTest {

    // Solo probamos métodos privados vía reflexión — ObjectProvider y Environment
    // nunca se usan en estos tests (solo interpret() los necesita), así que
    // pasamos mocks sin configuración.
    private final WhiteboardInterpretService service = new WhiteboardInterpretService(null, null, mock(Environment.class));

    // ── Helpers de reflexión ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <T> T invoke(String name, Class<?>[] paramTypes, Object... args) {
        try {
            Method method = WhiteboardInterpretService.class.getDeclaredMethod(name, paramTypes);
            method.setAccessible(true);
            return (T) method.invoke(service, args);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("Error invoking " + name, e);
        }
    }

    private boolean isLikelyMathExpression(String text) {
        return invoke("isLikelyMathExpression", new Class<?>[]{String.class}, text);
    }

    private String extractMathExpression(String text) {
        return invoke("extractMathExpression", new Class<?>[]{String.class}, text);
    }

    private String normalizeMode(String mode) {
        return invoke("normalizeMode", new Class<?>[]{String.class}, mode);
    }

    private String normalizeType(String type) {
        return invoke("normalizeType", new Class<?>[]{String.class}, type);
    }

    private boolean hasUsefulText(String text) {
        return invoke("hasUsefulText", new Class<?>[]{String.class}, text);
    }

    private String structuredText(List<Map<String, Object>> elements) {
        return invoke("structuredText", new Class<?>[]{List.class}, elements);
    }

    private Optional<String> firstEquationElementText(List<Map<String, Object>> elements) {
        return invoke("firstEquationElementText", new Class<?>[]{List.class}, elements);
    }

    @SuppressWarnings("unchecked")
    private String inferType(String text, List<Map<String, Object>> elements) {
        return invoke("inferType", new Class<?>[]{String.class, List.class}, text, elements);
    }

    private String semanticSummary(String type, String equation, String text, String structuredElements) {
        return invoke("semanticSummary", new Class<?>[]{String.class, String.class, String.class, String.class},
                type, equation, text, structuredElements);
    }

    // ── isLikelyMathExpression ────────────────────────────────────────────────

    @Nested
    class IsLikelyMathExpressionTest {

        @Test
        void nulo_o_vacio_devuelve_false() {
            assertThat(isLikelyMathExpression(null)).isFalse();
            assertThat(isLikelyMathExpression("")).isFalse();
            assertThat(isLikelyMathExpression("   ")).isFalse();
        }

        @Test
        void ecuacion_simple_con_igual_devuelve_true() {
            assertThat(isLikelyMathExpression("2x + 3 = 4")).isTrue();
            assertThat(isLikelyMathExpression("x + 5 = 10")).isTrue();
            assertThat(isLikelyMathExpression("y = 2x + 1")).isTrue();
        }

        @Test
        void desigualdad_devuelve_true() {
            assertThat(isLikelyMathExpression("x > 5")).isTrue();
            assertThat(isLikelyMathExpression("3x < 15")).isTrue();
        }

        @Test
        void derivada_devuelve_true() {
            assertThat(isLikelyMathExpression("dy/dx = 2x")).isTrue();
            assertThat(isLikelyMathExpression("dy/dx")).isTrue();
        }

        @Test
        void operacion_aritmetica_devuelve_true() {
            assertThat(isLikelyMathExpression("2 + 3 = 4")).isTrue();
        }

        @Test
        void fraccion_devuelve_true() {
            assertThat(isLikelyMathExpression("3/4")).isTrue();
        }

        @Test
        void texto_normal_devuelve_false() {
            assertThat(isLikelyMathExpression("Hola como estas")).isFalse();
            assertThat(isLikelyMathExpression("El perro corre")).isFalse();
        }

        @Test
        void numero_cerca_de_variable_devuelve_true() {
            assertThat(isLikelyMathExpression("2x")).isTrue();
        }

        @Test
        void potencia_devuelve_true() {
            assertThat(isLikelyMathExpression("x^2")).isTrue();
            assertThat(isLikelyMathExpression("x^2 + y^2 = 1")).isTrue();
        }

        @Test
        void integral_devuelve_true() {
            assertThat(isLikelyMathExpression("int x^2 dx")).isTrue();
        }
    }

    // ── extractMathExpression ─────────────────────────────────────────────────

    @Nested
    class ExtractMathExpressionTest {

        @Test
        void nulo_o_vacio_devuelve_null() {
            assertThat(extractMathExpression(null)).isNull();
            assertThat(extractMathExpression("")).isNull();
        }

        @Test
        void ecuacion_con_igual_se_extrae() {
            assertThat(extractMathExpression("la ecuacion 2x + 3 = 4 es simple"))
                    .contains("=");
        }

        @Test
        void derivada_se_extrae() {
            String result = extractMathExpression("calcular dy/dx = 2x");
            assertThat(result).isNotNull();
        }

        @Test
        void texto_sin_matematica_devuelve_null() {
            assertThat(extractMathExpression("Hola mundo")).isNull();
        }
    }

    // ── normalizeMode ─────────────────────────────────────────────────────────

    @Nested
    class NormalizeModeTest {

        @Test
        void nulo_o_vacio_devuelve_auto() {
            assertThat(normalizeMode(null)).isEqualTo("auto");
            assertThat(normalizeMode("")).isEqualTo("auto");
            assertThat(normalizeMode("   ")).isEqualTo("auto");
        }

        @Test
        void math_devuelve_math() {
            assertThat(normalizeMode("math")).isEqualTo("math");
            assertThat(normalizeMode("matemática")).isEqualTo("math");
            assertThat(normalizeMode("matematicas")).isEqualTo("math");
            assertThat(normalizeMode("MATEMATICAS")).isEqualTo("math");
        }

        @Test
        void algorithm_devuelve_algorithm() {
            assertThat(normalizeMode("algorithm")).isEqualTo("algorithm");
            assertThat(normalizeMode("algoritmo")).isEqualTo("algorithm");
            assertThat(normalizeMode("ALGORITMO")).isEqualTo("algorithm");
        }

        @Test
        void flowchart_devuelve_flowchart() {
            assertThat(normalizeMode("flowchart")).isEqualTo("flowchart");
            assertThat(normalizeMode("diagrama")).isEqualTo("flowchart");
        }

        @Test
        void text_devuelve_text() {
            assertThat(normalizeMode("text")).isEqualTo("text");
            assertThat(normalizeMode("texto")).isEqualTo("text");
        }

        @Test
        void valor_desconocido_devuelve_auto() {
            assertThat(normalizeMode("xyz")).isEqualTo("auto");
        }
    }

    // ── normalizeType ────────────────────────────────────────────────────────

    @Nested
    class NormalizeTypeTest {

        @Test
        void tipos_validos_pasan() {
            assertThat(normalizeType("math")).isEqualTo("math");
            assertThat(normalizeType("algorithm")).isEqualTo("algorithm");
            assertThat(normalizeType("flowchart")).isEqualTo("flowchart");
            assertThat(normalizeType("text")).isEqualTo("text");
        }

        @Test
        void tipo_desconocido_devuelve_unknown() {
            assertThat(normalizeType("anything")).isEqualTo("unknown");
            assertThat(normalizeType("")).isEqualTo("unknown");
        }

        @Test
        void normalizeType_con_null_lanza_NPE() {
            // La reflexión envuelve: NPE → InvocationTargetException → RuntimeException
            assertThatThrownBy(() -> normalizeType(null))
                    .isInstanceOf(RuntimeException.class)
                    .hasRootCauseInstanceOf(NullPointerException.class);
        }
    }

    // ── hasUsefulText ─────────────────────────────────────────────────────────

    @Nested
    class HasUsefulTextTest {

        @Test
        void texto_valido_devuelve_true() {
            assertThat(hasUsefulText("hola")).isTrue();
            assertThat(hasUsefulText("x + 1 = 2")).isTrue();
            assertThat(hasUsefulText("123")).isTrue();
        }

        @Test
        void demasiado_corto_devuelve_false() {
            assertThat(hasUsefulText(null)).isFalse();
            assertThat(hasUsefulText("")).isFalse();
            assertThat(hasUsefulText("a")).isFalse();  // length < 2
        }

        @Test
        void solo_espacios_devuelve_false() {
            assertThat(hasUsefulText("   ")).isFalse();
        }
    }

    // ── structuredText ────────────────────────────────────────────────────────

    @Nested
    class StructuredTextTest {

        @Test
        void elementos_con_texto_se_unen() {
            var elements = List.of(
                    Map.<String, Object>of("text", "hola"),
                    Map.<String, Object>of("text", "mundo")
            );
            assertThat(structuredText(elements)).isEqualTo("hola\nmundo");
        }

        @Test
        void elementos_sin_texto_se_ignoran() {
            var elements = List.of(
                    Map.<String, Object>of("type", "rect"),
                    Map.<String, Object>of("text", "solo")
            );
            assertThat(structuredText(elements)).isEqualTo("solo");
        }

        @Test
        void lista_vacia_devuelve_vacio() {
            assertThat(structuredText(List.of())).isEqualTo("");
        }

        @Test
        void texto_en_blanco_se_filtra() {
            var elements = List.of(
                    Map.<String, Object>of("text", "  "),
                    Map.<String, Object>of("text", "valido")
            );
            assertThat(structuredText(elements)).isEqualTo("valido");
        }
    }

    // ── firstEquationElementText ─────────────────────────────────────────────

    @Nested
    class FirstEquationElementTextTest {

        @Test
        void encuentra_primer_elemento_equation() {
            var elements = List.of(
                    Map.<String, Object>of("type", "rect", "text", "caja"),
                    Map.<String, Object>of("type", "equation", "text", "x^2 + 1"),
                    Map.<String, Object>of("type", "equation", "text", "segunda")
            );
            assertThat(firstEquationElementText(elements))
                    .hasValue("x^2 + 1");
        }

        @Test
        void sin_equation_devuelve_vacio() {
            var elements = List.of(
                    Map.<String, Object>of("type", "rect", "text", "caja")
            );
            assertThat(firstEquationElementText(elements)).isEmpty();
        }

        @Test
        void equation_con_texto_vacio_se_ignora() {
            var elements = List.of(
                    Map.<String, Object>of("type", "equation", "text", "  "),
                    Map.<String, Object>of("type", "equation", "text", "valida")
            );
            assertThat(firstEquationElementText(elements)).hasValue("valida");
        }
    }

    // ── inferType ─────────────────────────────────────────────────────────────

    @Nested
    class InferTypeTest {

        @Test
        void texto_matematico_devuelve_math() {
            assertThat(inferType("x + 2 = 5", List.of())).isEqualTo("math");
        }

        @Test
        void palabras_de_algoritmo_devuelve_algorithm() {
            // Nota: expresiones con desigualdades (>, <) se detectan como "math" primero
            assertThat(inferType("leer n y mostrar resultado", List.of())).isEqualTo("algorithm");
            assertThat(inferType("inicio del algoritmo", List.of())).isEqualTo("algorithm");
            assertThat(inferType("funcion factorial", List.of())).isEqualTo("algorithm");
        }

        @Test
        void elementos_de_diagrama_devuelve_flowchart() {
            var elements = List.of(
                    Map.<String, Object>of("type", "diamond"),
                    Map.<String, Object>of("type", "rect")
            );
            assertThat(inferType("texto generico", elements)).isEqualTo("flowchart");
        }

        @Test
        void texto_generico_devuelve_text() {
            assertThat(inferType("Hola mundo", List.of())).isEqualTo("text");
        }

        @Test
        void sin_texto_util_devuelve_unknown() {
            assertThat(inferType("", List.of())).isEqualTo("unknown");
        }
    }

    // ── semanticSummary ──────────────────────────────────────────────────────

    @Nested
    class SemanticSummaryTest {

        @Test
        void math_con_ecuacion_devuelve_resumen_especifico() {
            String result = semanticSummary("math", "x + 2 = 5", "bla", "estructura");
            assertThat(result).contains("x + 2 = 5");
            assertThat(result).contains("ecuación matemática");
        }

        @Test
        void texto_contenido_devuelve_resumen_de_texto() {
            String result = semanticSummary("text", null, "Hola mundo", "");
            assertThat(result).contains("Hola mundo");
        }

        @Test
        void sin_texto_devuelve_resumen_estructurado() {
            String result = semanticSummary("unknown", null, "", "caja → flecha");
            assertThat(result).contains("caja → flecha");
        }
    }
}
