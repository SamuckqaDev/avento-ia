package com.avento.service;

import com.avento.model.Notification;
import com.avento.repository.NotificationRepository;
import org.springframework.stereotype.Service;

// Persisted, in-app counterpart to the native macOS notifications fired by
// SystemAutomationService.displayNotification — this is what survives closing and reopening
// Avento, and the shared entry point future proactive triggers (git status, file watch, etc.)
// should use to surface themselves in the app.
@Service
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    public Notification record(String type, String title, String message) {
        return repository.save(new Notification(type, title, message));
    }
}
