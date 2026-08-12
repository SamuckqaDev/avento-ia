package com.avento.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.config.AuthProperties;
import com.avento.dto.auth.AuthDtos.UpdateProfileRequest;
import com.avento.dto.auth.AuthDtos.UserResponse;
import com.avento.model.UserRole;
import com.avento.service.auth.AuthCookieService;
import com.avento.service.auth.AuthPrincipal;
import com.avento.service.auth.AuthService;
import com.avento.service.auth.AvatarService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

class AuthControllerAvatarTest {

    @Test
    void avatarUploadEndpointDelegatesTheAuthenticatedUsersFile() {
        AvatarService avatarService = mock(AvatarService.class);
        AuthPrincipal principal = new AuthPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), "access-jti", "dev@avento.local", "Avento Dev", UserRole.USER);
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[] {1});
        AuthController controller = new AuthController(
                new AuthProperties(), mock(AuthService.class), mock(AuthCookieService.class), avatarService);

        controller.uploadAvatar(principal, file);

        verify(avatarService).upload(principal.userId(), file);
    }

    @Test
    void avatarEndpointReturnsStoredBytesWithTheirContentType() {
        AvatarService avatarService = mock(AvatarService.class);
        AuthPrincipal principal = new AuthPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), "access-jti", "dev@avento.local", "Avento Dev", UserRole.USER);
        byte[] bytes = {4, 5, 6};
        when(avatarService.avatar(principal.userId())).thenReturn(new AvatarService.Avatar(bytes, MediaType.IMAGE_PNG));
        AuthController controller = new AuthController(
                new AuthProperties(), mock(AuthService.class), mock(AuthCookieService.class), avatarService);

        ResponseEntity<byte[]> response = controller.avatar(principal);

        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertArrayEquals(bytes, response.getBody());
    }

    @Test
    void profileEndpointDelegatesTheSignedInUsersDisplayName() {
        AuthService authService = mock(AuthService.class);
        AuthPrincipal principal = new AuthPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), "access-jti", "dev@avento.local", "Avento Dev", UserRole.USER);
        UpdateProfileRequest request = new UpdateProfileRequest("Novo nome");
        when(authService.updateProfile(principal, request))
                .thenReturn(new UserResponse(principal.userId(), principal.email(), "Novo nome", UserRole.USER, false));
        AuthController controller = new AuthController(
                new AuthProperties(), authService, mock(AuthCookieService.class), mock(AvatarService.class));

        controller.updateProfile(principal, request);

        verify(authService).updateProfile(principal, request);
    }
}
