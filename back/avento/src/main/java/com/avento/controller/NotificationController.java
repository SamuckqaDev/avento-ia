package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.*;
import com.avento.api.dto.BaseResponse;
import com.avento.model.Notification;
import com.avento.repository.NotificationRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRepository notificationRepository;

    public NotificationController(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @GetMapping
    public ResponseEntity<BaseResponse<List<NotificationResponse>>> getRecentNotifications() {
        return ApiResponses.ok(notificationRepository.findTop50ByOrderByCreatedAtDesc().stream()
                .map(NotificationResponse::from)
                .toList());
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<BaseResponse<NotificationResponse>> markRead(@PathVariable Long id) {
        Notification notification = notificationRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found"));
        notification.setRead(true);
        return ApiResponses.ok(NotificationResponse.from(notificationRepository.save(notification)));
    }

    @PostMapping("/read-all")
    public ResponseEntity<BaseResponse<List<NotificationResponse>>> markAllRead() {
        List<Notification> notifications = notificationRepository.findTop50ByOrderByCreatedAtDesc();
        notifications.forEach(notification -> notification.setRead(true));
        return ApiResponses.ok(notificationRepository.saveAll(notifications).stream()
                .map(NotificationResponse::from)
                .toList());
    }
}
