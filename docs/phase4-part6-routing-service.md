# Phase 4 Part 6: Routing Service Implementation

## Overview

The Routing Service is responsible for ISO 8583 message construction, TCP communication with banks (via Netty), fraud detection, and smart routing to select the optimal acquirer. It's the bridge between PayFlow's REST world and the binary banking protocol world.

## Project Structure

```
routing-service/
├── src/main/java/com/payflow/routing/
│   ├── RoutingServiceApplication.java
│   ├── config/
│   │   ├── NettyClientConfig.java
│   │   └── ResilienceConfig.java
│   ├── controller/
│   │   └── InternalRoutingController.java
│   ├── fraud/
│   │   ├── FraudDetectionService.java
│   │   ├── VelocityCheckRule.java
│   │   ├── AmountLimitRule.java
│   │   └── FraudRule.java
│   ├── iso8583/
│   │   ├── ISO8583Message.java
│   │   ├── ISO8583MessageBuilder.java
│   │   ├── ISO8583Encoder.java
│   │   ├── ISO8583Decoder.java
│   │   └── FieldDefinition.java
│   ├── netty/
│   │   ├── BankTcpClient.java
│   │   ├── BankResponseHandler.java
│   │   └── ConnectionPool.java
│   ├── router/
│   │   └── SmartRouter.java
│   └── service/
│       └── RoutingService.java
```

## ISO 8583 Message Implementation

```java
public class ISO8583Message {
    private String mti;                    // Message Type Indicator
    private BitSet bitmap;                  // Primary bitmap (64 bits)
    private Map<Integer, String> fields;    // Data fields

    public byte[] encode() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        // 1. Write MTI (4 bytes ASCII)
        bos.write(mti.getBytes(StandardCharsets.US_ASCII));

        // 2. Calculate and write bitmap (8 bytes binary)
        byte[] bitmapBytes = bitmapToBytes(bitmap);
        bos.write(bitmapBytes);

        // 3. Write data fields in order
        for (int i = 2; i <= 128; i++) {
            if (bitmap.get(i - 1) && fields.containsKey(i)) {
                FieldDefinition def = FieldDefinition.get(i);
                byte[] fieldBytes = def.encode(fields.get(i));
                bos.write(fieldBytes);
            }
        }

        return bos.toByteArray();
    }

    public static ISO8583Message decode(byte[] data) {
        ISO8583Message msg = new ISO8583Message();
        ByteBuffer buffer = ByteBuffer.wrap(data);

        // 1. Read MTI
        byte[] mtiBytes = new byte[4];
        buffer.get(mtiBytes);
        msg.mti = new String(mtiBytes, StandardCharsets.US_ASCII);

        // 2. Read bitmap
        byte[] bitmapBytes = new byte[8];
        buffer.get(bitmapBytes);
        msg.bitmap = bytesToBitSet(bitmapBytes);

        // 3. Read fields
        msg.fields = new HashMap<>();
        for (int i = 2; i <= 64; i++) {
            if (msg.bitmap.get(i - 1)) {
                FieldDefinition def = FieldDefinition.get(i);
                msg.fields.put(i, def.decode(buffer));
            }
        }

        return msg;
    }
}
```

## ISO 8583 Message Builder

```java
public class ISO8583MessageBuilder {

    /**
     * Builds an authorization request (MTI 0100)
     */
    public static ISO8583Message buildAuthRequest(RoutingRequest request) {
        ISO8583Message msg = new ISO8583Message();
        msg.setMti("0100");  // Authorization Request

        // Field 2: PAN (Primary Account Number)
        msg.setField(2, request.getCard().getNumber());

        // Field 3: Processing Code (000000 = purchase)
        msg.setField(3, "000000");

        // Field 4: Amount (12 digits, right-justified, zero-filled)
        msg.setField(4, String.format("%012d", request.getAmount()));

        // Field 7: Transmission Date/Time (MMDDHHmmss)
        msg.setField(7, LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("MMddHHmmss")));

        // Field 11: STAN (System Trace Audit Number)
        msg.setField(11, generateSTAN());

        // Field 12: Local Transaction Time (HHmmss)
        msg.setField(12, LocalTime.now()
            .format(DateTimeFormatter.ofPattern("HHmmss")));

        // Field 13: Local Transaction Date (MMDD)
        msg.setField(13, LocalDate.now()
            .format(DateTimeFormatter.ofPattern("MMdd")));

        // Field 14: Expiration Date (YYMM)
        msg.setField(14, request.getCard().getExpiryYear()
            + request.getCard().getExpiryMonth());

        // Field 22: POS Entry Mode (051 = chip, 010 = manual/ecom)
        msg.setField(22, "010");  // E-commerce

        // Field 37: Retrieval Reference Number
        msg.setField(37, generateRRN());

        // Field 41: Card Acceptor Terminal ID
        msg.setField(41, "PAYFLOW1");

        // Field 42: Card Acceptor ID (Merchant ID)
        msg.setField(42, String.format("%-15s",
            request.getMerchantId().substring(0, 15)));

        // Field 49: Currency Code (356 = INR)
        msg.setField(49, "356");

        return msg;
    }

    /**
     * Builds a reversal request (MTI 0400)
     */
    public static ISO8583Message buildReversalRequest(String originalRrn,
                                                       Long amount) {
        ISO8583Message msg = new ISO8583Message();
        msg.setMti("0400");
        msg.setField(4, String.format("%012d", amount));
        msg.setField(7, LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("MMddHHmmss")));
        msg.setField(11, generateSTAN());
        msg.setField(37, originalRrn);
        return msg;
    }

    private static String generateSTAN() {
        return String.format("%06d",
            ThreadLocalRandom.current().nextInt(999999));
    }

    private static String generateRRN() {
        return LocalDate.now().format(
            DateTimeFormatter.ofPattern("yyMMdd"))
            + String.format("%06d",
                ThreadLocalRandom.current().nextInt(999999));
    }
}
```

