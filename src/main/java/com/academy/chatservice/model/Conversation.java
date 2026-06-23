package com.academy.chatservice.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "conversations")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_email", length = 150)
    private String userEmail;

    @Column(name = "user_name", length = 150)
    private String userName;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private boolean hidden = false;

    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    private List<Message> messages = new ArrayList<>();

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Column(name = "active_document_id")
    private Long activeDocumentId;

    @Column(name = "archived_context", columnDefinition = "TEXT")
    private String archivedContext;

    @Column(name = "archived_message_count", nullable = false)
    private int archivedMessageCount = 0;

    public Long getId() { return id; }
    public String getUserEmail() { return userEmail; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<Message> getMessages() { return messages; }
    public boolean isHidden() { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }
    public Long getActiveDocumentId() { return activeDocumentId; }
    public void setActiveDocumentId(Long id) { this.activeDocumentId = id; }
    public String getArchivedContext() { return archivedContext; }
    public void setArchivedContext(String archivedContext) { this.archivedContext = archivedContext; }
    public int getArchivedMessageCount() { return archivedMessageCount; }
    public void setArchivedMessageCount(int count) { this.archivedMessageCount = count; }

}
