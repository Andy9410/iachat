package com.academy.chatservice.controller;

import com.academy.chatservice.model.LearningProfileDto;
import com.academy.chatservice.service.LearningProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LearningProfileController {

    private final LearningProfileService learningProfileService;

    public LearningProfileController(LearningProfileService learningProfileService) {
        this.learningProfileService = learningProfileService;
    }

    @GetMapping("/api/learning/profile")
    public ResponseEntity<LearningProfileDto> getProfile(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(learningProfileService.getProfile(jwt.getSubject()));
    }
}