## Netty TCP Client

```java
@Component
@Slf4j
public class BankTcpClient {

    private final Bootstrap bootstrap;
    private final EventLoopGroup workerGroup;
    private final Map<String, CompletableFuture<ISO8583Message>> pendingRequests;

    @Value("${bank.host:localhost}")
    private String bankHost;

    @Value("${bank.port:9090}")
    private int bankPort;

    @PostConstruct
    public void init() {
        workerGroup = new NioEventLoopGroup(4);
        pendingRequests = new ConcurrentHashMap<>();

        bootstrap = new Bootstrap()
            .group(workerGroup)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.TCP_NODELAY, true)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline()
                        // Frame decoder: 2-byte length header
                        .addLast(new LengthFieldBasedFrameDecoder(
                            65535, 0, 2, 0, 2))
                        // Frame encoder: prepend 2-byte length
                        .addLast(new LengthFieldPrepender(2))
                        // ISO 8583 codec
                        .addLast(new ISO8583Decoder())
                        .addLast(new ISO8583Encoder())
                        // Response handler
                        .addLast(new BankResponseHandler(pendingRequests));
                }
            });
    }

    public CompletableFuture<ISO8583Message> sendMessage(
            ISO8583Message request) {
        String stan = request.getField(11);  // Use STAN as correlation ID
        CompletableFuture<ISO8583Message> future = new CompletableFuture<>();
        pendingRequests.put(stan, future);

        // Set timeout
        future.orTimeout(30, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                pendingRequests.remove(stan);
                log.error("Bank request timed out for STAN: {}", stan);
                return null;
            });

        bootstrap.connect(bankHost, bankPort)
            .addListener((ChannelFutureListener) cf -> {
                if (cf.isSuccess()) {
                    cf.channel().writeAndFlush(request);
                    log.info("Sent ISO 8583 message, MTI: {}, STAN: {}",
                        request.getMti(), stan);
                } else {
                    future.completeExceptionally(cf.cause());
                    pendingRequests.remove(stan);
                }
            });

        return future;
    }

    @PreDestroy
    public void shutdown() {
        workerGroup.shutdownGracefully();
    }
}
```

## Fraud Detection Service

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class FraudDetectionService {

    private final List<FraudRule> rules;
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Fraud checks performed:
     * 1. Velocity check: Max 5 transactions per card per hour
     * 2. Amount limit: Single transaction < ₹10,000
     * 3. Daily limit: Total per card < ₹50,000/day
     * 4. Known bad cards: Blacklist check
     * 5. Geographic anomaly: (future - IP-based)
     */
    public FraudCheckResult checkFraud(RoutingRequest request) {
        for (FraudRule rule : rules) {
            FraudCheckResult result = rule.evaluate(request);
            if (result.isFlagged()) {
                log.warn("Fraud check failed: rule={}, reason={}, " +
                    "merchant={}", rule.getName(), result.reason(),
                    request.getMerchantId());
                return result;
            }
        }
        return FraudCheckResult.passed();
    }
}

// Velocity Check Rule
@Component
public class VelocityCheckRule implements FraudRule {

    private final RedisTemplate<String, String> redis;
    private static final int MAX_TXN_PER_HOUR = 5;

    @Override
    public String getName() {
        return "VELOCITY_CHECK";
    }

    @Override
    public FraudCheckResult evaluate(RoutingRequest request) {
        String cardHash = DigestUtils.sha256Hex(
            request.getCard().getNumber());
        String key = "velocity:" + cardHash;

        Long count = redis.opsForValue().increment(key);
        if (count == 1) {
            redis.expire(key, Duration.ofHours(1));
        }

        if (count > MAX_TXN_PER_HOUR) {
            return FraudCheckResult.flagged("VELOCITY_EXCEEDED",
                "Too many transactions from this card in the last hour");
        }
        return FraudCheckResult.passed();
    }
}

