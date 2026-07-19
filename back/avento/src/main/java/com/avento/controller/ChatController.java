package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.*;
import com.avento.api.dto.BaseResponse;
import com.avento.auth.security.AuthPrincipal;
import com.avento.controller.dto.ChatDtos.ChatCreateRequest;
import com.avento.controller.dto.ChatDtos.ChatResponse;
import com.avento.controller.dto.ChatDtos.ChatUpdateRequest;
import com.avento.controller.dto.ChatDtos.MessageCreateRequest;
import com.avento.controller.dto.ChatDtos.MessageResponse;
import com.avento.controller.mapper.ChatApiMapper;
import com.avento.model.Chat;
import com.avento.model.Message;
import com.avento.repository.ChatRepository;
import com.avento.repository.MessageRepository;
import com.avento.service.ChatArtifactService;
import com.avento.service.ChatArtifactService.ChatArtifactDeletionException;
import com.avento.service.GeneratedMediaAssetService;
import com.avento.service.GeneratedMediaAssetService.MediaAssetDeletionException;
import com.avento.service.ImageGenerationJobService;
import com.avento.service.VideoGenerationJobService;
import com.avento.service.context.ConversationContextCache;
import com.avento.service.dto.ArtifactDeletionResult;
import com.avento.service.dto.AssetDeletionResult;
import com.avento.service.dto.JobDeletionResult;
import com.avento.service.execution.AgentRunSubmissionService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/chats")
public class ChatController {

    private final ChatRepository chatRepository;

    private final MessageRepository messageRepository;

    private final ChatArtifactService chatArtifactService;

    private final VideoGenerationJobService videoGenerationJobService;

    private final ImageGenerationJobService imageGenerationJobService;

    private final GeneratedMediaAssetService generatedMediaAssetService;

    private final ConversationContextCache conversationContextCache;

    private final AgentRunSubmissionService runSubmissionService;

    public ChatController(
            ChatRepository chatRepository,
            MessageRepository messageRepository,
            ChatArtifactService chatArtifactService) {
        this(chatRepository, messageRepository, chatArtifactService, null, null, null, null, null);
    }

    public ChatController(
            ChatRepository chatRepository,
            MessageRepository messageRepository,
            ChatArtifactService chatArtifactService,
            VideoGenerationJobService videoGenerationJobService) {
        this(chatRepository, messageRepository, chatArtifactService, videoGenerationJobService, null, null, null, null);
    }

    @Autowired
    public ChatController(
            ChatRepository chatRepository,
            MessageRepository messageRepository,
            ChatArtifactService chatArtifactService,
            VideoGenerationJobService videoGenerationJobService,
            ImageGenerationJobService imageGenerationJobService,
            GeneratedMediaAssetService generatedMediaAssetService,
            ConversationContextCache conversationContextCache,
            AgentRunSubmissionService runSubmissionService) {
        this.chatRepository = chatRepository;
        this.messageRepository = messageRepository;
        this.chatArtifactService = chatArtifactService;
        this.videoGenerationJobService = videoGenerationJobService;
        this.imageGenerationJobService = imageGenerationJobService;
        this.generatedMediaAssetService = generatedMediaAssetService;
        this.conversationContextCache = conversationContextCache;
        this.runSubmissionService = runSubmissionService;
    }

