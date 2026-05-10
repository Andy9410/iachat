package com.academy.chatservice.repository;

import com.academy.chatservice.model.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findByUserEmailOrderByCreatedAtDesc(String userEmail);

    Optional<Conversation> findByIdAndUserEmail(Long id, String userEmail);
}
