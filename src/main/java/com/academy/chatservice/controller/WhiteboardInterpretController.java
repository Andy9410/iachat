package com.academy.chatservice.controller;

import com.academy.chatservice.model.WhiteboardInterpretRequest;
import com.academy.chatservice.model.WhiteboardInterpretationResponse;
import com.academy.chatservice.service.WhiteboardToolService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WhiteboardInterpretController {

    private final WhiteboardToolService whiteboardToolService;

    public WhiteboardInterpretController(WhiteboardToolService whiteboardToolService) {
        this.whiteboardToolService = whiteboardToolService;
    }

    @PostMapping("/tools/whiteboard/interpret")
    public ResponseEntity<WhiteboardInterpretationResponse> interpret(
            @RequestBody WhiteboardInterpretRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(whiteboardToolService.interpret(request, jwt.getSubject()));
    }
}
