# Phase 4 · Part 9B — Netty TCP Client for Bank Communication

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Routing & Bank Integration |
| **Part** | 9B — Netty TCP Client |
| **Previous** | [Part 9A — ISO 8583 Message Parsing](./phase4-part09a-routing-iso8583.md) |
| **Next** | [Part 9C — Fraud Detection & Smart Routing](./phase4-part09c-routing-fraud-smartrouting.md) |
| **Time** | ~3 hours |
| **Difficulty** | ★★★★☆ (Advanced) |
| **Prerequisites** | Java NIO basics, ISO 8583 message format (Part 9A) |
| **What You'll Build** | A high-performance Netty TCP client that sends ISO 8583 messages to banks |
| **Git Commit** | `feat(routing): add Netty TCP client for bank communication` |

---

## Table of Contents

1. [What is Netty?](#1-what-is-netty)
2. [Why Netty Over Plain Sockets?](#2-why-netty-over-plain-sockets)
3. [BankNettyClient.java](#3-banknettyclientjava)
4. [BankChannelInitializer](#4-bankchannelinitializer)
5. [Iso8583Encoder](#5-iso8583encoder)
6. [Iso8583Decoder](#6-iso8583decoder)
7. [BankResponseHandler](#7-bankresponsehandler)
8. [Channel Pipeline Diagram](#8-channel-pipeline-diagram)
9. [Connection Lifecycle](#9-connection-lifecycle)
10. [Timeout Handling](#10-timeout-handling)
11. [What You Learned](#11-what-you-learned)
12. [Common Errors & Fixes](#12-common-errors--fixes)
13. [Git Commit](#13-git-commit)

---

## What You'll Learn

- How Netty's non-blocking I/O model works (event loop, channels, handlers)
- Why Netty is the standard for financial protocol communication
- How to build a TCP client that sends binary ISO 8583 messages
- How pipeline codecs encode/decode messages automatically
- How CompletableFuture bridges async Netty responses to synchronous callers
- How to handle timeouts when banks don't respond

---

## 1. What is Netty?

Netty is an **asynchronous, event-driven network framework** for building high-performance protocol servers and clients. It wraps Java NIO into an easy-to-use API.

### The Event Loop Model (Simplified)

```
Traditional Threading (one thread per connection):
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│  Thread 1   │  │  Thread 2   │  │  Thread 3   │
│  Conn → HDFC│  │  Conn → SBI │  │  Conn → ICICI│
│  (BLOCKED)  │  │  (BLOCKED)  │  │  (BLOCKED)  │
└─────────────┘  └─────────────┘  └─────────────┘
Problem: 1000 connections = 1000 threads = memory explosion

Netty Event Loop (few threads handle many connections):
┌──────────────────────────────────────────────┐
│            Event Loop (1 thread)              │
│                                              │
│  Channel 1 (HDFC) ──→ ready? → process      │
│  Channel 2 (SBI)  ──→ ready? → process      │
│  Channel 3 (ICICI)──→ ready? → process      │
│  ...                                         │
│  Channel 500      ──→ ready? → process      │
└──────────────────────────────────────────────┘
Result: 4 threads handle 10,000+ connections
```

### Key Concepts

| Concept | What It Is | Analogy |
|---------|-----------|---------|
| **Channel** | A connection (TCP socket wrapper) | A phone line to the bank |
| **EventLoop** | A thread that monitors multiple channels | A receptionist handling 100 phone lines |
| **EventLoopGroup** | A pool of EventLoops | The reception desk with 4 receptionists |
| **ChannelPipeline** | Chain of handlers that process data | Assembly line for messages |
| **ChannelHandler** | One processing step in the pipeline | One worker on the assembly line |
| **Bootstrap** | Client configuration builder | Instructions to set up the phone system |

### Non-Blocking I/O Explained Simply

```java
// BLOCKING (traditional): Thread sits idle waiting for bank
Socket socket = new Socket("hdfc-bank.com", 9090);
OutputStream out = socket.getOutputStream();
out.write(isoMessage);           // Thread waits until write completes
InputStream in = socket.getInputStream();
byte[] response = in.read();     // Thread waits until bank responds (1-5 seconds!)
// During those 5 seconds, this thread did NOTHING but wait

// NON-BLOCKING (Netty): Thread sends and moves on
channel.writeAndFlush(isoMessage);  // Returns immediately!
// Thread is now free to handle other connections
// When bank responds, Netty calls our handler automatically
```

---

## 2. Why Netty Over Plain Sockets?

| Feature | Plain Java Sockets | Netty |
|---------|-------------------|-------|
| Threading model | 1 thread per connection | Event loop (few threads, many connections) |
| Connection pooling | Manual implementation | Built-in via ChannelPool |
| Message framing | Manual buffer management | LengthFieldBasedFrameDecoder |
| Codec pipeline | All in one class | Separated, reusable handlers |
| Timeout handling | Thread.sleep / ScheduledExecutor | Built-in IdleStateHandler |
| Memory management | GC pressure from byte[] | Zero-copy ByteBuf with pooling |
| Reconnection | Manual logic | Built-in reconnect handlers |
| SSL/TLS | Complex SSLSocket setup | SslHandler in pipeline |
| Backpressure | None | Channel.isWritable() |

### Why Banks Use TCP (Not HTTP)

Banks use raw TCP because:
1. **Lower latency** — No HTTP header overhead (saves ~200 bytes per message)
2. **Binary protocol** — ISO 8583 is binary, not text-based
3. **Persistent connections** — Banks expect long-lived connections, not connect/disconnect per request
4. **Industry standard** — Visa, Mastercard, all banks speak ISO 8583 over TCP

---

## 3. BankNettyClient.java

This is the main class that manages the TCP connection to a bank.

```java
package com.payflow.routing.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

@Slf4j
@Component
public class BankNettyClient {

    // WHY: EventLoopGroup is a pool of threads that handle I/O operations.
    // We only need 2 threads because we're a CLIENT (not handling thousands of incoming connections)
    private final EventLoopGroup workerGroup = new NioEventLoopGroup(2);

    // WHY: ConcurrentHashMap because multiple threads may send messages simultaneously.
    // Key = correlationId (from ISO 8583 field 37), Value = the Future waiting for response
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> pendingRequests
            = new ConcurrentHashMap<>();

    // WHY: volatile because the channel is set by Netty's thread but read by Spring's threads
    private volatile Channel channel;

    @Value("${bank.host:localhost}")
    private String bankHost;

    @Value("${bank.port:9090}")
    private int bankPort;

    @Value("${bank.timeout-ms:5000}")
    private long timeoutMs;  // WHY: Banks should respond within 5 seconds. Beyond that = timeout

    private final ScheduledExecutorService timeoutScheduler =
            Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void connect() {
        // WHY: Bootstrap is Netty's builder pattern for configuring the client.
        // It chains all the settings together cleanly.
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)                          // Which thread pool to use
                .channel(NioSocketChannel.class)              // TCP via NIO (non-blocking)
                .option(ChannelOption.SO_KEEPALIVE, true)     // Keep TCP connection alive
                .option(ChannelOption.TCP_NODELAY, true)      // Disable Nagle's algorithm (send immediately)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000) // 3s to establish connection
                .handler(new BankChannelInitializer(pendingRequests)); // Pipeline setup

        // WHY: connect() is async. We add a listener to know when it's done.
        bootstrap.connect(bankHost, bankPort).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                channel = future.channel();
                log.info("✅ Connected to bank at {}:{}", bankHost, bankPort);
            } else {
                log.error("❌ Failed to connect to bank at {}:{}", bankHost, bankPort,
                        future.cause());
                // WHY: Schedule reconnection after 5 seconds
                scheduleReconnect(bootstrap);
            }
        });
    }

    /**
     * Sends an ISO 8583 message to the bank and waits for a response.
     *
     * WHY CompletableFuture: Netty is async, but our caller (RoutingService) wants
     * a response. CompletableFuture bridges this gap — we return a Future that will
     * be completed when the bank responds (or times out).
     */
    public CompletableFuture<byte[]> send(byte[] isoMessage, String correlationId) {
        CompletableFuture<byte[]> responseFuture = new CompletableFuture<>();

        // WHY: Store the future so BankResponseHandler can complete it when response arrives
        pendingRequests.put(correlationId, responseFuture);

        // WHY: Check if channel is active before sending (bank might be disconnected)
        if (channel == null || !channel.isActive()) {
            responseFuture.completeExceptionally(
                    new IllegalStateException("Bank connection is not active"));
            pendingRequests.remove(correlationId);
            return responseFuture;
        }

        // WHY: writeAndFlush sends the message through the pipeline (Encoder → TCP)
        channel.writeAndFlush(isoMessage).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                responseFuture.completeExceptionally(future.cause());
                pendingRequests.remove(correlationId);
            }
        });

        // WHY: Schedule a timeout. If bank doesn't respond in 5s, complete exceptionally.
        scheduleTimeout(correlationId, responseFuture);

        return responseFuture;
    }

    private void scheduleTimeout(String correlationId, CompletableFuture<byte[]> future) {
        timeoutScheduler.schedule(() -> {
            // WHY: completeExceptionally only works if the future hasn't been completed yet.
            // If the bank already responded, this is a no-op.
            if (future.completeExceptionally(
                    new TimeoutException("Bank did not respond within " + timeoutMs + "ms"))) {
                pendingRequests.remove(correlationId);
                log.warn("⏰ Timeout waiting for bank response. correlationId={}", correlationId);
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void scheduleReconnect(Bootstrap bootstrap) {
        // WHY: Don't reconnect immediately — give the bank time to recover
        workerGroup.schedule(() -> {
            log.info("🔄 Attempting to reconnect to bank...");
            bootstrap.connect(bankHost, bankPort).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    channel = future.channel();
                    log.info("✅ Reconnected to bank");
                } else {
                    scheduleReconnect(bootstrap);  // Keep trying
                }
            });
        }, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        // WHY: Graceful shutdown — finish pending operations, then release threads
        if (channel != null) {
            channel.close();
        }
        workerGroup.shutdownGracefully();
        timeoutScheduler.shutdown();
        log.info("🛑 Bank Netty client shut down");
    }
}
```

---

## 4. BankChannelInitializer

The initializer sets up the **pipeline** — the chain of handlers that process data.

```java
package com.payflow.routing.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * WHY this class exists:
 * Netty calls initChannel() for every new connection.
 * We configure the pipeline here — the order of handlers matters!
 */
public class BankChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> pendingRequests;

    public BankChannelInitializer(
            ConcurrentHashMap<String, CompletableFuture<byte[]>> pendingRequests) {
        this.pendingRequests = pendingRequests;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // INBOUND HANDLERS (data coming FROM bank):
        // Order matters! Data flows through these top-to-bottom

        // 1. Frame decoder: reads the first 2 bytes as message length
        // WHY: TCP is a stream protocol — messages can arrive split or merged.
        // This handler waits until we have a complete message before passing it on.
        pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(
                65535,  // maxFrameLength — max ISO 8583 message size
                0,      // lengthFieldOffset — length field starts at byte 0
                2,      // lengthFieldLength — length is stored in 2 bytes
                0,      // lengthAdjustment — no adjustment needed
                2       // initialBytesToStrip — remove the 2-byte length header
        ));

        // 2. ISO 8583 Decoder: converts raw bytes → structured message
        pipeline.addLast("iso8583Decoder", new Iso8583Decoder());

        // OUTBOUND HANDLERS (data going TO bank):
        // Order matters! Data flows through these bottom-to-top

        // 3. Frame encoder: prepends 2-byte length header before sending
        // WHY: The bank needs to know how long our message is
        pipeline.addLast("frameEncoder", new LengthFieldPrepender(2));

        // 4. ISO 8583 Encoder: converts structured message → raw bytes
        pipeline.addLast("iso8583Encoder", new Iso8583Encoder());

        // DUPLEX HANDLER (handles both inbound events):

        // 5. Idle state handler: fires event if no read/write for 30 seconds
        // WHY: Detect dead connections (bank crashed without closing socket)
        pipeline.addLast("idleState", new IdleStateHandler(30, 0, 0, TimeUnit.SECONDS));

        // 6. Business logic: matches responses to pending requests
        pipeline.addLast("responseHandler", new BankResponseHandler(pendingRequests));
    }
}
```

---

## 5. Iso8583Encoder

Converts our ISO 8583 message object into binary bytes for the wire.

```java
package com.payflow.routing.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import lombok.extern.slf4j.Slf4j;

/**
 * OUTBOUND handler: Java object → binary bytes
 *
 * WHY MessageToByteEncoder:
 * Netty provides this base class specifically for encoding objects into bytes.
 * We just implement encode() — Netty handles buffer management and flushing.
 */
@Slf4j
public class Iso8583Encoder extends MessageToByteEncoder<byte[]> {

    @Override
    protected void encode(ChannelHandlerContext ctx, byte[] message, ByteBuf out) {
        // WHY: We receive the raw ISO 8583 bytes (already built by Iso8583MessageBuilder)
        // and write them to Netty's ByteBuf. The LengthFieldPrepender upstream
        // will automatically add the 2-byte length prefix.

        log.debug("Encoding ISO 8583 message: {} bytes", message.length);
        out.writeBytes(message);

        // FLOW: message (byte[]) → Iso8583Encoder → LengthFieldPrepender → TCP socket
        // Example: [0x02, 0x00, ...] → [0x00, 0x4A, 0x02, 0x00, ...]
        //                                 ↑ 2-byte length (74 bytes)
    }
}
```

---

## 6. Iso8583Decoder

Converts binary bytes from the bank into a usable message.

```java
package com.payflow.routing.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * INBOUND handler: binary bytes → Java object
 *
 * WHY ByteToMessageDecoder:
 * Netty calls decode() when bytes arrive. The LengthFieldBasedFrameDecoder upstream
 * already stripped the 2-byte length prefix, so we receive exactly one complete message.
 */
@Slf4j
public class Iso8583Decoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // WHY: readableBytes() tells us how much data is available.
        // Thanks to LengthFieldBasedFrameDecoder, this is always one complete message.
        int length = in.readableBytes();

        if (length < 2) {
            // WHY: Minimum ISO 8583 message has at least MTI (2 bytes for binary)
            return; // Wait for more data (shouldn't happen with frame decoder)
        }

        // WHY: Read all bytes into a byte array for downstream processing
        byte[] messageBytes = new byte[length];
        in.readBytes(messageBytes);

        log.debug("Decoded ISO 8583 response: {} bytes", length);

        // WHY: Add to 'out' list — Netty passes this to the next inbound handler
        out.add(messageBytes);

        // FLOW: TCP socket → LengthFieldBasedFrameDecoder → Iso8583Decoder → BankResponseHandler
        // Bank sends: [0x00, 0x4A, 0x02, 0x10, ...]
        //                         ↓ strip length
        //             [0x02, 0x10, ...] (pure ISO 8583 message)
    }
}
```

---

## 7. BankResponseHandler

The final handler that matches bank responses to pending requests.

```java
package com.payflow.routing.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WHY SimpleChannelInboundHandler<byte[]>:
 * - SimpleChannel = auto-releases the message after channelRead0
 * - InboundHandler = handles data coming FROM the bank
 * - <byte[]> = we expect byte arrays (from Iso8583Decoder)
 */
@Slf4j
public class BankResponseHandler extends SimpleChannelInboundHandler<byte[]> {

    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> pendingRequests;

    public BankResponseHandler(
            ConcurrentHashMap<String, CompletableFuture<byte[]>> pendingRequests) {
        this.pendingRequests = pendingRequests;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, byte[] responseBytes) {
        // WHY: Extract the correlation ID from field 37 (Retrieval Reference Number)
        // This tells us WHICH request this response belongs to
        String correlationId = extractCorrelationId(responseBytes);

        log.info("📨 Received bank response for correlationId={}", correlationId);

        // WHY: Remove and complete the matching Future
        CompletableFuture<byte[]> future = pendingRequests.remove(correlationId);

        if (future != null) {
            // WHY: complete() signals the waiting thread that the response is ready
            future.complete(responseBytes);
        } else {
            // WHY: This happens if the timeout already fired (response came too late)
            log.warn("⚠️ No pending request for correlationId={}. Possible timeout.", correlationId);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        // WHY: IdleStateHandler fires this event when no data for 30 seconds
        if (evt instanceof IdleStateEvent) {
            log.warn("💤 Connection idle for 30s. Sending keep-alive or closing.");
            // In production: send a network management message (MTI 0800)
            // For now: close and let BankNettyClient reconnect
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // WHY: Any unhandled exception in the pipeline ends up here
        log.error("❌ Error in bank communication", cause);
        ctx.close();  // Close the broken connection
    }

    private String extractCorrelationId(byte[] responseBytes) {
        // WHY: Field 37 in ISO 8583 is the Retrieval Reference Number (12 chars)
        // Position depends on which fields are present (bitmap parsing needed)
        // Simplified: In our implementation, we put correlationId in a known position
        // Real implementation would parse the bitmap and extract field 37
        return new String(responseBytes, 20, 12).trim();
    }
}
```

---

## 8. Channel Pipeline Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      NETTY CHANNEL PIPELINE                             │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  OUTBOUND (Sending to bank)          INBOUND (Receiving from bank)     │
│  ─────────────────────────           ────────────────────────────      │
│                                                                         │
│  Java byte[] message                 TCP bytes from bank                │
│         │                                    │                          │
│         ▼                                    ▼                          │
│  ┌──────────────────┐              ┌─────────────────────────────┐    │
│  │ Iso8583Encoder   │              │ LengthFieldBasedFrameDecoder │    │
│  │ byte[] → ByteBuf │              │ Strip 2-byte length prefix   │    │
│  └────────┬─────────┘              └──────────────┬──────────────┘    │
│           │                                       │                    │
│           ▼                                       ▼                    │
│  ┌──────────────────────┐              ┌──────────────────┐           │
│  │ LengthFieldPrepender │              │  Iso8583Decoder  │           │
│  │ Add 2-byte length    │              │  ByteBuf → byte[]│           │
│  └────────┬─────────────┘              └────────┬─────────┘           │
│           │                                      │                     │
│           ▼                                      ▼                     │
│  ┌──────────────────┐              ┌──────────────────────────┐       │
│  │   TCP Socket     │              │   BankResponseHandler     │       │
│  │ (to bank network)│              │   Completes the Future    │       │
│  └──────────────────┘              └──────────────────────────┘       │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

Data flow example (sending authorization request to HDFC):

  OUTBOUND:
  [0x02,0x00,0x38,...] ──→ Encoder ──→ Prepender ──→ [0x00,0x4A,0x02,0x00,0x38,...] → TCP
       74 bytes                                        length=74  + 74 bytes

  INBOUND:
  TCP → [0x00,0x3C,0x02,0x10,0x38,...] ──→ FrameDecoder ──→ Decoder ──→ Handler
         length=60  + 60 bytes              strip [0x00,0x3C]    parse     complete Future
```

---

## 9. Connection Lifecycle

```
┌─────────────────────────────────────────────────────────────────┐
│                    CONNECTION LIFECYCLE                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. CONNECT                                                      │
│     ┌──────────┐         TCP 3-way handshake        ┌────────┐ │
│     │ PayFlow  │ ────── SYN ──────────────────────→ │  Bank  │ │
│     │ Routing  │ ←───── SYN-ACK ──────────────────  │ (9090) │ │
│     │ Service  │ ────── ACK ──────────────────────→ │        │ │
│     └──────────┘                                    └────────┘ │
│                                                                  │
│  2. SEND (Authorization Request)                                │
│     PayFlow ────→ [MTI:0200 + PAN + Amount + ...] ────→ Bank   │
│                                                                  │
│  3. RECEIVE (Authorization Response)                            │
│     PayFlow ←──── [MTI:0210 + Response Code + ...] ←──── Bank  │
│                                                                  │
│  4. KEEP-ALIVE (Connection stays open for next transaction)     │
│     PayFlow ←────────── idle 30s ──────────────────→ Bank      │
│     PayFlow ────→ [MTI:0800 Network Mgmt] ─────────→ Bank     │
│     PayFlow ←──── [MTI:0810 Echo Response] ←──────── Bank     │
│                                                                  │
│  5. CLOSE (Shutdown or error)                                   │
│     PayFlow ────── FIN ──────────────────────────→  Bank       │
│     PayFlow ←───── FIN-ACK ──────────────────────  Bank       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Connection States in Code

```java
// How the connection lifecycle maps to Netty events:

// 1. CONNECT → channelActive() is called
@Override
public void channelActive(ChannelHandlerContext ctx) {
    log.info("Connected to bank: {}", ctx.channel().remoteAddress());
}

// 2-3. SEND/RECEIVE → writeAndFlush() and channelRead0()
channel.writeAndFlush(requestBytes);  // Send
// ... Netty calls channelRead0() when response arrives

// 4. KEEP-ALIVE → userEventTriggered() with IdleStateEvent
// (handled in BankResponseHandler)

// 5. CLOSE → channelInactive() is called
@Override
public void channelInactive(ChannelHandlerContext ctx) {
    log.warn("Disconnected from bank. Scheduling reconnect...");
}
```

---

## 10. Timeout Handling

### What Happens When a Bank Doesn't Respond?

```
Timeline (5-second timeout configured):

t=0.000s  PayFlow sends auth request (MTI 0200)
           → CompletableFuture created
           → Timeout scheduled for t=5.000s

t=0.000s  ────────────────────────────── Request sent to bank ──→

                    ... bank is processing ...

SCENARIO A: Bank responds in time (t=1.2s)
t=1.200s  ←── Response received (MTI 0210, response code "00")
           → BankResponseHandler.channelRead0() called
           → future.complete(responseBytes)
           → Timeout fires at t=5s but future already completed (no-op)

SCENARIO B: Bank doesn't respond (timeout at t=5s)
t=5.000s  ⏰ Timeout fires!
           → future.completeExceptionally(TimeoutException)
           → pendingRequests.remove(correlationId)
           → Caller gets TimeoutException
t=7.000s  ←── Late response arrives from bank
           → BankResponseHandler.channelRead0() called
           → pendingRequests.get(correlationId) returns null
           → Log warning: "No pending request" (response discarded)
```

### Integration with RoutingService

```java
@Service
@Slf4j
public class RoutingService {

    private final BankNettyClient bankClient;

    public BankResponse sendToBank(IsoMessage request, String bankCode) {
        String correlationId = UUID.randomUUID().toString().substring(0, 12);
        byte[] requestBytes = request.toBytes();

        try {
            // WHY: .get() blocks the calling thread until Future completes or times out
            // In production, you'd use .thenApply() for fully async processing
            byte[] responseBytes = bankClient.send(requestBytes, correlationId)
                    .get(6, TimeUnit.SECONDS);  // Extra 1s buffer beyond Netty timeout

            return parseResponse(responseBytes);

        } catch (TimeoutException e) {
            // WHY: Bank didn't respond. Return a timeout response to upstream.
            log.error("Bank {} timed out for transaction {}", bankCode, correlationId);
            return BankResponse.timeout(correlationId);

        } catch (ExecutionException e) {
            // WHY: Something went wrong (connection lost, encoding error, etc.)
            log.error("Bank communication failed", e.getCause());
            return BankResponse.error(correlationId, e.getCause().getMessage());
        }
    }
}
```

---

## 11. What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Netty Event Loop | Few threads handle thousands of connections via non-blocking I/O |
| 2 | Bootstrap | Builder pattern to configure Netty client (channel type, options, handler) |
| 3 | Channel Pipeline | Ordered chain of handlers — each does one job (encode, decode, handle) |
| 4 | LengthFieldBasedFrameDecoder | Solves TCP framing — ensures we receive complete messages |
| 5 | MessageToByteEncoder | Converts Java objects to binary for the wire |
| 6 | ByteToMessageDecoder | Converts binary from wire to Java objects |
| 7 | SimpleChannelInboundHandler | Business logic handler — matches responses to requests |
| 8 | CompletableFuture bridge | Links async Netty to synchronous callers |
| 9 | Timeout scheduling | ScheduledExecutor completes Future exceptionally after N seconds |
| 10 | Reconnection | Auto-retry connection with backoff when bank disconnects |

---

## 12. Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `ConnectException: Connection refused` | Bank simulator not running | Start bank-simulator on port 9090 |
| `ReadTimeoutException` | Bank took too long | Increase `bank.timeout-ms` or check bank health |
| `CorrelatedMessageException` | Response has unknown correlationId | Ensure field 37 matches between request/response |
| `IllegalReferenceCountException` | ByteBuf released twice | Don't call `release()` in SimpleChannelInboundHandler (it auto-releases) |
| `TooLongFrameException` | Message exceeds 65535 bytes | Check maxFrameLength in LengthFieldBasedFrameDecoder |
| `ClosedChannelException` | Writing to closed channel | Check `channel.isActive()` before write |
| `NotYetConnectedException` | Sending before connection established | Wait for `channelActive()` or use connect listener |
| Pipeline order wrong | Handlers in wrong order | Decoders first (inbound), Encoders after (outbound) |

---

## 13. Git Commit

```bash
# Stage the Netty client files
git add backend/routing-service/src/main/java/com/payflow/routing/netty/

# Commit with descriptive message
git commit -m "feat(routing): add Netty TCP client for bank communication

- BankNettyClient: Bootstrap + EventLoopGroup + CompletableFuture send
- BankChannelInitializer: pipeline with frame codec + ISO codec + handler
- Iso8583Encoder: MessageToByteEncoder for outbound messages
- Iso8583Decoder: ByteToMessageDecoder for inbound messages
- BankResponseHandler: matches responses to pending requests via correlationId
- Timeout handling: ScheduledExecutor completes Future after 5s
- Auto-reconnection on disconnect with 5s backoff"

# Push to feature branch
git push origin feature/phase4-routing-service
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
| **09b** | **Netty TCP Client** | **📍 Current** |
| 09c | Fraud Detection & Smart Routing | 🔜 Next |
| 10 | Bank Simulator | ⬜ |
| 11 | Settlement Service | ⬜ |
| 12 | Webhook Service | ⬜ |
| 13 | Notification Service | ⬜ |
| 14 | Docker & Containerization | ⬜ |
| 15a | Frontend Setup | ⬜ |
| 15b | Frontend Features | ⬜ |

---

## Next Steps

In **Part 9C**, we'll build the **Fraud Detection & Smart Routing** layer that sits BEFORE the Netty client:
- Rule-based fraud engine (velocity checks, geo-blocking)
- ML-inspired scoring (decision tree)
- Multi-armed bandit algorithm for choosing the best bank
- Circuit breaker for resilience

The flow: `Request → FraudCheck → SmartRouting → NettyClient → Bank`

---

[← Previous: Part 9A — ISO 8583](./phase4-part09a-routing-iso8583.md) | [Next: Part 9C — Fraud & Smart Routing →](./phase4-part09c-routing-fraud-smartrouting.md)