    @GetMapping
    @Transactional
    public ResponseEntity<BaseResponse<List<ChatResponse>>> getAllChats(
            @AuthenticationPrincipal AuthPrincipal principal) {
        List<Chat> chats;
        if (principal != null) {
            chats = new ArrayList<>(chatRepository.findByUserIdOrderByUpdatedAtDesc(principal.userId()));
            List<Chat> legacyChats = chatRepository.findByUserIdIsNullOrderByUpdatedAtDesc();
            if (!legacyChats.isEmpty()) {
                // Chats created before authentication have no owner. In the local single-user setup,
                // claim them once so enabling auth does not make the existing history disappear.
                legacyChats.forEach(chat -> chat.setUserId(principal.userId()));
                chatRepository.saveAll(legacyChats);
                chats.addAll(legacyChats);
                chats.sort(Comparator.comparing(Chat::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed());
            }
        } else {
            chats = chatRepository.findAllByOrderByUpdatedAtDesc();
        }
        return ApiResponses.ok(chats.stream().map(ChatApiMapper::toResponse).toList());
    }

    @PostMapping
    public ResponseEntity<BaseResponse<ChatResponse>> createChat(
            @Valid @RequestBody ChatCreateRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        Chat chat = ChatApiMapper.toEntity(request);
        if (chat.getTitle() == null || chat.getTitle().isEmpty()) {
            chat.setTitle("Novo Chat");
        }
        if (principal != null) {
            chat.setUserId(principal.userId());
        }
        return ApiResponses.created(ChatApiMapper.toResponse(chatRepository.save(chat)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<BaseResponse<ChatResponse>> updateChat(
            @PathVariable Long id,
            @RequestBody ChatUpdateRequest payload,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Chat chat = findOwnedChat(id, principal);

        if (payload.title() != null && !payload.title().isBlank()) {
            chat.setTitle(payload.title());
        }
        if (payload.projectPath() != null) {
            chat.setProjectPath(payload.projectPath());
        }

        chat.touch();
        return ApiResponses.ok(ChatApiMapper.toResponse(chatRepository.save(chat)));
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<BaseResponse<List<MessageResponse>>> getMessages(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        findOwnedChat(id, principal);
        return ApiResponses.ok(messageRepository.findByChatIdOrderByTimestampAsc(id).stream()
                .map(ChatApiMapper::toResponse)
                .toList());
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<BaseResponse<MessageResponse>> addMessage(
            @PathVariable Long id,
            @Valid @RequestBody MessageCreateRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Chat chat = findOwnedChat(id, principal);
        Message message = ChatApiMapper.toEntity(id, request);
        Message saved = messageRepository.save(message);
        chat.touch();
        chatRepository.save(chat);
        if (conversationContextCache != null) {
            conversationContextCache.refresh(principal == null ? null : principal.userId(), id);
        }
        return ApiResponses.created(ChatApiMapper.toResponse(saved));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<BaseResponse<ChatDeletionResult>> deleteChat(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        Chat chat = findOwnedChat(id, principal);
        List<Message> messages = messageRepository.findByChatIdOrderByTimestampAsc(id);
        ArtifactDeletionResult artifacts;
        AssetDeletionResult mediaAssets = new AssetDeletionResult(0, 0);
        JobDeletionResult videoJobs = new JobDeletionResult(0, 0);
        JobDeletionResult imageJobs = new JobDeletionResult(0, 0);
        int deletedAgentJobs = 0;
        try {
            if (generatedMediaAssetService != null && principal != null) {
                mediaAssets = generatedMediaAssetService.deleteForChat(id, principal.userId());
            }
            artifacts = chatArtifactService.deleteOwnedArtifacts(messages);
            if (videoGenerationJobService != null && principal != null) {
                videoJobs = videoGenerationJobService.deleteForChat(id, principal.userId());
            }
            if (imageGenerationJobService != null && principal != null) {
                imageJobs = imageGenerationJobService.deleteForChat(id, principal.userId());
            }
            if (runSubmissionService != null && principal != null) {
                deletedAgentJobs = runSubmissionService.deleteForChat(id, principal.userId());
            }
        } catch (ChatArtifactDeletionException | MediaAssetDeletionException exception) {
            String filename = exception instanceof ChatArtifactDeletionException artifactException
                    ? artifactException.getFilename()
                    : ((MediaAssetDeletionException) exception).getFilename();
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Não foi possível apagar o arquivo " + filename + ". Feche-o e tente novamente.",
                    exception);
        }

        messageRepository.deleteAllInBatch(messages);
        chatRepository.delete(chat);
        chatRepository.flush();
        if (conversationContextCache != null) {
            conversationContextCache.evict(principal == null ? null : principal.userId(), id);
        }
        return ApiResponses.ok(new ChatDeletionResult(
                id,
                messages.size(),
                mediaAssets.deletedFiles()
                        + artifacts.deletedFiles()
                        + videoJobs.deletedFiles()
                        + imageJobs.deletedFiles(),
                mediaAssets.deletedAssets(),
                videoJobs.deletedJobs(),
                imageJobs.deletedJobs(),
                deletedAgentJobs));
    }

    private Chat findOwnedChat(Long id, AuthPrincipal principal) {
        Optional<Chat> chat = principal == null
                ? chatRepository.findById(id)
                : chatRepository.findByIdAndUserId(id, principal.userId());
        return chat.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));
    }
}
