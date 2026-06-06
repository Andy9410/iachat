package com.academy.chatservice.controller;

import com.academy.chatservice.model.InjectWhiteboardRequest;
import com.academy.chatservice.model.WhiteboardAnnotateRequest;
import com.academy.chatservice.model.WhiteboardDto;
import com.academy.chatservice.model.WhiteboardEntriesRequest;
import com.academy.chatservice.model.WhiteboardEntryDto;
import com.academy.chatservice.model.WhiteboardRequest;
import com.academy.chatservice.service.LLMClient;
import com.academy.chatservice.service.WhiteboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class WhiteboardController {

    private final WhiteboardService whiteboardService;
    private final LLMClient llmClient;

    public WhiteboardController(WhiteboardService whiteboardService, LLMClient llmClient) {
        this.whiteboardService = whiteboardService;
        this.llmClient = llmClient;
    }

    @GetMapping("/api/conversations/{conversationId}/whiteboards")
    public ResponseEntity<List<WhiteboardDto>> list(
            @PathVariable Long conversationId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(whiteboardService.list(conversationId, jwt.getSubject()));
    }

    @GetMapping("/api/conversations/{conversationId}/whiteboards/active")
    public ResponseEntity<WhiteboardDto> active(
            @PathVariable Long conversationId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(whiteboardService.active(conversationId, jwt.getSubject()));
    }

    @PostMapping("/api/conversations/{conversationId}/whiteboards")
    public ResponseEntity<WhiteboardDto> create(
            @PathVariable Long conversationId,
            @RequestBody WhiteboardRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(whiteboardService.createOrGet(conversationId, jwt.getSubject(), request));
    }

    /** Abre (o reutiliza) una pizarra activa de enseñanza. El backend la crea si no existe. */
    @PostMapping("/api/conversations/{conversationId}/whiteboards/open")
    public ResponseEntity<WhiteboardDto> open(
            @PathVariable Long conversationId,
            @RequestBody(required = false) WhiteboardRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        String title = request != null && request.title() != null ? request.title() : "Pizarra de enseñanza";
        String mode  = request != null && request.exerciseLabel() != null ? request.exerciseLabel() : "teaching";
        return ResponseEntity.ok(whiteboardService.openForTeaching(conversationId, title, mode, jwt.getSubject()));
    }

    @PutMapping("/api/whiteboards/{whiteboardId}")
    public ResponseEntity<WhiteboardDto> update(
            @PathVariable String whiteboardId,
            @RequestBody WhiteboardRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(whiteboardService.update(whiteboardId, jwt.getSubject(), request));
    }

    @DeleteMapping("/api/whiteboards/{whiteboardId}")
    public ResponseEntity<Void> delete(
            @PathVariable String whiteboardId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        whiteboardService.delete(whiteboardId, jwt.getSubject());
        return ResponseEntity.noContent().build();
    }

    /** Agrega entradas (pasos, fórmulas, etc.) a la pizarra de enseñanza. */
    @PostMapping("/api/conversations/{conversationId}/whiteboards/{whiteboardId}/entries")
    public ResponseEntity<List<WhiteboardEntryDto>> addEntries(
            @PathVariable Long conversationId,
            @PathVariable String whiteboardId,
            @RequestBody WhiteboardEntriesRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        var argEntries = request.entries().stream()
                .map(e -> new com.academy.chatservice.model.tools.UpdateWhiteboardArgs.StepArg(e.type(), e.content(), e.orderIndex()))
                .toList();
        return ResponseEntity.ok(whiteboardService.addEntries(whiteboardId, conversationId, argEntries, jwt.getSubject()));
    }

    /**
     * Inyecta bloques de contenido estructurado en la pizarra.
     * Los bloques se anexan respetando el orderIndex existente.
     */
    @PostMapping("/api/conversations/{conversationId}/whiteboards/{whiteboardId}/inject")
    public ResponseEntity<List<WhiteboardEntryDto>> inject(
            @PathVariable Long conversationId,
            @PathVariable String whiteboardId,
            @RequestBody InjectWhiteboardRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(
                whiteboardService.injectBlocks(whiteboardId, conversationId, request.blocks(), jwt.getSubject()));
    }

    /**
     * Teacher mode / Socratic mode: AI observes the whiteboard and returns
     * an annotation (AI_NOTE, AI_QUESTION, or AI_CORRECTION) injected as a block.
     */
    @PostMapping("/api/conversations/{conversationId}/whiteboards/{whiteboardId}/annotate")
    public ResponseEntity<WhiteboardEntryDto> annotate(
            @PathVariable Long conversationId,
            @PathVariable String whiteboardId,
            @RequestBody WhiteboardAnnotateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        String userEmail = jwt.getSubject();
        String prompt = whiteboardService.buildAnnotationContext(
                conversationId,
                request.question(),
                request.selectedContent(),
                request.selectedType(),
                request.socraticMode()
        );

        String aiResponse;
        try {
            aiResponse = llmClient.generate(prompt);
            if (aiResponse == null || aiResponse.isBlank()) aiResponse = "¿Querés continuar?";
        } catch (Exception e) {
            aiResponse = "¿En qué paso estás trabajando?";
        }

        // Determine annotation type from content hints
        String type = request.socraticMode() ? "AI_QUESTION" : "AI_NOTE";
        if (request.selectedContent() != null && !request.selectedContent().isBlank()) {
            type = "AI_QUESTION";
        }

        var block = new InjectWhiteboardRequest.BlockRequest(type, "assistant", aiResponse.trim(), 0, null);
        var saved = whiteboardService.injectBlocks(whiteboardId, conversationId, java.util.List.of(block), userEmail);
        return ResponseEntity.ok(saved.get(0));
    }

    /** Lista las entradas de una pizarra ordenadas por orderIndex. */
    @GetMapping("/api/conversations/{conversationId}/whiteboards/{whiteboardId}/entries")
    public ResponseEntity<List<WhiteboardEntryDto>> getEntries(
            @PathVariable Long conversationId,
            @PathVariable String whiteboardId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(whiteboardService.getEntries(whiteboardId, conversationId, jwt.getSubject()));
    }
}
