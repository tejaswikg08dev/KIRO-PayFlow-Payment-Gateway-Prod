# Phase 4 · Part 10 — Bank Simulator

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Routing & Bank Integration |
| **Part** | 10 — Bank Simulator (Fake Bank for Testing) |
| **Previous** | [Part 9C — Fraud Detection & Smart Routing](./phase4-part09c-routing-fraud-smartrouting.md) |
| **Next** | [Part 11 — Settlement Service](./phase4-part11-settlement-service.md) |
| **Time** | ~2.5 hours |
| **Difficulty** | ★★★☆☆ (Intermediate) |
| **Prerequisites** | Netty basics (Part 9B), ISO 8583 format (Part 9A) |
| **What You'll Build** | A Netty TCP server that simulates a bank's authorization system |
| **Git Commit** | `feat(bank-sim): add bank simulator with configurable approve/decline rules` |

---

## Table of Contents

1. [What is the Bank Simulator?](#1-what-is-the-bank-simulator)
2. [Architecture: TCP Server vs TCP Client](#2-architecture-tcp-server-vs-tcp-client)
3. [BankSimulatorServer](#3-banksimulatorserver)
4. [Iso8583RequestHandler](#4-iso8583requesthandler)
5. [ResponseGenerator](#5-responsegenerator)
6. [CardBinRules](#6-cardbinrules)
7. [AmountRules](#7-amountrules)
8. [SimulatorConfig](#8-simulatorconfig)
9. [Testing the Simulator](#9-testing-the-simulator)
10. [What You Learned](#10-what-you-learned)
11. [Common Errors & Fixes](#11-common-errors--fixes)
12. [Git Commit](#12-git-commit)

---

## What You'll Learn

- How to build a Netty TCP **server** (vs the TCP **client** in Part 9B)
- How banks process authorization requests and send responses
- How to simulate real-world behavior (latency, partial failures, decline reasons)
- How card BIN rules determine approve/decline decisions
- How to make failure scenarios configurable for testing
- How to test the routing service end-to-end without a real bank

---

## 1. What is the Bank Simulator?

The bank simulator is a **fake bank** that speaks ISO 8583 over TCP. It replaces real banks (Visa, HDFC, SBI) during development and testing.

```
WHY we need it:
┌──────────────────────────────────────────────────────────────┐
│                                                               │
│  Without Simulator:                                           │
│  PayFlow → Visa (PRODUCTION) → Real money moves! 💸          │
│  Problems:                                                    │
│    • Can't test freely (every test costs real money)          │
│    • Can't simulate failures (banks don't fail on demand)     │
│    • Can't run tests offline (need bank VPN)                  │
│    • Certification required (takes months)                    │
│                                                               │
│  With Simulator:                                              │
│  PayFlow → Bank Simulator (LOCAL) → Fake response 🎭         │
│  Benefits:                                                    │
│    • Free unlimited testing                                   │
│    • Simulate any failure (timeout, decline, error)           │
│    • Works offline (runs on your machine)                     │
│    • Instant feedback (no certification needed)               │
│                                                               │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. Architecture: TCP Server vs TCP Client

```
┌─────────────────────────────────────────────────────────────────────┐
│                  PRODUCTION ARCHITECTURE                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌─────────────┐        TCP         ┌──────────────┐               │
│  │  Routing    │ ──── Client ──────→ │  HDFC Bank   │               │
│  │  Service    │ ←─── Response ────  │  (Real)      │               │
│  │  (Netty     │                     │  Port 9090   │               │
│  │   CLIENT)   │        TCP         ┌──────────────┐               │
│  │             │ ──── Client ──────→ │  SBI Bank    │               │
│  │             │ ←─── Response ────  │  (Real)      │               │
│  └─────────────┘                     └──────────────┘               │
│                                                                      │
├─────────────────────────────────────────────────────────────────────┤
│                  DEVELOPMENT ARCHITECTURE                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌─────────────┐        TCP         ┌──────────────────┐           │
│  │  Routing    │ ──── Client ──────→ │  Bank Simulator  │           │
│  │  Service    │ ←─── Response ────  │  (Netty SERVER)  │           │
│  │  (Netty     │                     │  Port 9090       │           │
│  │   CLIENT)   │                     │                  │           │
│  │             │                     │  Simulates:      │           │
│  │             │                     │  • HDFC          │           │
│  │             │                     │  • SBI           │           │
│  │             │                     │  • ICICI         │           │
│  └─────────────┘                     └──────────────────┘           │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Key Difference: Server vs Client

| Aspect | Routing Service (Part 9B) | Bank Simulator (This Part) |
|--------|--------------------------|---------------------------|
| Role | TCP **Client** | TCP **Server** |
| Netty class | `Bootstrap` | `ServerBootstrap` |
| Who connects? | WE connect to bank | Bank simulator ACCEPTS connections |
| EventLoopGroup | 1 group (worker) | 2 groups (boss + worker) |
| Port | Random outgoing | Fixed: 9090 |
| Channel type | `NioSocketChannel` | `NioServerSocketChannel` |

---

## 3. BankSimulatorServer

```java
package com.payflow.banksimulator;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class BankSimulatorServer {

    private final SimulatorConfig simulatorConfig;
    private final ResponseGenerator responseGenerator;

    @Value("${simulator.port:9090}")
    private int port;

    // WHY two groups:
    // bossGroup: Accepts incoming TCP connections (like a receptionist)
    // workerGroup: Handles actual data for accepted connections (like clerks)
    // In production banks, this separation lets them accept thousands of connections
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    @PostConstruct
    public void start() {
        // WHY 1 boss thread: We only bind to 1 port, so 1 acceptor thread is enough
        bossGroup = new NioEventLoopGroup(1);
        // WHY 4 worker threads: Simulate bank processing with some parallelism
        workerGroup = new NioEventLoopGroup(4);

        try {
            // WHY ServerBootstrap (not Bootstrap): This is a SERVER, not a client
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    // WHY NioServerSocketChannel: Server socket that accepts connections
                    .channel(NioServerSocketChannel.class)
                    // WHY SO_BACKLOG: Queue up to 128 pending connections
                    // (if all workers busy, new connections wait in this queue)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    // WHY SO_KEEPALIVE: Detect dead client connections
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    // WHY TCP_NODELAY: Send responses immediately (no Nagle buffering)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    // WHY childHandler: Applied to each accepted connection (not the server socket)
                    .childHandler(new BankSimulatorInitializer(simulatorConfig, responseGenerator));

            // WHY sync(): Block until server is actually bound to the port
            ChannelFuture future = bootstrap.bind(port).sync();
            serverChannel = future.channel();

            log.info("🏦 Bank Simulator started on port {}", port);
            log.info("   Success rate: {}%", simulatorConfig.getSuccessRatePercent());
            log.info("   Latency range: {}-{}ms",
                    simulatorConfig.getMinLatencyMs(), simulatorConfig.getMaxLatencyMs());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to start bank simulator", e);
        }
    }

    @PreDestroy
    public void stop() {
        log.info("🛑 Shutting down bank simulator...");
        if (serverChannel != null) {
            serverChannel.close();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }
}
```

### BankSimulatorInitializer

```java
package com.payflow.banksimulator;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

public class BankSimulatorInitializer extends ChannelInitializer<SocketChannel> {

    private final SimulatorConfig config;
    private final ResponseGenerator responseGenerator;

    public BankSimulatorInitializer(SimulatorConfig config, ResponseGenerator responseGenerator) {
        this.config = config;
        this.responseGenerator = responseGenerator;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // INBOUND: Receiving request from PayFlow routing service
        // WHY same framing as client: Both sides must agree on length-prefix format
        pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(
                65535, 0, 2, 0, 2));

        // OUTBOUND: Sending response back to PayFlow
        pipeline.addLast("frameEncoder", new LengthFieldPrepender(2));

        // BUSINESS LOGIC: Process the request and generate response
        pipeline.addLast("requestHandler",
                new Iso8583RequestHandler(config, responseGenerator));
    }
}
```

---

## 4. Iso8583RequestHandler

```java
package com.payflow.banksimulator;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles incoming ISO 8583 authorization requests.
 * Simulates bank processing: parse request → decide → respond.
 */
@Slf4j
public class Iso8583RequestHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private final SimulatorConfig config;
    private final ResponseGenerator responseGenerator;

    public Iso8583RequestHandler(SimulatorConfig config, ResponseGenerator responseGenerator) {
        this.config = config;
        this.responseGenerator = responseGenerator;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf requestBuf) throws Exception {
        // Step 1: Read the raw bytes
        byte[] requestBytes = new byte[requestBuf.readableBytes()];
        requestBuf.readBytes(requestBytes);

        log.info("📨 Received authorization request: {} bytes from {}",
                requestBytes.length, ctx.channel().remoteAddress());

        // Step 2: Simulate bank processing latency
        // WHY: Real banks take 200-2000ms to process. We simulate this for realistic testing.
        simulateLatency();

        // Step 3: Generate response (approve or decline)
        byte[] responseBytes = responseGenerator.generateResponse(requestBytes);

        // Step 4: Send response back through the pipeline
        // WHY Unpooled.wrappedBuffer: Zero-copy — wraps our byte[] without copying
        ByteBuf responseBuf = Unpooled.wrappedBuffer(responseBytes);
        ctx.writeAndFlush(responseBuf);

        log.info("📤 Sent authorization response: {} bytes", responseBytes.length);
    }

    private void simulateLatency() throws InterruptedException {
        // WHY random latency: Real banks don't have constant response times
        // Under load, some requests take longer (queue effects)
        int latency = ThreadLocalRandom.current().nextInt(
                config.getMinLatencyMs(),
                config.getMaxLatencyMs() + 1
        );
        Thread.sleep(latency);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("🔗 New connection from: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("🔌 Connection closed: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("❌ Error processing request", cause);
        ctx.close();
    }
}
```

---

## 5. ResponseGenerator

The core decision logic — determines if a transaction is approved or declined.

```java
package com.payflow.banksimulator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates ISO 8583 response messages based on configurable rules.
 *
 * WHY separate class: Single Responsibility — the handler deals with Netty I/O,
 * this class deals with business logic. Makes testing easier.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ResponseGenerator {

    private final CardBinRules cardBinRules;
    private final AmountRules amountRules;
    private final SimulatorConfig config;

    public byte[] generateResponse(byte[] requestBytes) {
        // Step 1: Parse minimal fields from request
        String mti = extractMti(requestBytes);
        String pan = extractPan(requestBytes);
        String amount = extractAmount(requestBytes);
        String correlationId = extractCorrelationId(requestBytes);

        // Step 2: Determine response code
        String responseCode = determineResponseCode(pan, amount);

        log.info("Decision: PAN={}***, amount={}, response={}",
                pan.substring(0, 6), amount, responseCode);

        // Step 3: Build response message
        // WHY MTI 0210: Response to 0200 (authorization request → authorization response)
        return buildResponseMessage(correlationId, responseCode, pan);
    }

    private String determineResponseCode(String pan, String amountStr) {
        // Priority 1: Card BIN rules (deterministic, for test cards)
        String binDecision = cardBinRules.evaluate(pan);
        if (binDecision != null) {
            return binDecision;
        }

        // Priority 2: Amount rules (deterministic, for testing edge cases)
        String amountDecision = amountRules.evaluate(amountStr);
        if (amountDecision != null) {
            return amountDecision;
        }

        // Priority 3: Random based on configured success rate
        // WHY random: Simulates real-world where some transactions fail for various reasons
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < config.getSuccessRatePercent()) {
            return "00";  // Approved
        } else {
            // WHY different decline codes: Real banks use different codes for different reasons
            return randomDeclineCode();
        }
    }

    private String randomDeclineCode() {
        // WHY: Different decline codes help test error handling in payment-service
        String[] declineCodes = {
                "05",  // Do not honor (generic decline)
                "14",  // Invalid card number
                "51",  // Insufficient funds
                "54",  // Expired card
                "61",  // Exceeds withdrawal amount limit
                "65"   // Exceeds withdrawal frequency limit
        };
        return declineCodes[ThreadLocalRandom.current().nextInt(declineCodes.length)];
    }

    private byte[] buildResponseMessage(String correlationId, String responseCode, String pan) {
        // WHY: Build a valid ISO 8583 response (MTI 0210)
        // In a real implementation, this would use Iso8583MessageBuilder
        // Simplified for the simulator:

        byte[] response = new byte[60];  // Fixed size for simplicity

        // MTI: 0210 (authorization response)
        response[0] = 0x02;
        response[1] = 0x10;

        // Bitmap: indicates which fields are present
        response[2] = 0x38;  // Fields 2, 3, 4 present (simplified)

        // Field 37: Correlation ID (12 bytes, position 20)
        byte[] corrBytes = correlationId.getBytes();
        System.arraycopy(corrBytes, 0, response, 20, Math.min(12, corrBytes.length));

        // Field 39: Response code (2 bytes, position 32)
        response[32] = (byte) responseCode.charAt(0);
        response[33] = (byte) responseCode.charAt(1);

        return response;
    }

    // --- Field extraction helpers ---

    private String extractMti(byte[] msg) {
        return String.format("%02X%02X", msg[0], msg[1]);
    }

    private String extractPan(byte[] msg) {
        // WHY: PAN starts at a known offset in our simplified format
        return new String(msg, 4, 16).trim();
    }

    private String extractAmount(byte[] msg) {
        return new String(msg, 36, 12).trim();
    }

    private String extractCorrelationId(byte[] msg) {
        return new String(msg, 20, 12).trim();
    }
}
```

---

## 6. CardBinRules

Deterministic rules based on the first 4-6 digits of the card number.

```java
package com.payflow.banksimulator;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Card BIN (Bank Identification Number) Rules
 *
 * WHY: Test cards need PREDICTABLE behavior.
 * When writing tests, you need to know "if I send card 4111..., it will approve."
 * Without this, tests would be flaky (random approve/decline).
 *
 * Industry standard test cards:
 * - 4111 1111 1111 1111 = Visa test card (always approve)
 * - 4000 0000 0000 0002 = Visa test card (always decline)
 */
@Component
public class CardBinRules {

    // WHY LinkedHashMap: Preserves insertion order (more specific rules first)
    private final Map<String, String> rules = new LinkedHashMap<>();

    public CardBinRules() {
        // ═══════════ ALWAYS APPROVE ═══════════
        rules.put("4111", "00");  // Classic Visa test card → APPROVED
        rules.put("5500", "00");  // Mastercard test card → APPROVED
        rules.put("3782", "00");  // Amex test card → APPROVED

        // ═══════════ ALWAYS DECLINE ═══════════
        rules.put("4000", "05");  // Decline: "Do not honor"
        rules.put("4100", "14");  // Decline: "Invalid card number"
        rules.put("4200", "51");  // Decline: "Insufficient funds"
        rules.put("4300", "54");  // Decline: "Expired card"

        // ═══════════ SPECIAL BEHAVIORS ═══════════
        rules.put("4400", "TIMEOUT");  // Simulate timeout (no response)
        rules.put("4500", "ERROR");    // Simulate protocol error
    }

    /**
     * @return Response code if BIN matches a rule, null if no rule applies
     */
    public String evaluate(String pan) {
        if (pan == null || pan.length() < 4) return null;

        // WHY: Check each rule prefix against the PAN
        for (Map.Entry<String, String> entry : rules.entrySet()) {
            if (pan.startsWith(entry.getKey())) {
                String result = entry.getValue();

                // WHY: TIMEOUT = don't respond at all (simulates bank hanging)
                if ("TIMEOUT".equals(result)) {
                    return null;  // Handler will detect and not send response
                }

                return result;
            }
        }

        return null;  // No rule matched — fall through to amount/random rules
    }
}
```

### Test Cards Reference Table

| Card Number | BIN | Behavior | Use Case |
|-------------|-----|----------|----------|
| 4111 1111 1111 1111 | 4111 | Always approve | Happy path testing |
| 5500 0000 0000 0004 | 5500 | Always approve | Mastercard happy path |
| 4000 0000 0000 0002 | 4000 | Always decline (05) | Decline handling |
| 4100 0000 0000 0001 | 4100 | Decline: Invalid card | Validation error testing |
| 4200 0000 0000 0000 | 4200 | Decline: NSF | Insufficient funds testing |
| 4300 0000 0000 0009 | 4300 | Decline: Expired | Expiry check testing |
| 4400 0000 0000 0008 | 4400 | Timeout (no response) | Timeout handling testing |
| 4500 0000 0000 0007 | 4500 | Protocol error | Error recovery testing |

---

## 7. AmountRules

Rules based on the transaction amount.

```java
package com.payflow.banksimulator;

import org.springframework.stereotype.Component;

/**
 * Amount-based decision rules.
 *
 * WHY: Additional deterministic rules for testing specific scenarios.
 * These apply AFTER CardBinRules (only if no BIN rule matched).
 */
@Component
public class AmountRules {

    /**
     * @param amountStr Amount as string (in smallest currency unit, e.g., paise)
     * @return Response code if amount rule matches, null otherwise
     */
    public String evaluate(String amountStr) {
        if (amountStr == null || amountStr.isBlank()) return null;

        try {
            long amount = Long.parseLong(amountStr.trim());

            // Rule 1: Very high amount → always decline
            // WHY: Simulates bank's internal fraud limit
            // Real banks decline amounts above merchant's approved limit
            if (amount > 10_000_000) {  // > ₹1,00,000 (in paise)
                return "61";  // "Exceeds withdrawal amount limit"
            }

            // Rule 2: Amount ending in 13 → always decline
            // WHY: Easy way to trigger declines in tests without specific cards
            // Just set amount to ₹1013, ₹5013, etc.
            if (amount % 100 == 13) {
                return "05";  // "Do not honor"
            }

            // Rule 3: Amount ending in 99 → insufficient funds
            // WHY: Another test trigger for a specific decline reason
            if (amount % 100 == 99) {
                return "51";  // "Insufficient funds"
            }

            // Rule 4: Zero amount → invalid transaction
            if (amount == 0) {
                return "12";  // "Invalid transaction"
            }

            return null;  // No amount rule matched

        } catch (NumberFormatException e) {
            // WHY: If amount can't be parsed, it's an error in the request
            return "30";  // "Format error"
        }
    }
}
```

### Amount Rules Quick Reference

| Amount Pattern | Response Code | Meaning | Example |
|---------------|---------------|---------|---------|
| > ₹1,00,000 | 61 | Exceeds limit | ₹1,50,000 → decline |
| Ends in 13 | 05 | Do not honor | ₹10,013 → decline |
| Ends in 99 | 51 | Insufficient funds | ₹5,099 → decline |
| = 0 | 12 | Invalid transaction | ₹0 → decline |
| Any other | null | Fall to random | ₹5,000 → random |

---

## 8. SimulatorConfig

```java
package com.payflow.banksimulator;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configurable parameters for the bank simulator.
 *
 * WHY configurable: Different test scenarios need different behaviors.
 * - Integration tests: 100% success rate (deterministic)
 * - Load tests: 85% success rate (realistic)
 * - Chaos tests: 50% success rate (stress testing)
 */
@Data
@Component
@ConfigurationProperties(prefix = "simulator")
public class SimulatorConfig {

    // WHY 85%: Realistic — real banks approve ~85-95% of transactions
    private int successRatePercent = 85;

    // WHY 200ms min: Even fast banks have network + processing overhead
    private int minLatencyMs = 200;

    // WHY 2000ms max: Banks under load can take up to 2 seconds
    private int maxLatencyMs = 2000;

    // WHY: Sometimes you want to test timeout handling
    private int timeoutPercent = 2;  // 2% of transactions will timeout

    // WHY: Enable/disable latency simulation (disable for fast unit tests)
    private boolean latencyEnabled = true;

    // WHY: Port to listen on (matches routing-service config)
    private int port = 9090;
}
```

### Application Properties

```yaml
# application.yml for bank-simulator
server:
  port: 8085  # HTTP port (for management/health endpoints)

simulator:
  port: 9090              # TCP port for ISO 8583
  success-rate-percent: 85
  min-latency-ms: 200
  max-latency-ms: 2000
  timeout-percent: 2
  latency-enabled: true

# Profiles for different testing scenarios
---
spring:
  config:
    activate:
      on-profile: fast-test
simulator:
  success-rate-percent: 100   # Always approve
  min-latency-ms: 0           # No latency
  max-latency-ms: 0
  latency-enabled: false      # Skip sleep entirely

---
spring:
  config:
    activate:
      on-profile: chaos
simulator:
  success-rate-percent: 50    # 50% failure rate
  min-latency-ms: 1000       # Always slow
  max-latency-ms: 5000       # Very slow
  timeout-percent: 10         # 10% timeout
```

---

## 9. Testing the Simulator

### Integration Test: Send ISO 8583 Request

```java
package com.payflow.banksimulator;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BankSimulatorIntegrationTest {

    private EventLoopGroup clientGroup;

    @BeforeEach
    void setup() {
        clientGroup = new NioEventLoopGroup(1);
    }

    @AfterEach
    void teardown() {
        clientGroup.shutdownGracefully();
    }

    @Test
    @DisplayName("Should approve transaction with test card 4111")
    void shouldApproveTestCard() throws Exception {
        // Given: An ISO 8583 authorization request with test card 4111
        byte[] request = buildTestRequest("4111111111111111", "000000005000");
        CompletableFuture<byte[]> responseFuture = new CompletableFuture<>();

        // When: Send to bank simulator
        Channel channel = connectAndSend(request, responseFuture);

        // Then: Should receive approval (response code "00")
        byte[] response = responseFuture.get(10, TimeUnit.SECONDS);
        String responseCode = new String(response, 32, 2);
        assertThat(responseCode).isEqualTo("00");

        channel.close();
    }

    @Test
    @DisplayName("Should decline transaction with test card 4000")
    void shouldDeclineTestCard() throws Exception {
        // Given: Decline test card
        byte[] request = buildTestRequest("4000000000000002", "000000005000");
        CompletableFuture<byte[]> responseFuture = new CompletableFuture<>();

        // When
        Channel channel = connectAndSend(request, responseFuture);

        // Then: Should decline with code "05"
        byte[] response = responseFuture.get(10, TimeUnit.SECONDS);
        String responseCode = new String(response, 32, 2);
        assertThat(responseCode).isEqualTo("05");

        channel.close();
    }

    @Test
    @DisplayName("Should decline amount > 1,00,000")
    void shouldDeclineHighAmount() throws Exception {
        // Given: Amount over limit (₹1,50,000 = 15000000 paise)
        byte[] request = buildTestRequest("5555555555554444", "000015000000");
        CompletableFuture<byte[]> responseFuture = new CompletableFuture<>();

        // When
        Channel channel = connectAndSend(request, responseFuture);

        // Then: Should decline with "61" (exceeds limit)
        byte[] response = responseFuture.get(10, TimeUnit.SECONDS);
        String responseCode = new String(response, 32, 2);
        assertThat(responseCode).isEqualTo("61");

        channel.close();
    }

    private Channel connectAndSend(byte[] request, CompletableFuture<byte[]> future) throws Exception {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline()
                                .addLast(new LengthFieldBasedFrameDecoder(65535, 0, 2, 0, 2))
                                .addLast(new LengthFieldPrepender(2))
                                .addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                                        byte[] bytes = new byte[msg.readableBytes()];
                                        msg.readBytes(bytes);
                                        future.complete(bytes);
                                    }
                                });
                    }
                });

        Channel channel = bootstrap.connect("localhost", 9090).sync().channel();
        channel.writeAndFlush(Unpooled.wrappedBuffer(request));
        return channel;
    }

    private byte[] buildTestRequest(String pan, String amount) {
        // Simplified ISO 8583 request builder for testing
        byte[] msg = new byte[60];
        msg[0] = 0x02; msg[1] = 0x00;  // MTI: 0200
        System.arraycopy(pan.getBytes(), 0, msg, 4, 16);  // PAN
        System.arraycopy(amount.getBytes(), 0, msg, 36, 12);  // Amount
        // Correlation ID
        String corrId = "TST" + System.currentTimeMillis() % 1000000000;
        System.arraycopy(corrId.getBytes(), 0, msg, 20, Math.min(12, corrId.length()));
        return msg;
    }
}
```

### Manual Testing with netcat

```bash
# Start the bank simulator
./mvnw spring-boot:run -pl backend/bank-simulator

# In another terminal, verify it's listening
netstat -tlnp | grep 9090
# Output: tcp  0  0  0.0.0.0:9090  0.0.0.0:*  LISTEN

# Health check via HTTP
curl http://localhost:8085/actuator/health
# {"status":"UP"}
```

---

## 10. What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Bank Simulator Purpose | Test entire payment flow without real bank connections |
| 2 | Netty ServerBootstrap | Server uses 2 EventLoopGroups (boss accepts, workers handle) |
| 3 | Request Handler | SimpleChannelInboundHandler processes ISO 8583 requests |
| 4 | Response Generator | Separates decision logic from network I/O |
| 5 | CardBinRules | Deterministic approve/decline based on card prefix (4111=approve) |
| 6 | AmountRules | Amount-based rules (>₹1L decline, ends-in-13 decline) |
| 7 | SimulatorConfig | Configurable success rate, latency, timeout simulation |
| 8 | Spring Profiles | `fast-test` for CI, `chaos` for stress testing |
| 9 | Integration Testing | Full TCP roundtrip test with Netty client |
| 10 | ISO 8583 Response Codes | 00=approve, 05=decline, 51=NSF, 61=limit exceeded |

---

## 11. Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `Address already in use: 9090` | Another process using port 9090 | Kill it: `lsof -i:9090` or change `simulator.port` |
| `Connection refused` | Simulator not started yet | Wait for "Bank Simulator started" log message |
| Response always timeout | Card starts with `4400` (timeout test card) | Use different card number |
| All transactions decline | `success-rate-percent: 0` in config | Set to 85 (default) |
| Test flaky (random pass/fail) | Using random success rate in tests | Use `fast-test` profile: 100% success |
| `DecoderException` | Client/server frame format mismatch | Ensure both use 2-byte length prefix |
| Large latency in tests | `latency-enabled: true` | Use `fast-test` profile or set to false |
| `PortUnreachableException` | Simulator crashed during test | Check logs for exceptions, restart |

---

## 12. Git Commit

```bash
# Stage bank simulator files
git add backend/bank-simulator/

# Commit
git commit -m "feat(bank-sim): add bank simulator with configurable approve/decline rules

- BankSimulatorServer: Netty TCP server on port 9090
- Iso8583RequestHandler: processes auth requests, simulates latency
- ResponseGenerator: orchestrates decision logic
- CardBinRules: deterministic rules (4111=approve, 4000=decline)
- AmountRules: amount-based rules (>1L decline, ends-in-13 decline)
- SimulatorConfig: configurable success rate, latency, profiles
- Integration test: full TCP roundtrip with approve/decline verification
- Spring profiles: fast-test (100%, no latency), chaos (50%, high latency)"

# Push
git push origin feature/phase4-bank-simulator
```

---

## Document Index

| # | Document | Status |
|---|----------|--------|
| 01 | Project Overview & Architecture | ✅ |
| 02 | Development Environment Setup | ✅ |
| 03 | Merchant Service (CRUD + Auth) | ✅ |
| 04 | Payment Service (Core Processing) | ✅ |
| 05 | API Gateway (Routing + Security) | ✅ |
| 06 | Kafka Event Streaming | ✅ |
| 07 | Redis Caching & Idempotency | ✅ |
| 08 | Ledger Service (Double-Entry) | ✅ |
| 09a | ISO 8583 Message Parsing | ✅ |
| 09b | Netty TCP Client | ✅ |
| 09c | Fraud Detection & Smart Routing | ✅ |
| **10** | **Bank Simulator** | **📍 Current** |
| 11 | Settlement Service | 🔜 Next |
| 12 | Webhook Service | ⬜ |
| 13 | Notification Service | ⬜ |
| 14 | Docker & Containerization | ⬜ |
| 15a | Frontend Setup | ⬜ |
| 15b | Frontend Features | ⬜ |

---

## Next Steps

In **Part 11**, we'll build the **Settlement Service** — the end-of-day batch job that:
- Reads all CAPTURED payments (T+1 settlement)
- Calculates fees (MDR + GST)
- Creates payout records for merchants
- Uses Spring Batch for reliable, restartable processing

---

[← Previous: Part 9C — Fraud & Smart Routing](./phase4-part09c-routing-fraud-smartrouting.md) | [Next: Part 11 — Settlement Service →](./phase4-part11-settlement-service.md)
