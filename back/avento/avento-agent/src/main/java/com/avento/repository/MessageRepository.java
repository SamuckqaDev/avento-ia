package com.avento.repository;

import com.avento.model.Message;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findByChatIdOrderByTimestampAsc(Long chatId);

    @Query(
            "SELECT DISTINCT m FROM Message m JOIN Chat c ON m.chatId = c.id WHERE (:userId IS NULL OR c.userId = :userId OR c.userId IS NULL) AND LOWER(m.content) LIKE LOWER(CONCAT('%', :query, '%')) ORDER BY m.timestamp DESC")
    List<Message> searchMessagesForUser(@Param("userId") UUID userId, @Param("query") String query);
}
