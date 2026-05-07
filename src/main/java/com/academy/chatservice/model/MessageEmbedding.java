package com.academy.chatservice.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "message_embeddings")
public class MessageEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @Convert(converter = VectorConverter.class)
    @Column(columnDefinition = "vector(768)", nullable = false)
    private List<Float> embedding;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }

    public MessageEmbedding() {}

    public MessageEmbedding(Message message, List<Float> embedding) {
        this.message = message;
        this.embedding = embedding;
    }

    public Long getId() { return id; }
    public Message getMessage() { return message; }
    public List<Float> getEmbedding() { return embedding; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
