package com.academy.chatservice.controller;

import com.academy.chatservice.model.LearningProfileDto;
import com.academy.chatservice.service.LearningProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

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

    @GetMapping("/api/admin/learning/profile")
    public ResponseEntity<LearningProfileDto> getAdminProfile(
            @RequestParam String email,
            @AuthenticationPrincipal Jwt jwt
    ) {
        requireAdmin(jwt);
        return ResponseEntity.ok(learningProfileService.getProfile(email));
    }

    private void requireAdmin(Jwt jwt) {
        if (jwt == null || !"ROLE_ADMIN".equals(jwt.getClaimAsString("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acceso solo para administradores");
        }
    }
}
