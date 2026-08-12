# Phase 4 Part 7: Bank Simulator Implementation

## Overview

The Bank Simulator is a Netty-based TCP server that mimics a real bank's card network. It receives ISO 8583 messages, applies configurable response rules, and returns approve/decline responses. Essential for local development and testing without connecting to real banks.

## Project Structure

```
bank-simulator/
├── src/main/java/com/payflow/banksimulator/
│   ├── BankSimulatorApplication.java
│   ├── config/
│   │   └── NettyServerConfig.java
│   ├── handler/
│   │   ├── ISO8583ServerHandler.java
│   │   └── ResponseGenerator.java
│   ├── codec/
│   │   ├── ISO8583ServerDecoder.java
│   │   └── ISO8583ServerEncoder.java
│   ├── rules/
│   │   ├── ResponseRule.java
│   │   ├── CardNumberRule.java
│   │   ├── AmountRule.java
│   │   └── RandomFailureRule.java
│   └── server/
│       └── BankTcpServer.java
└── src/main/resources/
    └── application.yml
```

## Netty TCP Server

```java
@Component
@Slf4j
public class BankTcpServer {

    @Value("${bank.simulator.port:9090}")
    private int port;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    @PostConstruct
    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(4);

        ServerBootstrap bootstrap = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline()
                        // Length-based framing (2 bytes header)
                        .addLast(new LengthFieldBasedFrameDecoder(
                            65535, 0, 2, 0, 2))
                        .addLast(new LengthFieldPrepender(2))
                        // ISO 8583 codec
                        .addLast(new ISO8583ServerDecoder())
                        .addLast(new ISO8583ServerEncoder())
                        // Business logic handler
                        .addLast(new ISO8583ServerHandler(
                            new ResponseGenerator()));
                }
            });

        try {
            ChannelFuture future = bootstrap.bind(port).sync();
            serverChannel = future.channel();
            log.info("Bank Simulator started on port {}", port);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to start bank simulator", e);
        }
    }

    @PreDestroy
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        log.info("Bank Simulator stopped");
    }
}
```

## Request Handler

```java
@Slf4j
@RequiredArgsConstructor
public class ISO8583ServerHandler extends SimpleChannelInboundHandler<ISO8583Message> {

    private final ResponseGenerator responseGenerator;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ISO8583Message request) {
        log.info("Received ISO 8583 message: MTI={}, STAN={}, Amount={}",
            request.getMti(), request.getField(11), request.getField(4));

        // Simulate processing delay (50-200ms like real banks)
        simulateLatency();

        // Generate response based on rules
        ISO8583Message response = responseGenerator.generateResponse(request);

        log.info("Sending response: MTI={}, ResponseCode={}, AuthCode={}",
            response.getMti(), response.getField(39), response.getField(38));

        ctx.writeAndFlush(response);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error in bank simulator handler", cause);
        ctx.close();
    }

    private void simulateLatency() {
        try {
            int delay = ThreadLocalRandom.current().nextInt(50, 200);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

## Response Generation Rules

```java
@Component
@Slf4j
public class ResponseGenerator {

    private final List<ResponseRule> rules;

    public ResponseGenerator() {
        this.rules = List.of(
            new CardNumberRule(),
            new AmountRule(),
            new RandomFailureRule()
        );
    }

    public ISO8583Message generateResponse(ISO8583Message request) {
        ISO8583Message response = new ISO8583Message();

        // Set response MTI (request + 10)
        String requestMti = request.getMti();
        String responseMti = requestMti.substring(0, 2)
            + String.valueOf(Integer.parseInt(requestMti.substring(2)) + 10);
        response.setMti(responseMti);

        // Copy relevant fields from request
        response.setField(2, request.getField(2));   // PAN
        response.setField(3, request.getField(3));   // Processing Code
        response.setField(4, request.getField(4));   // Amount
        response.setField(7, request.getField(7));   // DateTime
        response.setField(11, request.getField(11)); // STAN
        response.setField(37, request.getField(37)); // RRN
        response.setField(41, request.getField(41)); // Terminal ID
        response.setField(42, request.getField(42)); // Merchant ID

        // Apply rules to determine response code
        String responseCode = "00";  // Default: approved
        for (ResponseRule rule : rules) {
            String ruleResult = rule.evaluate(request);
            if (ruleResult != null) {
                responseCode = ruleResult;
                break;
            }
        }

        // Set response-specific fields
        response.setField(38, generateAuthCode());   // Auth code
        response.setField(39, responseCode);          // Response code

        return response;
    }

    private String generateAuthCode() {
        return String.format("%06d",
            ThreadLocalRandom.current().nextInt(999999));
    }
}
```

## Response Rules

```java
/**
 * Rule: Specific card numbers trigger specific responses
 * Useful for testing different scenarios
 */
