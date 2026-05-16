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

    public record SearchResult(
            List<DocumentChunk> chunks,
            boolean ambiguous,
            List<String> ambiguousDocuments,
            String exerciseRef
    ) {
        public static SearchResult empty() {
            return new SearchResult(Collections.emptyList(), false, Collections.emptyList(), null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearchApiResponse(
            @JsonProperty("results") List<DocumentChunk> results,
            @JsonProperty("found") int found,
            @JsonProperty("ambiguous") boolean ambiguous,
            @JsonProperty("ambiguous_documents") List<String> ambiguousDocuments,
            @JsonProperty("exercise_ref") String exerciseRef
    ) {}

    public SearchResult search(String query, String userEmail, Long preferredDocumentId) {
        if (!props.enabled()) return SearchResult.empty();

        try {
            var bodyMap = new java.util.HashMap<String, Object>(Map.of(
                    "query", query,
                    "user_email", userEmail,
                    "top_k", props.topK(),
                    "similarity_threshold", props.similarityThreshold()));
            if (preferredDocumentId != null) bodyMap.put("preferred_document_id", preferredDocumentId);
            String body = mapper.writeValueAsString(bodyMap);

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
                return SearchResult.empty();
            }

            var parsed = mapper.readValue(response.body(), SearchApiResponse.class);
            if (parsed.ambiguous()) {
                log.info("[RAG] Ambiguous: {} docs for '{}'", parsed.ambiguousDocuments().size(), parsed.exerciseRef());
                return new SearchResult(Collections.emptyList(), true, parsed.ambiguousDocuments(), parsed.exerciseRef());
            }
            log.info("[RAG] Found {} chunks", parsed.results().size());
            return new SearchResult(parsed.results(), false, Collections.emptyList(), null);

        } catch (Exception e) {
            log.warn("[RAG] Exception: {}", e.getMessage(), e);
            return SearchResult.empty();
        }
    }
}
