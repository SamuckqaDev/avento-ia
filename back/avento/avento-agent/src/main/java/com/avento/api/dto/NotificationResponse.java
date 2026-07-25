package com.avento.api.dto;

import com.avento.model.Notification;
import java.time.LocalDateTime;

public record NotificationResponse(
        Long id, String type, String title, String message, boolean read, LocalDateTime createdAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                notification.isRead(),
                notification.getCreatedAt());
    }
}