public class CardNumberRule implements ResponseRule {

    // Test card numbers and their responses
    private static final Map<String, String> CARD_RESPONSES = Map.of(
        "4111111111111111", "00",   // Always approved (Visa test)
        "5500000000000004", "00",   // Always approved (MC test)
        "4000000000000002", "05",   // Always declined
        "4000000000000069", "51",   // Insufficient funds
        "4000000000000077", "54",   // Expired card
        "4000000000000085", "91",   // Issuer unavailable (retry)
        "4000000000000093", "96",   // System error (retry)
        "4000000000000101", "14",   // Invalid card number
        "4000000000000119", "41",   // Lost card
        "4000000000000127", "55"    // Incorrect PIN
    );

    @Override
    public String evaluate(ISO8583Message request) {
        String pan = request.getField(2);
        return CARD_RESPONSES.get(pan);  // null if not a test card
    }
}

/**
 * Rule: Amount-based responses
 */
public class AmountRule implements ResponseRule {

    @Override
    public String evaluate(ISO8583Message request) {
        String amountStr = request.getField(4);
        long amount = Long.parseLong(amountStr);

        // Amounts ending in 99 → decline (for testing)
        if (amount % 100 == 99) {
            return "05";  // Do not honor
        }

        // Amounts over ₹9,999 (999900 paise) → insufficient funds
        if (amount > 999900) {
            return "51";
        }

        return null;  // No rule triggered
    }
}

/**
 * Rule: Random failures (simulates real-world bank behavior)
 * 5% random failure rate
 */
public class RandomFailureRule implements ResponseRule {

    private static final double FAILURE_RATE = 0.05;  // 5%

    @Override
    public String evaluate(ISO8583Message request) {
        if (ThreadLocalRandom.current().nextDouble() < FAILURE_RATE) {
            // Random decline reasons
            String[] reasons = {"05", "91", "96"};
            return reasons[ThreadLocalRandom.current().nextInt(reasons.length)];
        }
        return null;  // Approved
    }
}
```

## Configuration

```yaml
spring:
  application:
    name: bank-simulator

server:
  port: 8086  # REST port for health/management

bank:
  simulator:
    port: 9090                   # TCP port for ISO 8583
    latency-min-ms: 50          # Min simulated latency
    latency-max-ms: 200         # Max simulated latency
    failure-rate: 0.05           # 5% random failures
    timeout-rate: 0.02           # 2% timeout simulation
```

## Test Card Reference

| Card Number | Network | Expected Response | Use Case |
|------------|---------|-------------------|----------|
| 4111 1111 1111 1111 | Visa | Approved (00) | Happy path testing |
| 5500 0000 0000 0004 | Mastercard | Approved (00) | Happy path testing |
| 4000 0000 0000 0002 | Visa | Declined (05) | Decline testing |
| 4000 0000 0000 0069 | Visa | Insufficient Funds (51) | Balance testing |
| 4000 0000 0000 0077 | Visa | Expired Card (54) | Validation testing |
| 4000 0000 0000 0085 | Visa | Issuer Unavailable (91) | Retry testing |
| 4000 0000 0000 0093 | Visa | System Error (96) | Error handling |
| 4000 0000 0000 0119 | Visa | Lost Card (41) | Fraud flagging |

## Message Flow Example

```
Routing Service                        Bank Simulator
      │                                       │
      │─── TCP Connect ─────────────────────▶│
      │◀── Connection ACK ──────────────────│
      │                                       │
      │─── [2 bytes length][ISO 8583 msg] ──▶│
      │    MTI: 0100                          │
      │    Field 2: 4111111111111111          │
      │    Field 4: 000000050000 (₹500)      │
      │    Field 11: 123456 (STAN)           │
      │    Field 37: 240115123456 (RRN)      │
      │                                       │
      │    ... (50-200ms simulated delay) ... │
      │                                       │
      │◀── [2 bytes length][ISO 8583 msg] ──│
      │    MTI: 0110                          │
      │    Field 38: A12345 (Auth Code)      │
      │    Field 39: 00 (Approved)           │
      │                                       │
      │─── TCP Close ───────────────────────▶│
```

## Health Check Endpoint

```java
@RestController
@RequestMapping("/actuator")
public class HealthController {

    private final BankTcpServer tcpServer;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "UP",
            "tcpServer", Map.of(
                "port", 9090,
                "active", tcpServer.isRunning(),
                "connections", tcpServer.getActiveConnections()
            )
        );
    }
}
```

## Monitoring Metrics

| Metric | Description |
|--------|-------------|
| `bank.simulator.requests.total` | Total messages received |
| `bank.simulator.responses.approved` | Approved count |
| `bank.simulator.responses.declined` | Declined count |
| `bank.simulator.latency.avg` | Average response time |
| `bank.simulator.connections.active` | Current TCP connections |
