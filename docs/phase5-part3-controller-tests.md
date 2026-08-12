# Phase 5 Part 3: Controller Tests

## Overview

Controller layer tests using `@WebMvcTest` and `MockMvc`. These tests verify request validation, HTTP status codes, response structure, and proper delegation to service layer — without starting a full Spring context.

## Order Controller Tests

```java
@WebMvcTest(OrderController.class)
@ActiveProfiles("test")
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
    }

    @Test
    void createOrder_withValidRequest_shouldReturn201() throws Exception {
        CreateOrderRequest request = TestDataFactory.createOrderRequest();
        OrderResponse response = new OrderResponse(
            UUID.randomUUID().toString(), 50000L, "INR", "CREATED",
            "Test order", LocalDateTime.now().toString());

        when(orderService.createOrder(any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/orders")
                .header("X-Merchant-Id", merchantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("CREATED"))
            .andExpect(jsonPath("$.data.amount").value(50000));
    }

    @Test
    void createOrder_withMissingAmount_shouldReturn400() throws Exception {
        String invalidBody = """
            {
                "currency": "INR",
                "description": "test"
            }
            """;

        mockMvc.perform(post("/api/v1/orders")
                .header("X-Merchant-Id", merchantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void createOrder_withNegativeAmount_shouldReturn400() throws Exception {
        String body = """
            {
                "amount": -100,
                "currency": "INR"
            }
            """;

        mockMvc.perform(post("/api/v1/orders")
                .header("X-Merchant-Id", merchantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createOrder_withoutMerchantHeader_shouldReturn400() throws Exception {
        CreateOrderRequest request = TestDataFactory.createOrderRequest();

        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getOrder_whenExists_shouldReturn200() throws Exception {
        UUID orderId = UUID.randomUUID();
        OrderResponse response = new OrderResponse(
            orderId.toString(), 50000L, "INR", "CREATED", null, null);

        when(orderService.getOrder(any(), eq(orderId))).thenReturn(response);

        mockMvc.perform(get("/api/v1/orders/{id}", orderId)
                .header("X-Merchant-Id", merchantId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(orderId.toString()))
            .andExpect(jsonPath("$.data.amount").value(50000));
    }

    @Test
    void getOrder_whenNotFound_shouldReturn404() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orderService.getOrder(any(), eq(orderId)))
            .thenThrow(new OrderNotFoundException(orderId));

        mockMvc.perform(get("/api/v1/orders/{id}", orderId)
                .header("X-Merchant-Id", merchantId.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void listOrders_shouldReturnPaginatedResults() throws Exception {
        PageResponse<OrderResponse> page = new PageResponse<>(
            List.of(
                new OrderResponse("id1", 50000L, "INR", "CAPTURED", null, null),
                new OrderResponse("id2", 30000L, "INR", "CREATED", null, null)
            ), 25, 3, 0, 10);

        when(orderService.listOrders(any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/orders")
                .header("X-Merchant-Id", merchantId.toString())
                .param("page", "0")
                .param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isArray())
            .andExpect(jsonPath("$.data.content.length()").value(2))
            .andExpect(jsonPath("$.data.totalElements").value(25))
            .andExpect(jsonPath("$.data.totalPages").value(3));
    }

    @Test
    void capturePayment_whenAuthorized_shouldReturn200() throws Exception {
        UUID orderId = UUID.randomUUID();
        PaymentResponse response = new PaymentResponse(
            orderId.toString(), "CAPTURED", "240115123456", null);

        when(orderService.capturePayment(eq(orderId))).thenReturn(response);

        mockMvc.perform(post("/api/v1/orders/{id}/capture", orderId)
                .header("X-Merchant-Id", merchantId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CAPTURED"));
    }
}
```

## Auth Controller Tests

```java
@WebMvcTest(AuthController.class)
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void register_withValidData_shouldReturn201() throws Exception {
        RegisterRequest request = new RegisterRequest(
            "new@example.com", "SecurePass1", "New User");
        AuthResponse response = new AuthResponse(
            "access_token", "refresh_token", 900,
            new UserResponse("id", "new@example.com", "New User",
                "MERCHANT", "ACTIVE", "2024-01-15"));

        when(authService.register(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.accessToken").value("access_token"))
            .andExpect(jsonPath("$.data.user.email").value("new@example.com"));
    }

    @Test
    void register_withInvalidEmail_shouldReturn400() throws Exception {
        String body = """
            {"email": "not-an-email", "password": "SecurePass1", "fullName": "Test"}
            """;

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void register_withShortPassword_shouldReturn400() throws Exception {
        String body = """
            {"email": "test@test.com", "password": "short", "fullName": "Test"}
            """;

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void login_withValidCredentials_shouldReturn200() throws Exception {
        LoginRequest request = new LoginRequest("test@test.com", "password123");
        AuthResponse response = new AuthResponse(
            "jwt_token", "refresh", 900, null);

        when(authService.login(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").exists());
    }

    @Test
    void login_withInvalidCredentials_shouldReturn401() throws Exception {
        when(authService.login(any()))
            .thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email": "test@test.com", "password": "wrong"}
                    """))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_withValidToken_shouldReturn200() throws Exception {
        AuthResponse response = new AuthResponse(
            "new_access", "new_refresh", 900, null);
        when(authService.refreshToken(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"refreshToken": "valid_refresh_token"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").value("new_access"));
    }
}
```

## Merchant Controller Tests

```java
@WebMvcTest(MerchantController.class)
@ActiveProfiles("test")
class MerchantControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private MerchantService merchantService;
    @MockBean private ApiKeyService apiKeyService;

    @Test
    void createMerchant_withValidData_shouldReturn201() throws Exception {
        String body = """
            {
                "businessName": "Test Shop",
                "businessType": "RETAIL",
                "mccCode": "5411",
                "accountNumber": "1234567890",
                "ifscCode": "SBIN0001234"
            }
            """;

        when(merchantService.createMerchant(any(), any()))
            .thenReturn(new MerchantResponse(/*...*/));

        mockMvc.perform(post("/api/v1/merchants")
                .header("X-User-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void generateApiKey_shouldReturnKeyOnce() throws Exception {
        UUID merchantId = UUID.randomUUID();
        ApiKeyResponse response = new ApiKeyResponse(
            "key-id", "pk_live_full_key_shown_once",
            "pk_live_abc1", "LIVE", "ACTIVE", "2024-01-15");

        when(apiKeyService.generateApiKey(eq(merchantId), any()))
            .thenReturn(response);

        mockMvc.perform(post("/api/v1/merchants/{id}/api-keys", merchantId)
                .header("X-User-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"type": "LIVE"}"""))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.fullKey").value("pk_live_full_key_shown_once"));
    }
}
```

## Key Testing Patterns

| Pattern | Usage |
|---------|-------|
| `@WebMvcTest` | Loads only web layer (controller + filters) |
| `@MockBean` | Replaces real beans with Mockito mocks |
| `MockMvc` | Simulates HTTP requests without real server |
| `jsonPath()` | Asserts on JSON response structure |
| `status().isXxx()` | Verifies HTTP status codes |
| `content()` | Sets request body |
| `header()` | Sets request headers |
