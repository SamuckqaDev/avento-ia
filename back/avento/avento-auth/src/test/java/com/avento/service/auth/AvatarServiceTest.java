package com.avento.service.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.model.UserAccount;
import com.avento.model.UserAccountRepository;
import com.avento.model.exception.InvalidRequestException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

class AvatarServiceTest {

    private final UserAccountRepository userRepository = mock(UserAccountRepository.class);
    private final AvatarService avatarService = new AvatarService(userRepository);
    private final UUID userId = UUID.randomUUID();

    @Test
    void acceptsSupportedAvatarAndStoresItsBytesAndMediaType() {
        UserAccount user = new UserAccount();
        byte[] bytes = {1, 2, 3};
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        avatarService.upload(userId, new MockMultipartFile("file", "avatar.png", "image/png", bytes));

        assertArrayEquals(bytes, user.getAvatarBytes());
        assertEquals("image/png", user.getAvatarMediaType());
        verify(userRepository).save(user);
    }

    @Test
    void rejectsAnUnsupportedAvatarMediaType() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.txt", "text/plain", new byte[] {1});

        assertThrows(InvalidRequestException.class, () -> avatarService.upload(userId, file));
    }

    @Test
    void rejectsAvatarLargerThanTheConfiguredLimit() {
        byte[] oversized = new byte[(int) AvatarService.MAX_AVATAR_BYTES + 1];
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", oversized);

        assertThrows(InvalidRequestException.class, () -> avatarService.upload(userId, file));
    }

    @Test
    void returnsAvatarBytesWithTheirStoredContentType() {
        UserAccount user = new UserAccount();
        user.setAvatarBytes(new byte[] {7, 8, 9});
        user.setAvatarMediaType("image/webp");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        AvatarService.Avatar avatar = avatarService.avatar(userId);

        assertArrayEquals(new byte[] {7, 8, 9}, avatar.bytes());
        assertEquals(MediaType.parseMediaType("image/webp"), avatar.mediaType());
    }
}
