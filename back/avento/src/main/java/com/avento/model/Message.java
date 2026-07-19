package com.avento.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "messages")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id")
    private Long chatId;

    @Column(name = "role_name")
    private String role; // "system", "user", "assistant"

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "document_context", columnDefinition = "TEXT")
    private String documentContext;

    @Column(name = "document_names", columnDefinition = "TEXT")
    private String documentNames;

    private LocalDateTime timestamp;

    public Message() {}

    public Message(Long chatId, String role, String content) {
        this.chatId = chatId;
        this.role = role;
        this.content = content;
        this.timestamp = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getChatId() {
        return chatId;
    }

    public void setChatId(Long chatId) {
        this.chatId = chatId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getDocumentContext() {
        return documentContext;
    }

    public void setDocumentContext(String documentContext) {
        this.documentContext = documentContext;
    }

    public String getDocumentNames() {
        return documentNames;
    }

    public void setDocumentNames(String documentNames) {
        this.documentNames = documentNames;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
