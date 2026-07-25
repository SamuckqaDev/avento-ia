package com.avento.repository;

import com.avento.model.Notification;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findTop50ByOrderByCreatedAtDesc();

    long countByReadFalse();
}
