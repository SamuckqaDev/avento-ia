package com.avento.service.auth;

import com.avento.model.UserAccount;
import com.avento.model.UserAccountRepository;
import com.avento.model.exception.InvalidRequestException;
import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AvatarService {

    public static final long MAX_AVATAR_BYTES = 512 * 1024;

    private static final Set<MediaType> SUPPORTED_MEDIA_TYPES = Set.of(
            MediaType.IMAGE_GIF, MediaType.IMAGE_JPEG, MediaType.IMAGE_PNG, MediaType.parseMediaType("image/webp"));

    private final UserAccountRepository userRepository;

    public AvatarService(UserAccountRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public void upload(UUID userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("Selecione uma imagem não vazia.");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new InvalidRequestException("A foto de perfil deve ter no máximo 512 KiB.");
        }

        MediaType mediaType = supportedMediaType(file.getContentType());
        UserAccount user = userRepository
                .findById(userId)
                .orElseThrow(() -> new NoSuchElementException("Usuário não encontrado."));
        try {
            user.setAvatarBytes(file.getBytes());
        } catch (IOException exception) {
            throw new InvalidRequestException("Não foi possível ler a foto de perfil.");
        }
        user.setAvatarMediaType(mediaType.toString());
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public Avatar avatar(UUID userId) {
        UserAccount user = userRepository
                .findById(userId)
                .orElseThrow(() -> new NoSuchElementException("Usuário não encontrado."));
        if (user.getAvatarBytes() == null || user.getAvatarMediaType() == null) {
            throw new NoSuchElementException("Foto de perfil não encontrada.");
        }
        return new Avatar(user.getAvatarBytes(), MediaType.parseMediaType(user.getAvatarMediaType()));
    }

    private MediaType supportedMediaType(String rawMediaType) {
        if (rawMediaType == null || rawMediaType.isBlank()) {
            throw new InvalidRequestException("Informe um tipo de imagem suportado.");
        }

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(rawMediaType);
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException("Informe um tipo de imagem suportado.");
        }
        boolean supported = SUPPORTED_MEDIA_TYPES.stream().anyMatch(candidate -> candidate.getType().equals(mediaType.getType())
                && candidate.getSubtype().equals(mediaType.getSubtype()));
        if (!supported) {
            throw new InvalidRequestException("Formato de imagem não suportado. Envie PNG, JPEG, WebP ou GIF.");
        }
        return new MediaType(mediaType.getType(), mediaType.getSubtype());
    }

    public record Avatar(byte[] bytes, MediaType mediaType) {}
}
