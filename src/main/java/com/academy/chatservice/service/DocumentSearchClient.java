package com.academy.chatservice.service;

import com.academy.chatservice.config.DocumentServiceProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class DocumentSearchClient {

    private static final Logger log = LoggerFactory.getLogger(DocumentSearchClient.class);

    private final DocumentServiceProperties props;
    private final ServiceTokenProvider tokenProvider;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public DocumentSearchClient(DocumentServiceProperties props, ServiceTokenProvider tokenProvider, ObjectMapper mapper) {
        this.props = props;
        this.tokenProvider = tokenProvider;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.mapper = mapper;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocumentChunk(
            @JsonProperty("chunk_text") String chunkText,
            @JsonProperty("filename") String filename,
            @JsonProperty("page_number") Integer pageNumber,
            @JsonProperty("similarity") double similarity
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearchResponse(
            @JsonProperty("results") List<DocumentChunk> results,
            @JsonProperty("found") int found
    ) {}

    public List<DocumentChunk> search(String query, String userEmail) {
        if (!props.enabled()) return Collections.emptyList();

        try {
            String body = mapper.writeValueAsString(Map.of(
                    "query", query,
                    "user_email", userEmail,
                    "top_k", props.topK(),
                    "similarity_threshold", props.similarityThreshold()));

            log.info("[RAG] Searching docs for user={} body={}", userEmail, body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.baseUrl() + "/documents/search"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + tokenProvider.getServiceToken())
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("[RAG] document-service status={} body={}", response.statusCode(), response.body().substring(0, Math.min(200, response.body().length())));

            if (response.statusCode() != 200) {
                return Collections.emptyList();
            }

            List<DocumentChunk> results = mapper.readValue(response.body(), SearchResponse.class).results();
            log.info("[RAG] Found {} chunks", results.size());
            return results;

        } catch (Exception e) {
            log.warn("[RAG] Exception: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
