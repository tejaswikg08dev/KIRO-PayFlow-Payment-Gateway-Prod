# 🏗️ Phase 4 Part 9d: Routing Service — Netty Config + Client

> **"Four threads. Ten thousand connections. That's the power of non-blocking I/O — and why banks chose Netty over thread-per-connection."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 9d — Netty Config + Client |
| **What You Build** | NettyConfig.java, BankNettyClient.java |
| **Previous** | [Part 9c — ISO 8583 Messages](./phase4-part09c-iso8583-messages.md) |
| **Next** | [Part 9e — Netty Pipeline](./phase4-part09e-netty-pipeline.md) |

---

## 📖 Table of Contents

1. [What Is Netty? — From Zero](#1-what-is-netty--from-zero)
2. [Why Netty and Not Plain Java Sockets?](#2-why-netty-and-not-plain-java-sockets)
3. [Folder Structure After This Part](#3-folder-structure-after-this-part)
4. [Step-by-Step: NettyConfig.java](#4-step-by-step-nettyconfigjava)
5. [Step-by-Step: BankNettyClient.java](#5-step-by-step-banknettyclientjava)
6. [The Connection Lifecycle](#6-the-connection-lifecycle)
7. [Async → Sync Bridge: CompletableFuture](#7-async--sync-bridge-completablefuture)
8. [What You Learned](#8-what-you-learned)

---

## 1. What Is Netty? — From Zero

Netty is an **asynchronous, event-driven network framework** for building high-performance TCP/UDP clients and servers. It's the standard for financial protocol communication in Java.

### The Problem with Traditional Networking

```
TRADITIONAL JAVA (one thread per connection):

Thread-1 → connect to HDFC Bank → WAITING... (bank is thinking) → response
Thread-2 → connect to SBI Bank  → WAITING... (bank is thinking) → response
Thread-3 → connect to ICICI Bank → WAITING... (bank is thinking) → response
...
Thread-1000 → connect to bank → WAITING...

Problem: 1,000 simultaneous connections = 1,000 threads
  Each thread: ~512KB-1MB stack memory
  1,000 threads × 1MB = 1 GB just for thread stacks!
  Plus: thread context switching is expensive
```

### Netty's Solution: Event Loop

```
NETTY (few threads handle many connections):

┌──────────────────────────────────────────────────┐
│            Event Loop Thread 1                    │
│                                                   │
│  Channel 1 (HDFC) ──→ data ready? → YES → process│
│  Channel 2 (SBI)  ──→ data ready? → NO  → skip   │
│  Channel 3 (ICICI)──→ data ready? → YES → process│
│  ...                                              │
│  Channel 250      ──→ data ready? → NO  → skip   │
└──────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────┐
│            Event Loop Thread 2                    │
│  Channel 251-500 ──→ same polling loop           │
└──────────────────────────────────────────────────┘

Result: 4 threads handle 1,000+ connections
  Memory: 4 threads × 1MB = 4 MB (not 1 GB!)
  No context switching between 1,000 threads
```

### Key Netty Concepts

| Concept | What It Is | Analogy |
|---|---|---|
| **Channel** | A TCP connection (socket wrapper) | A phone line to the bank |
| **EventLoop** | A thread that monitors multiple channels | A receptionist handling 100 phone lines |
| **EventLoopGroup** | A pool of EventLoops | The reception desk with 4 receptionists |
| **Bootstrap** | Client configuration builder | The phone system setup manual |
| **ChannelPipeline** | Chain of handlers that process data | An assembly line for messages |
| **CompletableFuture** | Bridge between async Netty and sync callers | A callback promise |

---

## 2. Why Netty and Not Plain Java Sockets?

| Feature | Plain Java Sockets | Netty |
|---|---|---|
| Threading | 1 thread per connection | Event loop (few threads, many connections) |
| Connection management | Manual `Socket.connect()` + `close()` | `Bootstrap` + auto-reconnect |
| Message framing | Manual buffer management | `LengthFieldBasedFrameDecoder` |
| Codec pipeline | All in one class | Separated, reusable handlers |
| Timeout handling | `Socket.setSoTimeout()` | `ReadTimeoutHandler` in pipeline |
| Memory | GC pressure from `byte[]` allocations | Pooled `ByteBuf` with reference counting |
| SSL/TLS | Complex `SSLSocket` setup | `SslHandler` in pipeline |

### Why Banks Use TCP (Not HTTP)

```
HTTP (what REST APIs use):
  POST /api/authorize HTTP/1.1\r\n     ← 30+ bytes just for the request line
  Host: bank.com\r\n                   ← more headers
  Content-Type: application/json\r\n   ← more headers
  Content-Length: 45\r\n\r\n           ← more headers
  {"pan":"4111...","amount":50000}     ← JSON payload
  Total: ~200+ bytes overhead

TCP with ISO 8583 (what banks use):
  [4-byte length][MTI][bitmap][fields]  ← pure data, no headers
  Total: ~80 bytes for same information

At 10,000 transactions/second:
  HTTP: 200 bytes × 10,000 = 2 MB/s overhead
  TCP:  80 bytes × 10,000 = 0.8 MB/s total
  Banks chose efficiency.
```

---

## 3. Folder Structure After This Part

```
backend/routing-service/src/main/java/com/payflow/routing/
├── RoutingServiceApplication.java    ← from 9a
├── config/
│   └── NettyConfig.java              ← YOU CREATE THIS
├── iso8583/                          ← from 9b, 9c
│   ├── Iso8583Field.java, BitmapUtils.java, Iso8583Constants.java
│   ├── Iso8583Message.java, Iso8583MessageBuilder.java, Iso8583MessageParser.java
└── netty/
    └── BankNettyClient.java          ← YOU CREATE THIS
```

---

## 4. Step-by-Step: NettyConfig.java

**File:** `src/main/java/com/payflow/routing/config/NettyConfig.java`

This configuration class creates the Netty `EventLoopGroup` — the thread pool that handles ALL TCP I/O.

### Full Source Code

```java
package com.payflow.routing.config;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
```

**NEW IMPORTS — Netty classes:**

| Import | What It Is |
|---|---|
| `EventLoopGroup` | Interface — a pool of event loop threads |
| `NioEventLoopGroup` | Implementation — uses Java NIO (non-blocking I/O) |

```java
/**
 * Netty configuration for the bank communication client.
 * Manages EventLoopGroup lifecycle and connection pool settings.
 */
@Configuration
public class NettyConfig {

    private static final Logger log = LoggerFactory.getLogger(NettyConfig.class);

    @Value("${netty.worker-threads:4}")
    private int workerThreads;

    @Value("${netty.connection-pool.max-connections:10}")
    private int maxConnections;

    @Value("${netty.connection-pool.max-idle-time-seconds:60}")
    private int maxIdleTimeSeconds;
```

**`@Value("${netty.worker-threads:4}")`** — reads from `application.yml`. The `:4` is the default if the property isn't found.

**THREE CONFIGURATION VALUES from application.yml:**

| Property | Default | Purpose |
|---|---|---|
| `workerThreads` | 4 | How many Netty I/O threads to create |
| `maxConnections` | 10 | Max simultaneous TCP connections (exposed via getter, for future pooling) |
| `maxIdleTimeSeconds` | 60 | Close idle connections after this time (exposed via getter) |

**WHY NO `@Slf4j`?** This class uses manual `Logger` creation instead of Lombok's `@Slf4j`. It's a stylistic choice — both produce identical results. The routing service uses manual loggers in config and controller classes.

```java
    /**
     * Creates the Netty EventLoopGroup used for bank TCP connections.
     * Thread count is configurable (default: 4 threads).
     */
    @Bean(destroyMethod = "shutdownGracefully")
    public EventLoopGroup workerGroup() {
        log.info("Creating Netty EventLoopGroup with {} worker threads", workerThreads);
        return new NioEventLoopGroup(workerThreads);
    }
```

**THIS IS THE MOST IMPORTANT BEAN IN THE ENTIRE ROUTING SERVICE.**

**`new NioEventLoopGroup(workerThreads)`** — creates 4 event loop threads. These 4 threads handle ALL TCP I/O for ALL bank connections. They poll channels for readiness (non-blocking I/O) instead of blocking one thread per connection.

**`@Bean(destroyMethod = "shutdownGracefully")`** — 🆕 **NEW SPRING PATTERN.**

| Without `destroyMethod` | With `destroyMethod = "shutdownGracefully"` |
|---|---|
| Application shutdown → EventLoopGroup threads keep running | Application shutdown → Netty finishes pending I/O → closes threads cleanly |
| JVM exits with orphaned threads | Clean shutdown, no data corruption |

**`shutdownGracefully()`** — Netty method that:
1. Stops accepting new tasks
2. Waits for in-progress I/O operations to complete
3. Closes all channels
4. Releases thread resources

**WHY A SPRING BEAN?** The `EventLoopGroup` is shared by ALL bank connections. By making it a Spring bean:
- BankNettyClient gets it via constructor injection
- Spring manages its lifecycle (creation on startup, shutdown on exit)
- One pool serves all connections (not one pool per connection)

```java
    public int getMaxConnections() {
        return maxConnections;
    }

    public int getMaxIdleTimeSeconds() {
        return maxIdleTimeSeconds;
    }
}
```

**SIMPLE GETTERS** — expose config values for future connection pooling logic. Currently not used by BankNettyClient (it creates connections on demand), but available for enhancement.

---

## 5. Step-by-Step: BankNettyClient.java

**File:** `src/main/java/com/payflow/routing/netty/BankNettyClient.java`

This is the **TCP client** that sends ISO 8583 messages to banks and receives responses asynchronously.

### Full Source Code

```java
package com.payflow.routing.netty;

import com.payflow.routing.iso8583.Iso8583Message;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
```

**KEY IMPORTS:**

| Import | What It Is |
|---|---|
| `Bootstrap` | Netty client configuration builder (like Spring's `WebClient.builder()` but for TCP) |
| `Channel` | A TCP connection |
| `ChannelFuture` | Async result of a channel operation (connect, write) |
| `ChannelOption` | TCP socket options (keepalive, nodelay, timeout) |
| `EventLoopGroup` | Thread pool (injected from NettyConfig) |
| `NioSocketChannel` | TCP channel using Java NIO (non-blocking) |
| `CompletableFuture` | Java's async promise — bridges Netty's async model to our sync caller |

```java
/**
 * Netty-based TCP client for communicating with the bank simulator.
 * Sends ISO 8583 messages and receives responses asynchronously via CompletableFuture.
 */
@Component
public class BankNettyClient {

    private static final Logger log = LoggerFactory.getLogger(BankNettyClient.class);

    private final EventLoopGroup workerGroup;
    private final String bankHost;
    private final int bankPort;
    private final int connectTimeoutMs;
    private final int responseTimeoutSeconds;
```

**5 DEPENDENCIES — all injected via constructor:**

| Dependency | Source | Purpose |
|---|---|---|
| `workerGroup` | `NettyConfig.workerGroup()` bean | Shared Netty thread pool |
| `bankHost` | `@Value("${bank.simulator.host:localhost}")` | Bank server address |
| `bankPort` | `@Value("${bank.simulator.port:9090}")` | Bank TCP port |
| `connectTimeoutMs` | `@Value("${bank.simulator.connect-timeout-ms:5000}")` | TCP connect timeout |
| `responseTimeoutSeconds` | `@Value("${bank.simulator.response-timeout-seconds:30}")` | Response wait timeout |

```java
    public BankNettyClient(
            EventLoopGroup workerGroup,
            @Value("${bank.simulator.host:localhost}") String bankHost,
            @Value("${bank.simulator.port:9090}") int bankPort,
            @Value("${bank.simulator.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${bank.simulator.response-timeout-seconds:30}") int responseTimeoutSeconds) {
        this.workerGroup = workerGroup;
        this.bankHost = bankHost;
        this.bankPort = bankPort;
        this.connectTimeoutMs = connectTimeoutMs;
        this.responseTimeoutSeconds = responseTimeoutSeconds;
    }
```

**CONSTRUCTOR INJECTION with `@Value`** — Spring injects the `EventLoopGroup` bean AND the YAML property values. No `@Autowired` needed (single constructor = auto-wired).

### sendMessage — The Core Async Method

```java
    /**
     * Sends an ISO 8583 message to the bank and returns the response asynchronously.
     *
     * @param request ISO 8583 request message
     * @return CompletableFuture containing the bank's response
     */
    public CompletableFuture<Iso8583Message> sendMessage(Iso8583Message request) {
        CompletableFuture<Iso8583Message> responseFuture = new CompletableFuture<>();
```

**`CompletableFuture<Iso8583Message>`** — a promise that will eventually contain the bank's response. The caller can:
- `.join()` to block and wait (what RoutingController does)
- `.thenApply()` to chain async processing
- `.exceptionally()` to handle errors

```java
        BankChannelInitializer initializer = new BankChannelInitializer(responseTimeoutSeconds);
```

**Creates a NEW pipeline initializer** for each request. The initializer sets up the message processing chain (encoder, decoder, response handler). See Part 9e for details.

```java
        Bootstrap bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(initializer);
```

**BOOTSTRAP — Netty's Builder Pattern for client configuration:**

| Setting | Value | What It Does |
|---|---|---|
| `.group(workerGroup)` | The shared EventLoopGroup | "Use these threads for I/O" |
| `.channel(NioSocketChannel.class)` | NIO socket | "Use non-blocking TCP" |
| `CONNECT_TIMEOUT_MILLIS` | 5000 | "Fail if can't connect in 5 seconds" |
| `SO_KEEPALIVE` | true | "Send TCP keep-alive probes to detect dead connections" |
| `TCP_NODELAY` | true | "Disable Nagle's algorithm — send data immediately" |
| `.handler(initializer)` | Pipeline setup | "Use this pipeline for encoding/decoding" |

**`TCP_NODELAY` — WHY?**
```
WITHOUT TCP_NODELAY (Nagle's algorithm ON):
  Small messages are buffered and sent together (saves bandwidth)
  But: adds latency (waits for buffer to fill)
  
WITH TCP_NODELAY (Nagle's algorithm OFF):
  Every message is sent IMMEDIATELY (no buffering)
  For payments: latency matters more than bandwidth savings
  We want: card swipe → immediate response (not "wait 200ms to buffer")
```

```java
        log.debug("Connecting to bank at {}:{}", bankHost, bankPort);

        ChannelFuture connectFuture = bootstrap.connect(bankHost, bankPort);
```

**`bootstrap.connect()` is NON-BLOCKING.** It returns immediately with a `ChannelFuture`. The actual TCP connection happens in the background on a Netty event loop thread.

```java
        connectFuture.addListener(future -> {
            if (future.isSuccess()) {
                Channel channel = connectFuture.channel();
                BankResponseHandler handler = initializer.getResponseHandler();
                handler.setResponseFuture(responseFuture);
```

**CONNECTION SUCCESS CALLBACK:** When TCP connection is established:
1. Get the `Channel` (the live TCP connection)
2. Get the `BankResponseHandler` from the pipeline
3. Give the handler our `CompletableFuture` — it will complete it when the bank responds

**WHY SET THE FUTURE ON THE HANDLER?** The handler is the last link in the Netty pipeline. When the bank's response bytes are decoded into an `Iso8583Message`, the handler needs to know WHERE to deliver it. The `CompletableFuture` is that delivery mechanism.

```java
                log.debug("Connected to bank, sending message: MTI={}", request.getMti());
                channel.writeAndFlush(request).addListener(writeFuture -> {
                    if (!writeFuture.isSuccess()) {
                        log.error("Failed to write message to bank: {}",
                                writeFuture.cause().getMessage());
                        responseFuture.completeExceptionally(writeFuture.cause());
                        channel.close();
                    }
                });
```

**SEND THE MESSAGE:**
- `channel.writeAndFlush(request)` — sends the `Iso8583Message` through the pipeline
- The pipeline's `Iso8583Encoder` converts it to binary bytes
- The pipeline's `LengthFieldPrepender` adds the 4-byte length header
- The bytes go out over TCP to the bank

**`addListener` on write** — if the write fails (broken connection, encoding error), complete the future with an exception and close the channel.

```java
                // Close channel when response is received
                responseFuture.whenComplete((response, ex) -> channel.close());
```

**CLEANUP:** When the response arrives (or an error occurs), close the TCP connection. This is a **connect-per-request** pattern — each transaction gets a fresh connection.

**WHY NOT KEEP THE CONNECTION OPEN?** In a production system, you'd use connection pooling. For this implementation, connect-per-request is simpler and avoids stale connection issues.

```java
            } else {
                log.error("Failed to connect to bank at {}:{}: {}",
                        bankHost, bankPort, future.cause().getMessage());
                responseFuture.completeExceptionally(future.cause());
            }
        });
```

**CONNECTION FAILURE:** If TCP connection fails (bank is down, wrong port, network error), complete the future with the connection error.

### Timeout Protection

```java
        // Apply timeout
        return responseFuture.orTimeout(responseTimeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException) {
                        log.error("Bank response timeout after {} seconds", responseTimeoutSeconds);
                    }
                    throw new RuntimeException("Bank communication failed: " + ex.getMessage(), ex);
                });
    }
```

**`responseFuture.orTimeout(30, SECONDS)`** — 🆕 **Java 9+ API**

If the bank doesn't respond within 30 seconds, the future is automatically completed with a `TimeoutException`. This prevents threads from waiting forever.

**`.exceptionally(ex -> { ... throw ... })`** — converts the `TimeoutException` into a `RuntimeException` that the caller (RoutingController) can handle.

**THE TIMEOUT FLOW:**
```
t=0.000s  Send ISO 8583 to bank → CompletableFuture created
t=0.000s  → orTimeout(30s) schedules a timeout check

SCENARIO A: Bank responds in 2 seconds
t=2.000s  Bank response arrives → handler completes Future → channel closes
t=30.00s  Timeout fires → Future already complete → no-op

SCENARIO B: Bank doesn't respond
t=30.00s  Timeout fires → Future completed with TimeoutException
          → .exceptionally() wraps it in RuntimeException
          → RoutingController catches it → returns error to Payment Service
```

### Synchronous Wrapper

```java
    /**
     * Sends a message and blocks until response is received or timeout expires.
     *
     * @param request ISO 8583 request message
     * @return Bank response message
     * @throws RuntimeException if communication fails or times out
     */
    public Iso8583Message sendMessageSync(Iso8583Message request) {
        try {
            return sendMessage(request).get(responseTimeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Synchronous bank communication failed: " + e.getMessage(), e);
        }
    }
```

**SYNC WRAPPER:** For callers that don't want to deal with `CompletableFuture`. `.get()` blocks the calling thread until the response arrives.

**RoutingController uses the async `.join()` instead** — both block, but `join()` doesn't throw checked exceptions.

### Shutdown

```java
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down bank Netty client");
        if (workerGroup != null && !workerGroup.isShutdown()) {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }
}
```

**`@PreDestroy`** — called by Spring when the application is shutting down.

**`shutdownGracefully(0, 5, SECONDS)`:**
- `0` = quiet period (wait 0 seconds after last task)
- `5` = max timeout (force shutdown after 5 seconds)

**NOTE:** `NettyConfig` also has `destroyMethod = "shutdownGracefully"` on the bean. Both ensure cleanup — belt and suspenders.

---

## 6. The Connection Lifecycle

```
┌─────────────────────────────────────────────────────────────────────────┐
│                 CONNECTION LIFECYCLE (per transaction)                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  1. RoutingController calls bankNettyClient.sendMessage(isoRequest)     │
│                                                                         │
│  2. CREATE CompletableFuture                                            │
│     responseFuture = new CompletableFuture<>()                         │
│                                                                         │
│  3. CONFIGURE Bootstrap                                                 │
│     group(workerGroup) + NioSocketChannel + options + handler           │
│                                                                         │
│  4. CONNECT (async)                                                     │
│     bootstrap.connect("localhost", 9090)                               │
│     → TCP 3-way handshake: SYN → SYN-ACK → ACK                       │
│                                                                         │
│  5. CONNECTION ESTABLISHED                                              │
│     → Set responseFuture on BankResponseHandler                        │
│     → channel.writeAndFlush(request)                                   │
│       → Iso8583Encoder encodes message to bytes                        │
│       → LengthFieldPrepender adds 4-byte length header                 │
│       → Bytes sent over TCP to bank                                    │
│                                                                         │
│  6. WAIT FOR RESPONSE                                                   │
│     → Bank processes the authorization                                  │
│     → Bank sends ISO 8583 response bytes back                          │
│                                                                         │
│  7. RESPONSE ARRIVES                                                    │
│     → LengthFieldBasedFrameDecoder strips length header                │
│     → Iso8583Decoder converts bytes to Iso8583Message                  │
│     → BankResponseHandler receives Iso8583Message                      │
│     → handler completes the CompletableFuture                          │
│                                                                         │
│  8. CLEANUP                                                             │
│     → responseFuture.whenComplete → channel.close()                    │
│     → TCP connection closed (FIN → FIN-ACK)                            │
│                                                                         │
│  9. RESULT                                                              │
│     → RoutingController gets Iso8583Message from .join()               │
│     → Extracts response code and auth code                             │
│     → Returns RoutingResponse to Payment Service                       │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Async → Sync Bridge: CompletableFuture

```
THE PROBLEM:
  Netty is fully async (non-blocking, callback-based)
  RoutingController needs a synchronous response (request → process → response)

THE BRIDGE: CompletableFuture
  1. BankNettyClient creates: CompletableFuture<Iso8583Message> future = new CompletableFuture<>();
  2. Gives it to BankResponseHandler: handler.setResponseFuture(future)
  3. Returns it to RoutingController: return future.orTimeout(30, SECONDS)
  4. RoutingController blocks: Iso8583Message response = future.join()

TIMELINE:
  t=0.0s  Controller calls sendMessage() → gets CompletableFuture back
  t=0.0s  Controller calls future.join() → BLOCKS (waiting)
          
          Meanwhile, on Netty's event loop thread:
          t=0.1s  TCP connection established
          t=0.2s  ISO 8583 bytes sent to bank
          t=1.5s  Bank response bytes arrive
          t=1.5s  Decoder → Handler → future.complete(response)
          
  t=1.5s  Controller's .join() RETURNS with the response
          → Controller continues processing

CompletableFuture bridges the gap:
  Netty thread completes the future (async)
  Controller thread waits on the future (sync)
  Both threads never block each other
```

---

## 8. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **Netty Event Loop** | Few threads handle thousands of connections via non-blocking I/O polling |
| 2 | **NioEventLoopGroup** | Netty's thread pool — 4 threads for all TCP I/O |
| 3 | **@Bean(destroyMethod)** | Spring calls `shutdownGracefully()` on shutdown — clean resource release |
| 4 | **Bootstrap** | Netty's builder for configuring TCP clients (group, channel, options, handler) |
| 5 | **NioSocketChannel** | TCP channel using Java NIO (non-blocking I/O) |
| 6 | **ChannelOption.TCP_NODELAY** | Disable Nagle's algorithm — send immediately, don't buffer for latency |
| 7 | **ChannelOption.SO_KEEPALIVE** | TCP keep-alive probes — detect dead connections |
| 8 | **ChannelOption.CONNECT_TIMEOUT_MILLIS** | Fail fast if TCP handshake takes too long |
| 9 | **bootstrap.connect() is non-blocking** | Returns ChannelFuture immediately — connection happens in background |
| 10 | **ChannelFuture.addListener()** | Callback when async operation completes (connect, write) |
| 11 | **channel.writeAndFlush()** | Sends data through the pipeline (encoder → prepender → TCP) |
| 12 | **CompletableFuture bridge** | Links async Netty (callbacks) to sync controller (.join() blocks) |
| 13 | **`.orTimeout(30, SECONDS)`** | Java 9+ — auto-completes Future with TimeoutException after N seconds |
| 14 | **`.exceptionally()`** | Transform exceptions in CompletableFuture chain |
| 15 | **Connect-per-request** | Each transaction opens a fresh TCP connection (simple, no stale connections) |
| 16 | **`responseFuture.whenComplete → channel.close()`** | Cleanup: close TCP connection when response received or error occurs |
| 17 | **@PreDestroy** | Spring lifecycle callback — clean up Netty resources on shutdown |
| 18 | **Constructor injection with @Value** | Spring injects both beans (EventLoopGroup) and properties (host, port) |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part09-routing-service-overview.md) | Routing Service Overview |
| [Part 9a](./phase4-part09a-routing-project-setup.md) | Project Setup |
| [Part 9b](./phase4-part09b-iso8583-foundation.md) | ISO 8583 Foundation |
| [Part 9c](./phase4-part09c-iso8583-messages.md) | ISO 8583 Messages + Tests |
| **Part 9d** | **Netty Config + Client** (You are here) |
| [Part 9e](./phase4-part09e-netty-pipeline.md) | Netty Pipeline |
| [Part 9f](./phase4-part09f-fraud-rule-engine.md) | Fraud Rule Engine |
| [Part 9g](./phase4-part09g-fraud-ml-service.md) | Fraud ML + Service + Tests |
| [Part 9h](./phase4-part09h-smart-routing.md) | Smart Routing + Tests |
| [Part 9i](./phase4-part09i-config-classes.md) | Config Classes (DynamoDB, Resilience4j) |
| [Part 9j](./phase4-part09j-controller-docker.md) | Controller + DTOs + Docker + curl |
| [Part 9k](./phase4-part09k-connections-flows.md) | How Everything Connects |

---

*Next: [Part 9e — Netty Pipeline (Initializer, Encoder, Decoder, Handler)](./phase4-part09e-netty-pipeline.md) →*
