package com.avento.controller.mapper;

import com.avento.controller.dto.ChatDtos.ChatCreateRequest;
import com.avento.controller.dto.ChatDtos.ChatResponse;
import com.avento.controller.dto.ChatDtos.MessageCreateRequest;
import com.avento.controller.dto.ChatDtos.MessageResponse;
import com.avento.model.Chat;
import com.avento.model.Message;

public final class ChatApiMapper {

    private ChatApiMapper() {}

    public static Chat toEntity(ChatCreateRequest request) {
        return new Chat(request.title(), request.projectPath());
    }

    public static Message toEntity(Long chatId, MessageCreateRequest request) {
        Message message = new Message(chatId, request.role(), request.content());
        message.setDocumentContext(request.documentContext());
        message.setDocumentNames(request.documentNames());
        return message;
    }

    public static ChatResponse toResponse(Chat chat) {
        return new ChatResponse(chat.getId(), chat.getTitle(), chat.getUpdatedAt(), chat.getProjectPath());
    }

    public static MessageResponse toResponse(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getChatId(),
                message.getRole(),
                message.getContent(),
                message.getDocumentContext(),
                message.getDocumentNames(),
                message.getTimestamp());
    }
}