// Amount Limit Rule
@Component
public class AmountLimitRule implements FraudRule {

    private static final long MAX_SINGLE_TXN = 10_000_00L;  // ₹10,000
    private static final long MAX_DAILY = 50_000_00L;        // ₹50,000

    @Override
    public String getName() {
        return "AMOUNT_LIMIT";
    }

    @Override
    public FraudCheckResult evaluate(RoutingRequest request) {
        if (request.getAmount() > MAX_SINGLE_TXN) {
            return FraudCheckResult.flagged("AMOUNT_EXCEEDED",
                "Transaction amount exceeds single transaction limit");
        }
        // Daily limit check via Redis accumulator
        // ...
        return FraudCheckResult.passed();
    }
}
```

## Smart Routing Algorithm

```java
@Service
@RequiredArgsConstructor
public class SmartRouter {

    /**
     * Routing priorities:
     * 1. Card network preference (Visa → acquirer A, MC → acquirer B)
     * 2. Success rate (route to acquirer with higher recent success rate)
     * 3. Latency (prefer faster acquirer)
     * 4. Cost (prefer cheaper acquirer for merchant)
     * 5. Load balancing (distribute evenly when all else equal)
     */
    public AcquirerConfig selectAcquirer(RoutingRequest request) {
        String cardNetwork = detectCardNetwork(request.getCard().getNumber());

        List<AcquirerConfig> candidates = getAcquirersForNetwork(cardNetwork);

        // Score each acquirer
        return candidates.stream()
            .max(Comparator.comparingDouble(this::calculateScore))
            .orElseThrow(() -> new NoAcquirerAvailableException(cardNetwork));
    }

    private String detectCardNetwork(String pan) {
        if (pan.startsWith("4")) return "VISA";
        if (pan.startsWith("5") || pan.startsWith("2")) return "MASTERCARD";
        if (pan.startsWith("6")) return "RUPAY";
        return "UNKNOWN";
    }

    private double calculateScore(AcquirerConfig acquirer) {
        double successWeight = 0.4;
        double latencyWeight = 0.3;
        double costWeight = 0.2;
        double loadWeight = 0.1;

        return (acquirer.getSuccessRate() * successWeight)
            + ((1.0 / acquirer.getAvgLatencyMs()) * latencyWeight * 1000)
            + ((1.0 / acquirer.getCostPerTxn()) * costWeight)
            + ((1.0 / acquirer.getCurrentLoad()) * loadWeight);
    }
}
```

## Routing Service Orchestrator

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class RoutingService {

    private final FraudDetectionService fraudService;
    private final SmartRouter smartRouter;
    private final BankTcpClient bankClient;

    @CircuitBreaker(name = "bankConnection", fallbackMethod = "fallback")
    @TimeLimiter(name = "bankConnection")
    @Retry(name = "bankConnection")
    public CompletableFuture<RoutingResponse> processPayment(
            RoutingRequest request) {

        // Step 1: Fraud detection
        FraudCheckResult fraudResult = fraudService.checkFraud(request);
        if (fraudResult.isFlagged()) {
            return CompletableFuture.completedFuture(
                RoutingResponse.declined("FRAUD", fraudResult.reason()));
        }

        // Step 2: Select acquirer
        AcquirerConfig acquirer = smartRouter.selectAcquirer(request);

        // Step 3: Build ISO 8583 message
        ISO8583Message isoMessage = ISO8583MessageBuilder
            .buildAuthRequest(request);

        // Step 4: Send to bank via TCP
        return bankClient.sendMessage(isoMessage)
            .thenApply(response -> {
                String responseCode = response.getField(39);
                boolean approved = "00".equals(responseCode);

                return new RoutingResponse(
                    approved,
                    response.getField(37),   // RRN
                    response.getField(38),   // Auth Code
                    responseCode,
                    approved ? "APPROVED" :
                        getDeclineReason(responseCode)
                );
            });
    }

    private RoutingResponse fallback(RoutingRequest request, Throwable t) {
        log.error("Circuit breaker opened for bank connection", t);
        return RoutingResponse.declined("SERVICE_UNAVAILABLE",
            "Bank connection temporarily unavailable");
    }
}
```

## Response Code Mapping

| Code | Meaning | Action |
|------|---------|--------|
| 00 | Approved | ✅ Success |
| 01 | Refer to issuer | ❌ Decline |
| 05 | Do not honor | ❌ Decline |
| 12 | Invalid transaction | ❌ Decline |
| 14 | Invalid card number | ❌ Decline |
| 41 | Lost card | ❌ Decline + flag |
| 43 | Stolen card | ❌ Decline + flag |
| 51 | Insufficient funds | ❌ Decline |
| 54 | Expired card | ❌ Decline |
| 55 | Incorrect PIN | ❌ Decline |
| 61 | Exceeds limit | ❌ Decline |
| 91 | Issuer unavailable | ⚠️ Retry |
| 96 | System malfunction | ⚠️ Retry |
