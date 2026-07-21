package com.avento.service.dto;

/** Total de tokens consumidos por conversa (para "chats mais pesados"). */
public interface ChatUsage {
    Long getChatId();

    String getTitle();

    long getTotal();
}
