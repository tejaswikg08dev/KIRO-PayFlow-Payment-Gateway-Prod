# Phase 4 Part 3: Identity Service Implementation

## Overview

The Identity Service handles user registration, authentication, JWT token generation/validation, and session management. It's the foundation for both merchant dashboard access and internal service-to-service auth.

## Project Structure

```
identity-service/
├── src/main/java/com/payflow/identity/
│   ├── IdentityServiceApplication.java
│   ├── config/
│   │   └── SecurityConfig.java
│   ├── controller/
│   │   └── AuthController.java
│   ├── dto/
│   │   ├── request/
│   │   │   ├── RegisterRequest.java
│   │   │   ├── LoginRequest.java
│   │   │   └── RefreshTokenRequest.java
│   │   └── response/
│   │       ├── AuthResponse.java
│   │       └── UserResponse.java
│   ├── entity/
│   │   ├── User.java
│   │   └── RefreshToken.java
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java
│   │   ├── EmailAlreadyExistsException.java
│   │   └── InvalidCredentialsException.java
│   ├── repository/
│   │   ├── UserRepository.java
│   │   └── RefreshTokenRepository.java
│   ├── security/
│   │   ├── JwtTokenProvider.java
│   │   └── CustomUserDetailsService.java
│   └── service/
│       └── AuthService.java
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/
│       ├── V1__create_users_table.sql
│       └── V2__create_refresh_tokens_table.sql
└── src/test/java/com/payflow/identity/
    ├── controller/AuthControllerTest.java
    └── service/AuthServiceTest.java
```

## JWT Token Provider

```java
@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.access-token-expiry:900000}")  // 15 minutes
    private long accessTokenExpiry;

    @Value("${jwt.refresh-token-expiry:604800000}")  // 7 days
    private long refreshTokenExpiry;

    private SecretKey key;

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(
            Decoders.BASE64.decode(jwtSecret)
        );
    }

    public String generateAccessToken(User user) {
        return Jwts.builder()
            .subject(user.getId().toString())
            .claim("email", user.getEmail())
            .claim("role", user.getRole().name())
            .claim("name", user.getFullName())
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + accessTokenExpiry))
            .signWith(key)
            .compact();
    }

    public String generateRefreshToken() {
        return UUID.randomUUID().toString();
    }

    public Claims validateToken(String token) {
        return Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public UUID extractUserId(String token) {
        Claims claims = validateToken(token);
        return UUID.fromString(claims.getSubject());
    }
}
```

## Auth Service (BCrypt + Token Management)

```java
@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    public AuthResponse register(RegisterRequest request) {
        // Check if email already exists
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        // Create user with hashed password
        User user = User.builder()
            .email(request.email())
            .passwordHash(passwordEncoder.encode(request.password()))
            .fullName(request.fullName())
            .role(UserRole.MERCHANT)
            .status(UserStatus.ACTIVE)
            .createdAt(LocalDateTime.now())
            .build();

        user = userRepository.save(user);

        // Generate tokens
        return generateAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new InvalidCredentialsException());

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AccountSuspendedException(user.getEmail());
        }

        return generateAuthResponse(user);
    }

    public AuthResponse refreshToken(RefreshTokenRequest request) {
        RefreshToken storedToken = refreshTokenRepository
            .findByToken(request.refreshToken())
            .orElseThrow(() -> new InvalidRefreshTokenException());

        if (storedToken.isRevoked() ||
            storedToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidRefreshTokenException();
        }

        // Revoke old refresh token (rotation)
        storedToken.setRevoked(true);
        refreshTokenRepository.save(storedToken);

        // Generate new token pair
        return generateAuthResponse(storedToken.getUser());
    }

    public void logout(UUID userId) {
        refreshTokenRepository.revokeAllByUserId(userId);
    }

    private AuthResponse generateAuthResponse(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken();

        // Store refresh token
        RefreshToken token = RefreshToken.builder()
            .user(user)
            .token(refreshToken)
            .expiresAt(LocalDateTime.now().plusDays(7))
            .revoked(false)
            .build();
        refreshTokenRepository.save(token);

        return new AuthResponse(
            accessToken,
            refreshToken,
            900,  // 15 minutes in seconds
            UserResponse.from(user)
        );
    }
}
```

## Flyway Migrations

```sql
-- V1__create_users_table.sql
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'MERCHANT',
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_status ON users(status);

-- V2__create_refresh_tokens_table.sql
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_refresh_tokens_token ON refresh_tokens(token);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
```

