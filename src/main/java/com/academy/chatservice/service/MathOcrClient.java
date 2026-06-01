package com.academy.chatservice.service;

import com.academy.chatservice.model.MathOcrResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Cliente HTTP para el endpoint de OCR matemático del document-service.
 *
 * Llama a POST /ocr/math con una imagen base64 y recibe LaTeX estructurado.
 */
@Component
public class MathOcrClient {

    private static final Logger log = LoggerFactory.getLogger(MathOcrClient.class);

    private static final String OCR_MATH_PATH = "/ocr/math";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public MathOcrClient(
            ObjectMapper objectMapper,
            @Value("${document-service.base-url:http://document-service:8080}") String baseUrl
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Envía una imagen al servicio de OCR matemático y devuelve LaTeX.
     *
     * @param imageBase64 Imagen codificada en base64 (con prefijo data:image)
     * @return MathOcrResult con el LaTeX detectado
     */
    public MathOcrResult ocrMath(String imageBase64) {
        long startTime = System.currentTimeMillis();
        int imageSize = imageBase64 != null ? imageBase64.length() : 0;

        log.debug("[MATH_OCR] Enviando a document-service. imageSize={}bytes", imageSize);

        try {
            var body = objectMapper.writeValueAsString(Map.of("image_base64", imageBase64));

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + OCR_MATH_PATH))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - startTime;

            log.debug("[MATH_OCR] Response: status={} elapsed={}ms", response.statusCode(), elapsed);

            if (response.statusCode() != 200) {
                log.warn("[MATH_OCR] Error: status={} body={}", response.statusCode(),
                        response.body() != null ? response.body().substring(0, Math.min(200, response.body().length())) : "");
                return new MathOcrResult("", 0.0, "", "failed");
            }

            JsonNode json = objectMapper.readTree(response.body());
            String latex = json.path("latex").asText("");
            double confidence = json.path("confidence").asDouble(0.0);
            String text = json.path("text").asText("");
            String method = json.path("method").asText("failed");

            log.info("[MATH_OCR] Resultado: method={} confidence={:.2f} latex='{}'",
                    method, confidence, latex.length() > 80 ? latex.substring(0, 80) + "..." : latex);

            return new MathOcrResult(latex, confidence, text, method);

        } catch (java.net.http.HttpTimeoutException e) {
            log.warn("[MATH_OCR] Timeout conectando con document-service: {}", e.getMessage());
            return new MathOcrResult("", 0.0, "", "timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[MATH_OCR] Interrupted");
            return new MathOcrResult("", 0.0, "", "interrupted");
        } catch (Exception e) {
            log.warn("[MATH_OCR] Error llamando a document-service: {}", e.getMessage());
            return new MathOcrResult("", 0.0, "", "error");
        }
    }
}
