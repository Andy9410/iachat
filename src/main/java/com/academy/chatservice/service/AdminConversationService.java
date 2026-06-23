package com.academy.chatservice.service;

import com.academy.chatservice.model.*;
import com.academy.chatservice.repository.AdminConversationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminConversationService {

    private final AdminConversationRepository adminConversationRepository;

    public AdminConversationService(AdminConversationRepository adminConversationRepository) {
        this.adminConversationRepository = adminConversationRepository;
    }

    public AdminConversationPageDto getPage(AdminConversationFilters filters, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return adminConversationRepository.findPage(filters, safePage, safeSize);
    }

    public AdminConversationMetricsDto getMetrics(AdminConversationFilters filters) {
        return adminConversationRepository.fetchMetrics(filters);
    }

    public AdminConversationDetailDto getDetail(Long conversationId) {
        return adminConversationRepository.findDetail(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversación no encontrada"));
    }
}