## Security Configuration

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);  // Strength 12
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/register").permitAll()
                .requestMatchers("/api/v1/auth/login").permitAll()
                .requestMatchers("/api/v1/auth/refresh").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .build();
    }
}
```

## Auth Controller

```java
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "User registration and login")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new user")
    public ApiResponse<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ApiResponse.success(response, "Registration successful");
    }

    @PostMapping("/login")
    @Operation(summary = "Login with email and password")
    public ApiResponse<AuthResponse> login(
            @Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ApiResponse.success(response, "Login successful");
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token")
    public ApiResponse<AuthResponse> refresh(
            @Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refreshToken(request);
        return ApiResponse.success(response, "Token refreshed");
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Logout and revoke tokens")
    public void logout(@RequestHeader("X-User-Id") String userId) {
        authService.logout(UUID.fromString(userId));
    }

    @GetMapping("/me")
    @Operation(summary = "Get current user profile")
    public ApiResponse<UserResponse> getCurrentUser(
            @RequestHeader("X-User-Id") String userId) {
        UserResponse response = authService.getCurrentUser(
            UUID.fromString(userId));
        return ApiResponse.success(response);
    }
}
```

## Request/Response DTOs

```java
public record RegisterRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8, max = 100) String password,
    @NotBlank @Size(min = 2, max = 100) String fullName
) {}

public record LoginRequest(
    @NotBlank @Email String email,
    @NotBlank String password
) {}

public record RefreshTokenRequest(
    @NotBlank String refreshToken
) {}

public record AuthResponse(
    String accessToken,
    String refreshToken,
    long expiresIn,
    UserResponse user
) {}

public record UserResponse(
    String id,
    String email,
    String fullName,
    String role,
    String status,
    String createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
            user.getId().toString(),
            user.getEmail(),
            user.getFullName(),
            user.getRole().name(),
            user.getStatus().name(),
            user.getCreatedAt().toString()
        );
    }
}
```

## Application Configuration

```yaml
spring:
  application:
    name: identity-service
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/payflow_identity
    username: ${DB_USERNAME:payflow}
    password: ${DB_PASSWORD:payflow123}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: 8081

jwt:
  secret: ${JWT_SECRET:dGhpcyBpcyBhIHZlcnkgbG9uZyBzZWNyZXQga2V5IGZvciBkZXZlbG9wbWVudA==}
  access-token-expiry: 900000    # 15 minutes
  refresh-token-expiry: 604800000  # 7 days
```

## Unit Tests

```java
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private AuthService authService;

    @Test
    void register_withValidRequest_shouldReturnTokens() {
        RegisterRequest request = new RegisterRequest(
            "test@example.com", "password123", "Test User"
        );

        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        when(jwtTokenProvider.generateAccessToken(any())).thenReturn("access_token");
        when(jwtTokenProvider.generateRefreshToken()).thenReturn("refresh_token");

        AuthResponse response = authService.register(request);

        assertNotNull(response.accessToken());
        assertEquals("access_token", response.accessToken());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_withExistingEmail_shouldThrowException() {
        RegisterRequest request = new RegisterRequest(
            "existing@example.com", "password123", "Test User"
        );
        when(userRepository.existsByEmail("existing@example.com"))
            .thenReturn(true);

        assertThrows(EmailAlreadyExistsException.class,
            () -> authService.register(request));
    }

    @Test
    void login_withInvalidPassword_shouldThrowException() {
        LoginRequest request = new LoginRequest("test@example.com", "wrong");
        User user = User.builder()
            .passwordHash("hashed_password")
            .status(UserStatus.ACTIVE)
            .build();

        when(userRepository.findByEmail(anyString()))
            .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed_password"))
            .thenReturn(false);

        assertThrows(InvalidCredentialsException.class,
            () -> authService.login(request));
    }
}
```

## Security Best Practices Applied

| Practice | Implementation |
|----------|---------------|
| Password never stored in plaintext | BCrypt with strength 12 |
| Token rotation on refresh | Old refresh token revoked |
| Short-lived access tokens | 15 minute expiry |
| Refresh token revocation | On logout, all tokens revoked |
| No sensitive data in JWT payload | Only user ID, email, role |
| Timing-attack safe comparison | BCrypt handles this internally |
| Rate limit on login endpoint | Gateway handles (5 attempts/min) |
