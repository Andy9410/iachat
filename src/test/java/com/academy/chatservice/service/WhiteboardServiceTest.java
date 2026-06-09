package com.academy.chatservice.service;

import com.academy.chatservice.model.Conversation;
import com.academy.chatservice.model.Whiteboard;
import com.academy.chatservice.repository.ConversationRepository;
import com.academy.chatservice.repository.WhiteboardEntryRepository;
import com.academy.chatservice.repository.WhiteboardRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhiteboardServiceTest {

    private WhiteboardService service;

    @BeforeEach
    void setUp() {
        // Creamos una instancia real con mocks mínimos para probar helpers puros
        // Los helpers privados que probamos no necesitan dependencias
        service = new WhiteboardService(null, null, null, null, null, null);
    }

    // ── Helpers de reflexión ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <T> T invoke(String name, Class<?>[] paramTypes, Object... args) {
        try {
            Method method = WhiteboardService.class.getDeclaredMethod(name, paramTypes);
            method.setAccessible(true);
            return (T) method.invoke(service, args);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("Error invoking " + name, e);
        }
    }

    private String toPublicId(Long id) {
        return invoke("toPublicId", new Class<?>[]{Long.class}, id);
    }

    private Long parsePublicId(String id) {
        return invoke("parsePublicId", new Class<?>[]{String.class}, id);
    }

    private String normalizeOcr(String text) {
        return invoke("normalizeOcr", new Class<?>[]{String.class}, text);
    }

    @SuppressWarnings("unchecked")
    private String describeElement(Map<String, Object> el) {
        return invoke("describeElement", new Class<?>[]{Map.class}, el);
    }

    @SuppressWarnings("unchecked")
    private boolean containsText(List<Map<String, Object>> elements, String needle) {
        return invoke("containsText", new Class<?>[]{List.class, String.class}, elements, needle);
    }

    private String inferTypeFromInstruction(String instruction) {
        return invoke("inferType", new Class<?>[]{String.class}, instruction);
    }

    private String cleanSuggestionText(String instruction) {
        return invoke("cleanSuggestionText", new Class<?>[]{String.class}, instruction);
    }

    private String cleanSuggestionTitle(String instruction) {
        return invoke("cleanSuggestionTitle", new Class<?>[]{String.class}, instruction);
    }

    private String normalizeValue(String value) {
        return invoke("normalize", new Class<?>[]{String.class}, value);
    }

    private static void setPrivateId(Object target, Long id) {
        try {
            Field field = target.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, id);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Error setting id", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    @SuppressWarnings("unchecked")
    private String summarizeElements(List<Map<String, Object>> elements) {
        return invoke("summarizeElements", new Class<?>[]{List.class}, elements);
    }

    @SuppressWarnings("unchecked")
    private String summarizeStructured(List<Map<String, Object>> elements) {
        return invoke("summarizeStructured", new Class<?>[]{List.class}, elements);
    }

    @SuppressWarnings("unchecked")
    private String describeStructuredElement(Map<String, Object> el) {
        return invoke("describeStructuredElement", new Class<?>[]{Map.class}, el);
    }

    private boolean isSimpleMathExpression(String candidate) {
        return invoke("isSimpleMathExpression", new Class<?>[]{String.class}, candidate);
    }

    @SuppressWarnings("unchecked")
    private double confidence(double ocrConfidence, boolean hasUsefulOcr, List<Map<String, Object>> elements) {
        return invoke("confidence", new Class<?>[]{double.class, boolean.class, List.class},
                ocrConfidence, hasUsefulOcr, elements);
    }

    @Nested
    class ActiveWhiteboardTest {

        @Test
        void active_crea_pizarra_si_no_existe() {
            var conversation = new Conversation();
            setPrivateId(conversation, 295L);
            conversation.setUserEmail("learnsoftuy@edu.uy");

            var savedWhiteboards = new ArrayList<Whiteboard>();
            ConversationRepository conversationRepository = proxy(ConversationRepository.class, (target, method, args) -> {
                if ("findByIdAndUserEmail".equals(method.getName())) return Optional.of(conversation);
                throw new UnsupportedOperationException(method.getName());
            });
            WhiteboardRepository whiteboardRepository = proxy(WhiteboardRepository.class, (target, method, args) -> {
                if ("findFirstByConversationIdOrderByUpdatedAtDesc".equals(method.getName())) return Optional.empty();
                if ("save".equals(method.getName())) {
                    Whiteboard whiteboard = (Whiteboard) args[0];
                    setPrivateId(whiteboard, 7L);
                    savedWhiteboards.add(whiteboard);
                    return whiteboard;
                }
                throw new UnsupportedOperationException(method.getName());
            });

            var service = new WhiteboardService(
                    conversationRepository,
                    whiteboardRepository,
                    null,
                    null,
                    null,
                    new ObjectMapper()
            );

            var active = service.active(295L, "learnsoftuy@edu.uy");

            assertThat(active.id()).isEqualTo("wb_7");
            assertThat(active.conversationId()).isEqualTo(295L);
            assertThat(active.title()).isEqualTo("Resolución guiada");
            assertThat(active.data()).containsEntry("version", 1);
            assertThat(active.data()).containsKey("elements");
            assertThat(savedWhiteboards).hasSize(1);
        }
    }

    @Nested
    class TeachFragmentTest {

        @Test
        void buildTeachingPrompt_no_usa_tipo_pipe_como_ejemplo() {
            WhiteboardEntryRepository entryRepository = proxy(WhiteboardEntryRepository.class, (target, method, args) -> {
                if ("findByConversationIdOrderByOrderIndexAsc".equals(method.getName())) return List.of();
                throw new UnsupportedOperationException(method.getName());
            });
            var service = new WhiteboardService(null, null, entryRepository, null, null, new ObjectMapper());

            String prompt = service.buildTeachingPrompt(295L, null, 0, "2x + 6 = 9");

            assertThat(prompt).doesNotContain("TITLE|STEP");
            assertThat(prompt).contains("\"type\":\"TITLE\"");
            assertThat(prompt).contains("\"type\":\"FORMULA\"");
            assertThat(prompt).contains("No combines tipos con '|'");
        }

        @Test
        void parseTeachFragment_normaliza_tipo_con_pipes_y_extrae_formula() {
            String raw = """
                    {"blocks":[{"type":"TITLE|STEP|FORMULA|EXAMPLE|WARNING|$2x + 6 = 9","content":"La ecuación dice que dos veces el número más seis iguala a nueve."}],"question":"¿Qué hacemos con el 6?","isComplete":false}
                    """;

            var fragment = service.parseTeachFragment(raw);

            assertThat(fragment.question()).isEqualTo("¿Qué hacemos con el 6?");
            assertThat(fragment.isComplete()).isFalse();
            assertThat(fragment.blocks()).contains(
                    Map.of("type", "TEXT", "content", "La ecuación dice que dos veces el número más seis iguala a nueve."),
                    Map.of("type", "FORMULA", "content", "2x + 6 = 9")
            );
            assertThat(fragment.blocks())
                    .allSatisfy(block -> assertThat(block.get("type")).doesNotContain("|"));
        }

        @Test
        void parseTeachFragment_no_guarda_json_crudo_si_la_respuesta_viene_mal_formada() {
            String raw = """
                    {"blocks":[{"type":"TITLE|STEP|FORMULA|EXAMPLE|WARNING|$2x + 6 = 9","content":"Vamos a identificar el valor de cada variable paso a paso.","content":"La ecuación dice que dos veces el número más seis iguala a nueve."}],{"content":"Por ahora, restamos 6 de ambos lados."}],"question":"¿Qué haces para acercarte al resultado?","isComplete":false}
                    """;

            var fragment = service.parseTeachFragment(raw);

            assertThat(fragment.question()).isEqualTo("¿Qué haces para acercarte al resultado?");
            assertThat(fragment.blocks()).isNotEmpty();
            assertThat(fragment.blocks()).anySatisfy(block ->
                    assertThat(block).containsEntry("type", "FORMULA").containsEntry("content", "2x + 6 = 9"));
            assertThat(fragment.blocks())
                    .allSatisfy(block -> assertThat(block.get("content")).doesNotContain("\"blocks\""));
        }
    }

    // ── toPublicId / parsePublicId ────────────────────────────────────────────

    @Nested
    class PublicIdTest {

        @Test
        void toPublicId_prefija_con_wb_() {
            assertThat(toPublicId(42L)).isEqualTo("wb_42");
            assertThat(toPublicId(1L)).isEqualTo("wb_1");
        }

        @Test
        void parsePublicId_quita_prefijo_wb() {
            assertThat(parsePublicId("wb_42")).isEqualTo(42L);
            assertThat(parsePublicId("wb_1")).isEqualTo(1L);
        }

        @Test
        void parsePublicId_funciona_sin_prefijo() {
            assertThat(parsePublicId("42")).isEqualTo(42L);
        }

        @Test
        void parsePublicId_lanza_excepcion_si_id_invalido() {
            assertThatThrownBy(() -> parsePublicId("abc"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> parsePublicId("wb_abc"))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        void parsePublicId_lanza_excepcion_si_id_nulo() {
            assertThatThrownBy(() -> parsePublicId(null))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        void toPublicId_y_parsePublicId_son_inversos() {
            assertThat(parsePublicId(toPublicId(99L))).isEqualTo(99L);
            assertThat(parsePublicId(toPublicId(1000L))).isEqualTo(1000L);
        }
    }

    // ── normalizeOcr ─────────────────────────────────────────────────────────

    @Nested
    class NormalizeOcrTest {

        @Test
        void nulo_devuelve_vacio() {
            assertThat(normalizeOcr(null)).isEmpty();
        }

        @Test
        void normaliza_exponentes_unicode() {
            assertThat(normalizeOcr("x²")).isEqualTo("x^2");
            assertThat(normalizeOcr("x³")).isEqualTo("x^3");
            assertThat(normalizeOcr("xⁿ")).isEqualTo("x^n");
        }

        @Test
        void normaliza_guiones_y_division() {
            assertThat(normalizeOcr("3 − 2")).isEqualTo("3 - 2");
            assertThat(normalizeOcr("3 ÷ 2")).isEqualTo("3 / 2");
        }

        @Test
        void colapsa_espacios_multiples() {
            assertThat(normalizeOcr("hola    mundo")).isEqualTo("hola mundo");
        }

        @Test
        void trim_espacios() {
            assertThat(normalizeOcr("  texto  ")).isEqualTo("texto");
        }
    }

    // ── describeElement ──────────────────────────────────────────────────────

    @Nested
    class DescribeElementTest {

        @Test
        void elemento_con_texto_devuelve_el_texto() {
            var el = Map.<String, Object>of("type", "rect", "text", "Inicio");
            assertThat(describeElement(el)).isEqualTo("Inicio");
        }

        @Test
        void rectangulo_sin_texto_devuelve_rectangulo() {
            var el = Map.<String, Object>of("type", "rect");
            assertThat(describeElement(el)).isEqualTo("rectángulo");
        }

        @Test
        void circulo_devuelve_circulo() {
            var el = Map.<String, Object>of("type", "circle");
            assertThat(describeElement(el)).isEqualTo("círculo");
        }

        @Test
        void decision_devuelve_decision() {
            var el = Map.<String, Object>of("type", "diamond");
            assertThat(describeElement(el)).isEqualTo("decisión");
        }

        @Test
        void flecha_devuelve_flecha() {
            var el = Map.<String, Object>of("type", "arrow");
            assertThat(describeElement(el)).isEqualTo("flecha");
        }

        @Test
        void trazo_devuelve_trazo() {
            var el = Map.<String, Object>of("type", "path");
            assertThat(describeElement(el)).isEqualTo("trazo");
        }

        @Test
        void tipo_desconocido_devuelve_el_tipo() {
            var el = Map.<String, Object>of("type", "custom");
            assertThat(describeElement(el)).isEqualTo("custom");
        }
    }

    // ── containsText ─────────────────────────────────────────────────────────

    @Nested
    class ContainsTextTest {

        @Test
        void encuentra_texto_en_elementos() {
            var elements = List.of(
                    Map.<String, Object>of("text", "Inicio del algoritmo"),
                    Map.<String, Object>of("text", "Fin")
            );
            assertThat(containsText(elements, "inicio")).isTrue();
        }

        @Test
        void no_encuentra_texto_ausente() {
            var elements = List.of(
                    Map.<String, Object>of("text", "Paso 1"),
                    Map.<String, Object>of("text", "Paso 2")
            );
            assertThat(containsText(elements, "inicio")).isFalse();
        }

        @Test
        void elementos_sin_texto_no_causan_error() {
            var elements = List.of(
                    Map.<String, Object>of("type", "rect")
            );
            assertThat(containsText(elements, "texto")).isFalse();
        }
    }

    // ── inferType (para sugerencias) ─────────────────────────────────────────

    @Nested
    class InferTypeForSuggestionsTest {

        @Test
        void condicion_devuelve_diamond() {
            assertThat(inferTypeFromInstruction("Agregar condición x > 0")).isEqualTo("diamond");
            assertThat(inferTypeFromInstruction("condicion de parada")).isEqualTo("diamond");
        }

        @Test
        void flecha_devuelve_arrow() {
            assertThat(inferTypeFromInstruction("Agregar flecha al siguiente paso")).isEqualTo("arrow");
        }

        @Test
        void circulo_devuelve_circle() {
            assertThat(inferTypeFromInstruction("Agregar círculo de fin")).isEqualTo("circle");
            assertThat(inferTypeFromInstruction("nodo final")).isEqualTo("circle");
        }

        @Test
        void default_devuelve_rect() {
            assertThat(inferTypeFromInstruction("Agregar paso")).isEqualTo("rect");
            assertThat(inferTypeFromInstruction("cualquier cosa")).isEqualTo("rect");
        }
    }

    // ── cleanSuggestionText / cleanSuggestionTitle ───────────────────────────

    @Nested
    class CleanSuggestionTest {

        @Test
        void cleanSuggestionText_quita_prefijo_agregar() {
            assertThat(cleanSuggestionText("Agregar paso adicional"))
                    .isEqualTo("paso adicional");
        }

        @Test
        void cleanSuggestionText_sin_prefijo_devuelve_igual() {
            assertThat(cleanSuggestionText("Paso adicional"))
                    .isEqualTo("Paso adicional");
        }

        @Test
        void cleanSuggestionTitle_acorta_a_60_chars() {
            String largo = "a".repeat(100);
            assertThat(cleanSuggestionTitle(largo)).hasSize(60);
        }

        @Test
        void cleanSuggestionTitle_menor_a_60_pasa_igual() {
            assertThat(cleanSuggestionTitle("corto")).isEqualTo("corto");
        }
    }

    // ── normalize ────────────────────────────────────────────────────────────

    @Nested
    class NormalizeValueTest {

        @Test
        void nulo_devuelve_null() {
            assertThat(normalizeValue(null)).isNull();
        }

        @Test
        void blanco_devuelve_null() {
            assertThat(normalizeValue("")).isNull();
            assertThat(normalizeValue("   ")).isNull();
        }

        @Test
        void valor_con_espacios_se_trimmea() {
            assertThat(normalizeValue("  hola  ")).isEqualTo("hola");
        }
    }

    // ── summarizeElements ────────────────────────────────────────────────────

    @Nested
    class SummarizeElementsTest {

        @Test
        void elementos_vacios_devuelve_pizarra_vacia() {
            assertThat(summarizeElements(List.of())).isEqualTo("Pizarra vacía.");
        }

        @Test
        void elementos_se_describen_en_secuencia() {
            var elements = List.of(
                    Map.<String, Object>of("text", "Inicio"),
                    Map.<String, Object>of("type", "arrow"),
                    Map.<String, Object>of("text", "Fin")
            );
            assertThat(summarizeElements(elements)).isEqualTo("Inicio → flecha → Fin");
        }
    }

    // ── summarizeStructured ──────────────────────────────────────────────────

    @Nested
    class SummarizeStructuredTest {

        @Test
        void elementos_vacios_devuelve_pizarra_vacia() {
            assertThat(summarizeStructured(List.of())).isEqualTo("Pizarra vacía.");
        }

        @Test
        void incluye_conteo_de_flechas_y_trazos() {
            var elements = List.of(
                    Map.<String, Object>of("type", "rect", "text", "Inicio"),
                    Map.<String, Object>of("type", "arrow"),
                    Map.<String, Object>of("type", "rect", "text", "Fin"),
                    Map.<String, Object>of("type", "path")
            );
            String result = summarizeStructured(elements);
            assertThat(result).contains("1 flecha(s)");
            assertThat(result).contains("1 trazo(s) manuscrito(s)");
        }

        @Test
        void solo_paths_devuelve_conteo_de_trazos() {
            var elements = List.of(
                    Map.<String, Object>of("type", "path")
            );
            assertThat(summarizeStructured(elements)).isEqualTo("1 trazo(s) manuscrito(s)");
        }

        @Test
        void solo_elementos_desconocidos_devuelve_mensaje_sin_estructura() {
            var elements = List.of(
                    Map.<String, Object>of("type", "custom_type")
            );
            assertThat(summarizeStructured(elements)).contains("Trazos manuscritos sin estructura reconocible");
        }
    }

    // ── describeStructuredElement ────────────────────────────────────────────

    @Nested
    class DescribeStructuredElementTest {

        @Test
        void rect_con_texto_devuelve_caja_con_texto() {
            var el = Map.<String, Object>of("type", "rect", "text", "Inicio");
            assertThat(describeStructuredElement(el)).isEqualTo("caja \"Inicio\"");
        }

        @Test
        void rect_sin_texto_devuelve_caja() {
            var el = Map.<String, Object>of("type", "rect");
            assertThat(describeStructuredElement(el)).isEqualTo("caja");
        }

        @Test
        void diamond_devuelve_decision() {
            var el = Map.<String, Object>of("type", "diamond", "text", "x > 0");
            assertThat(describeStructuredElement(el)).isEqualTo("decisión \"x > 0\"");
        }

        @Test
        void path_devuelve_vacio() {
            var el = Map.<String, Object>of("type", "path");
            assertThat(describeStructuredElement(el)).isEmpty();
        }

        @Test
        void tipo_desconocido_devuelve_vacio() {
            var el = Map.<String, Object>of("type", "unknown_type");
            assertThat(describeStructuredElement(el)).isEmpty();
        }
    }

    // ── isSimpleMathExpression ───────────────────────────────────────────────

    @Nested
    class IsSimpleMathExpressionTest {

        @Test
        void expresion_valida_devuelve_true() {
            assertThat(isSimpleMathExpression("x + 2 = 5")).isTrue();
            assertThat(isSimpleMathExpression("2x = 10")).isTrue();
        }

        @Test
        void muy_corta_devuelve_false() {
            assertThat(isSimpleMathExpression("ab")).isFalse();
        }

        @Test
        void muy_larga_devuelve_false() {
            assertThat(isSimpleMathExpression("a".repeat(81))).isFalse();
        }

        @Test
        void sin_igual_devuelve_false() {
            assertThat(isSimpleMathExpression("x + 2")).isFalse();
        }

        @Test
        void sin_numeros_devuelve_false() {
            assertThat(isSimpleMathExpression("x + y = z")).isFalse();
        }

        @Test
        void nulo_devuelve_false() {
            assertThat(isSimpleMathExpression(null)).isFalse();
        }
    }

    // ── confidence ───────────────────────────────────────────────────────────

    @Nested
    class ConfidenceTest {

        @Test
        void con_ocr_util_devuelve_valor_entre_045_y_095() {
            double result = confidence(0.8, true, List.of(
                    Map.<String, Object>of("type", "rect")
            ));
            assertThat(result).isBetween(0.45, 0.95);
        }

        @Test
        void sin_ocr_pero_con_elementos_devuelve_035() {
            double result = confidence(0.0, false, List.of(
                    Map.<String, Object>of("type", "rect")
            ));
            assertThat(result).isEqualTo(0.35);
        }

        @Test
        void sin_ocr_y_sin_elementos_devuelve_00() {
            double result = confidence(0.0, false, List.of());
            assertThat(result).isEqualTo(0.0);
        }
    }
}
