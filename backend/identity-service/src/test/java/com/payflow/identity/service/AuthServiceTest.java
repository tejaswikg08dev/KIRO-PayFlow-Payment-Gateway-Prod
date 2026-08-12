package com.payflow.identity.service;

import com.payflow.common.exception.DuplicateResourceException;
import com.payflow.common.exception.UnauthorizedException;
import com.payflow.identity.dto.*;
import com.payflow.identity.model.RefreshToken;
import com.payflow.identity.model.Role;
import com.payflow.identity.model.User;
import com.payflow.identity.repository.RefreshTokenRepository;
import com.payflow.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService Unit Tests")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    private User testUser;
    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id("user-123")
                .email("john@example.com")
                .passwordHash("encoded-password")
                .fullName("John Doe")
                .role(Role.USER)
                .active(true)
                .createdAt(Instant.now())
                .build();

        registerRequest = RegisterRequest.builder()
                .email("john@example.com")
                .password("password123")
                .fullName("John Doe")
                .build();

        loginRequest = LoginRequest.builder()
                .email("john@example.com")
                .password("password123")
                .build();
    }

    @Test
    @DisplayName("register - should create user and return auth response")
    void register_Success() {
        when(userRepository.existsByEmail(registerRequest.getEmail())).thenReturn(false);
        when(passwordEncoder.encode(registerRequest.getPassword())).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenReturn(testUser);
        when(jwtService.generateAccessToken(anyString(), anyString(), anyString()))
                .thenReturn("access-token-123");
        when(jwtService.getAccessTokenExpirationSeconds()).thenReturn(3600L);
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AuthResponse response = authService.register(registerRequest);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("access-token-123");
        assertThat(response.getRefreshToken()).isNotBlank();
        assertThat(response.getExpiresIn()).isEqualTo(3600L);
        assertThat(response.getUser().getEmail()).isEqualTo("john@example.com");

        verify(userRepository).save(any(User.class));
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("register - should throw DuplicateResourceException for existing email")
    void register_DuplicateEmail_Throws() {
        when(userRepository.existsByEmail(registerRequest.getEmail())).thenReturn(true);

        assertThatThrownBy(() -> authService.register(registerRequest))
                .isInstanceOf(DuplicateResourceException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("login - should return auth response for valid credentials")
    void login_Success() {
        when(userRepository.findByEmail(loginRequest.getEmail())).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches(loginRequest.getPassword(), testUser.getPasswordHash()))
                .thenReturn(true);
        when(jwtService.generateAccessToken(anyString(), anyString(), anyString()))
                .thenReturn("access-token-456");
        when(jwtService.getAccessTokenExpirationSeconds()).thenReturn(3600L);
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AuthResponse response = authService.login(loginRequest);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("access-token-456");
        assertThat(response.getUser().getEmail()).isEqualTo("john@example.com");
    }

    @Test
    @DisplayName("login - should throw UnauthorizedException for wrong password")
    void login_WrongPassword_Throws() {
        when(userRepository.findByEmail(loginRequest.getEmail())).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches(loginRequest.getPassword(), testUser.getPasswordHash()))
                .thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginRequest))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    @DisplayName("refreshToken - should generate new tokens with valid refresh token")
    void refreshToken_Success() {
        RefreshToken existingToken = RefreshToken.builder()
                .id("token-id-1")
                .token("valid-refresh-token")
                .userId("user-123")
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .revoked(false)
                .build();

        RefreshRequest refreshRequest = new RefreshRequest();
        refreshRequest.setRefreshToken("valid-refresh-token");

        when(refreshTokenRepository.findByTokenAndRevokedFalse("valid-refresh-token"))
                .thenReturn(Optional.of(existingToken));
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById("user-123")).thenReturn(Optional.of(testUser));
        when(jwtService.generateAccessToken(anyString(), anyString(), anyString()))
                .thenReturn("new-access-token");
        when(jwtService.getAccessTokenExpirationSeconds()).thenReturn(3600L);

        AuthResponse response = authService.refreshToken(refreshRequest);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("new-access-token");
        assertThat(response.getRefreshToken()).isNotBlank();

        verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("refreshToken - should throw UnauthorizedException for expired token")
    void refreshToken_ExpiredToken_Throws() {
        RefreshToken expiredToken = RefreshToken.builder()
                .id("token-id-2")
                .token("expired-refresh-token")
                .userId("user-123")
                .expiresAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .revoked(false)
                .build();

        RefreshRequest refreshRequest = new RefreshRequest();
        refreshRequest.setRefreshToken("expired-refresh-token");

        when(refreshTokenRepository.findByTokenAndRevokedFalse("expired-refresh-token"))
                .thenReturn(Optional.of(expiredToken));

        assertThatThrownBy(() -> authService.refreshToken(refreshRequest))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Refresh token expired");
    }
}
