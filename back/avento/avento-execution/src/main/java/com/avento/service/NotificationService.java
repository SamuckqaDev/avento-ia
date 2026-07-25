package com.avento.service;

import com.avento.model.Notification;
import com.avento.repository.NotificationRepository;
import org.springframework.stereotype.Service;

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
