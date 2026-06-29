package com.academy.chatservice.service.openrouter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class OpenRouterRequestExecutor {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterRequestExecutor.class);

    private final ObjectMapper objectMapper;
    private final OpenRouterClient client;
    private final OpenRouterModelRouter modelRouter;
    private final OpenRouterRetryPolicy retryPolicy;
    private final OpenRouterMetrics metrics;

    public OpenRouterRequestExecutor(
            ObjectMapper objectMapper,
            OpenRouterClient client,
            OpenRouterModelRouter modelRouter,
            OpenRouterRetryPolicy retryPolicy,
            OpenRouterMetrics metrics
    ) {
        this.objectMapper = objectMapper;
        this.client = client;
        this.modelRouter = modelRouter;
        this.retryPolicy = retryPolicy;
        this.metrics = metrics;
    }

    public HttpResponse<String> sendString(Map<String, Object> requestBody, String apiKey) throws IOException, InterruptedException {
        Set<String> attemptedModels = new HashSet<>();
        String previousModel = null;

        for (int attempt = 0; attempt < modelRouter.modelCount(); attempt++) {
            OpenRouterModelRouter.ModelSelection selection = modelRouter.selectModel(attemptedModels);
            String model = selection.model();
            if (previousModel != null) {
                metrics.recordSwitch(previousModel, model);
                log.warn("Switching OpenRouter model from {} to {}", previousModel, model);
            }

            HttpResponse<String> response = sendStringWithModel(requestBody, apiKey, model);
            if (!retryPolicy.isModelCapacityError(response.statusCode(), response.body())) {
                return response;
            }

            metrics.recordRateLimit(model);
            log.warn("OpenRouter model rate limited: model={} status={} body={}", model, response.statusCode(), truncate(response.body()));
            modelRouter.markUnavailable(model);
            attemptedModels.add(model);
            previousModel = model;
        }

        throw new OpenRouterUnavailableException("Todos los modelos gratuitos de OpenRouter estan temporalmente saturados.");
    }

    public HttpResponse<Stream<String>> sendLines(Map<String, Object> requestBody, String apiKey) throws IOException, InterruptedException {
        Set<String> attemptedModels = new HashSet<>();
        String previousModel = null;

        for (int attempt = 0; attempt < modelRouter.modelCount(); attempt++) {
            OpenRouterModelRouter.ModelSelection selection = modelRouter.selectModel(attemptedModels);
            String model = selection.model();
            if (previousModel != null) {
                metrics.recordSwitch(previousModel, model);
                log.warn("Switching OpenRouter stream model from {} to {}", previousModel, model);
            }

            long start = System.currentTimeMillis();
            HttpResponse<Stream<String>> response = client.sendLines(serializeWithModel(requestBody, model), apiKey);
            long elapsed = System.currentTimeMillis() - start;

            if (response.statusCode() == 200) {
                modelRouter.markSuccessful(model);
                metrics.recordResponse(model, elapsed, true);
                if (attempt > 0) {
                    log.info("OpenRouter stream retry successful. finalModel={}", model);
                }
                return response;
            }

            if (response.statusCode() != 429) {
                return response;
            }

            String errorBody = collectBody(response.body());
            if (!retryPolicy.isModelCapacityError(response.statusCode(), errorBody)) {
                return response;
            }

            metrics.recordRateLimit(model);
            log.warn("OpenRouter stream model rate limited: model={} status={} body={}", model, response.statusCode(), truncate(errorBody));
            modelRouter.markUnavailable(model);
            attemptedModels.add(model);
            previousModel = model;
        }

        throw new OpenRouterUnavailableException("Todos los modelos gratuitos de OpenRouter estan temporalmente saturados.");
    }

    private HttpResponse<String> sendStringWithModel(Map<String, Object> requestBody, String apiKey, String model) throws IOException, InterruptedException {
        log.info("Using OpenRouter model: {}", model);
        long start = System.currentTimeMillis();
        HttpResponse<String> response = client.sendString(serializeWithModel(requestBody, model), apiKey);
        long elapsed = System.currentTimeMillis() - start;
        if (response.statusCode() == 200) {
            modelRouter.markSuccessful(model);
            metrics.recordResponse(model, elapsed, true);
            log.info("OpenRouter response successful. finalModel={} elapsedMs={}", model, elapsed);
        } else {
            metrics.recordResponse(model, elapsed, false);
        }
        return response;
    }

    private String serializeWithModel(Map<String, Object> requestBody, String model) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>(requestBody);
        body.put("model", model);
        return objectMapper.writeValueAsString(body);
    }

    private String collectBody(Stream<String> body) {
        if (body == null) {
            return "";
        }
        return body.collect(Collectors.joining("\n"));
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.substring(0, Math.min(300, value.length()));
    }
}
