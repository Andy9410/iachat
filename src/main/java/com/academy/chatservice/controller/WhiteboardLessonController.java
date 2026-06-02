package com.academy.chatservice.controller;

import com.academy.chatservice.model.WhiteboardLessonRequest;
import com.academy.chatservice.model.WhiteboardLessonResponse;
import com.academy.chatservice.service.WhiteboardLessonService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WhiteboardLessonController {

    private final WhiteboardLessonService whiteboardLessonService;

    public WhiteboardLessonController(WhiteboardLessonService whiteboardLessonService) {
        this.whiteboardLessonService = whiteboardLessonService;
    }

    @PostMapping("/tools/whiteboard/lesson")
    public ResponseEntity<WhiteboardLessonResponse> generateLesson(
            @RequestBody WhiteboardLessonRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(whiteboardLessonService.generate(request, jwt.getSubject()));
    }
}
