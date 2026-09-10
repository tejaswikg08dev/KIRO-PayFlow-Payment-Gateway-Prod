# 🏗️ Phase 4 Part 10b: Bank Simulator — Netty TCP Server

> **"The boss accepts the connection. The workers handle the data. Two thread pools, one server — that's how banks handle thousands of simultaneous card swipes."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 10b — Netty TCP Server |
| **What You Build** | BankSimulatorServer.java, BankChannelInitializer.java |
| **Previous** | [Part 10a — Project Setup](./phase4-part10a-bank-project-setup.md) |
| **Next** | [Part 10c — Request Handler + Decision Logic](./phase4-part10c-bank-handler-logic.md) |

---

## 📖 Table of Contents

1. [Server vs Client — The Key Difference](#1-server-vs-client--the-key-difference)
2. [Folder Structure After This Part](#2-folder-structure-after-this-part)
3. [Step-by-Step: BankSimulatorServer.java](#3-step-by-step-banksimulatorserverjava)
4. [Step-by-Step: BankChannelInitializer.java](#4-step-by-step-bankchannelinitializerjava)
5. [The Complete Server Pipeline Diagram](#5-the-complete-server-pipeline-diagram)
6. [What You Learned](#6-what-you-learned)

---

## 1. Server vs Client — The Key Difference

In Part 9d, you built a Netty TCP **client** (routing service connects TO the bank). Now you build the **server** (bank ACCEPTS connections FROM routing service).

```
CLIENT (Part 9d — routing service):              SERVER (This part — bank simulator):
  Bootstrap bootstrap = new Bootstrap();            ServerBootstrap bootstrap = new ServerBootstrap();
  bootstrap.group(workerGroup)                      bootstrap.group(bossGroup, workerGroup)
           .channel(NioSocketChannel.class)                  .channel(NioServerSocketChannel.class)
           .handler(initializer);                            .childHandler(initializer);
  bootstrap.connect(host, port);                    bootstrap.bind(port);
```

**THE BIG DIFFERENCES:**

| | Client (Bootstrap) | Server (ServerBootstrap) |
|---|---|---|
| EventLoopGroups | **1** (worker) | **2** (boss + worker) |
| Channel type | `NioSocketChannel` | `NioServerSocketChannel` |
| Handler method | `.handler()` | `.childHandler()` |
| Action | `.connect(host, port)` | `.bind(port)` |
| Who initiates? | Client reaches out | Server waits for connections |

**WHY 2 GROUPS ON THE SERVER?**
```
bossGroup (1 thread):
  Job: Accept incoming TCP connections (SYN → SYN-ACK → ACK)
  Analogy: The receptionist who answers the phone

workerGroup (default threads = CPU cores):
  Job: Handle actual data (read requests, write responses)
  Analogy: The bank clerks who process transactions

WHY SEPARATE?
  If the receptionist also processed transactions, new callers
  would get a busy signal while she's working on a transaction.
  By separating: receptionist always available to accept new connections.
```

---

## 2. Folder Structure After This Part

```
backend/bank-simulator/src/main/java/com/payflow/bank/
├── BankSimulatorApplication.java    ← from 10a
├── config/
│   └── SimulatorConfig.java         ← from 10a
└── server/                          ← YOU CREATE THIS FOLDER
    ├── BankSimulatorServer.java     ← YOU CREATE THIS
    └── BankChannelInitializer.java  ← YOU CREATE THIS
```

---

## 3. Step-by-Step: BankSimulatorServer.java

**File:** `src/main/java/com/payflow/bank/server/BankSimulatorServer.java`

### Full Source Code

```java
package com.payflow.bank.server;

import com.payflow.bank.config.SimulatorConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
```

**KEY IMPORTS — Server-specific Netty classes:**

| Import | Role |
|---|---|
| `ServerBootstrap` | Server builder (vs `Bootstrap` for client) |
| `NioServerSocketChannel` | Server socket channel (vs `NioSocketChannel` for client) |
| `ChannelOption` | TCP socket options (SO_BACKLOG, SO_KEEPALIVE, TCP_NODELAY) |

```java
/**
 * Netty-based TCP server that simulates a bank/acquirer ISO 8583 interface.
 * Listens on a configurable port (default 9090) for incoming transaction requests.
 */
@Slf4j
@Component
public class BankSimulatorServer {

    private final SimulatorConfig config;
    private final BankChannelInitializer channelInitializer;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public BankSimulatorServer(SimulatorConfig config, BankChannelInitializer channelInitializer) {
        this.config = config;
        this.channelInitializer = channelInitializer;
    }
```

**2 INJECTED DEPENDENCIES:**

| Dependency | Purpose |
|---|---|
| `SimulatorConfig` | Get `tcpPort` (which port to listen on) |
| `BankChannelInitializer` | Pipeline setup for each accepted connection |

**`bossGroup` and `workerGroup` are NOT injected** — they're created in `start()`. This is different from routing service where `EventLoopGroup` was a Spring bean from `NettyConfig`. Here the server manages its own lifecycle.

### start() — The Server Startup

```java
    /**
     * Start the Netty TCP server.
     */
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
```

**`NioEventLoopGroup(1)`** — boss group with exactly 1 thread. One thread is enough because we only listen on 1 port. The boss thread's ONLY job is accepting new TCP connections.

**`NioEventLoopGroup()`** — worker group with **default** thread count. Default = number of CPU cores × 2. On a 4-core machine = 8 worker threads. These threads handle ALL the actual request processing.

**COMPARISON TO ROUTING SERVICE'S CLIENT:**
```
Routing (client):  1 EventLoopGroup  → new NioEventLoopGroup(4)  → 4 threads
Bank sim (server): 2 EventLoopGroups → boss(1) + worker(default) → 1 + 8 = 9 threads
```

```java
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(channelInitializer)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true);
```

**`ServerBootstrap` CONFIGURATION — line by line:**

| Method | Value | What It Does |
|---|---|---|
| `.group(bossGroup, workerGroup)` | 2 groups | Boss accepts connections, workers handle data |
| `.channel(NioServerSocketChannel.class)` | Server socket | Listens for incoming connections (not initiates them) |
| `.childHandler(channelInitializer)` | Pipeline | Applied to EACH accepted connection (not the server socket itself) |
| `.option(SO_BACKLOG, 128)` | 128 | Queue up to 128 pending connections while all workers are busy |
| `.childOption(SO_KEEPALIVE, true)` | true | Send TCP keep-alive probes to detect dead routing-service connections |
| `.childOption(TCP_NODELAY, true)` | true | Send responses immediately (don't buffer with Nagle's algorithm) |

**`.option()` vs `.childOption()`:**
- `.option()` → applies to the **server socket** (the listening socket)
- `.childOption()` → applies to **each accepted connection** (the data sockets)

**`SO_BACKLOG = 128`** — the connection queue:
```
If all worker threads are busy processing requests:
  New connection attempt → queued in backlog (up to 128)
  If backlog is full → connection REFUSED (routing service gets error)
  
128 is generous — we rarely have more than 4-10 simultaneous connections
```

**`.childHandler()` NOT `.handler()`:**
- Client uses `.handler()` — applies to the one outgoing connection
- Server uses `.childHandler()` — applies to EVERY incoming accepted connection

```java
            int port = config.getTcpPort();
            ChannelFuture future = bootstrap.bind(port).sync();
            log.info("Bank Simulator TCP server started on port {}", port);
            log.info("Success rate: {}%, Latency: {}ms-{}ms",
                    config.getSuccessRatePercent(),
                    config.getMinLatencyMs(),
                    config.getMaxLatencyMs());

            future.channel().closeFuture().sync();
```

**`bootstrap.bind(port).sync()`** — bind to TCP port 9090 and BLOCK until binding succeeds.

**`future.channel().closeFuture().sync()`** — **THIS LINE BLOCKS FOREVER.** It waits until the server channel is closed (which only happens on shutdown). This is why `BankSimulatorApplication` runs `start()` on a separate thread.

**THE LOG OUTPUT when server starts:**
```
Bank Simulator TCP server started on port 9090
Success rate: 85%, Latency: 50ms-500ms
```

```java
        } finally {
            shutdown();
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Bank Simulator TCP server...");
        if (bossGroup != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
    }
}
```

**`finally` + `@PreDestroy` — double cleanup:**
- `finally` → if `start()` throws (port already in use), clean up
- `@PreDestroy` → when Spring shuts down, clean up
- Both call `shutdownGracefully()` — finishes pending operations, then releases threads

**NULL CHECKS** — `bossGroup != null` because `@PreDestroy` might run before `start()` was called (if Spring shuts down early).

---

## 4. Step-by-Step: BankChannelInitializer.java

**File:** `src/main/java/com/payflow/bank/server/BankChannelInitializer.java`

The pipeline configuration — what happens to each TCP connection that's accepted.

### Full Source Code

```java
package com.payflow.bank.server;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
```

**🆕 NEW IMPORTS vs routing service pipeline:**

| Import | In Routing? | In Bank Sim? | Purpose |
|---|---|---|---|
| `StringDecoder` | ❌ | ✅ | Converts ByteBuf → String (routing used custom Iso8583Decoder) |
| `StringEncoder` | ❌ | ✅ | Converts String → ByteBuf (routing used custom Iso8583Encoder) |
| `IdleStateHandler` | ❌ | ✅ | Closes idle connections after 60 seconds |
| `ReadTimeoutHandler` | ✅ | ❌ | Not needed on server side (client handles its own timeouts) |

```java
/**
 * Channel pipeline configuration for the bank simulator TCP server.
 * Sets up frame decoding, string encoding, and the ISO 8583 request handler.
 */
@Component
public class BankChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final Iso8583RequestHandler requestHandler;

    public BankChannelInitializer(Iso8583RequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }
```

**`@Component`** — Spring-managed bean. The `Iso8583RequestHandler` is injected via constructor.

**ONE SHARED HANDLER** — the same `requestHandler` instance is used for ALL connections. This is safe because the handler is `@ChannelHandler.Sharable` (see Part 10c).

### initChannel — The 6-Handler Pipeline

```java
    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // Idle state handler: close connections idle for 60 seconds
        pipeline.addLast("idleStateHandler",
                new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));
```

**HANDLER 1: `IdleStateHandler`** — if a connection has no READ activity for 60 seconds, fire an idle event. The handler closes stale connections that routing service forgot to close.

**`(60, 0, 0)` = (readIdle, writeIdle, allIdle):**
- 60 = close if no data READ for 60 seconds
- 0 = don't check write idle
- 0 = don't check all idle

```java
        // Frame decoder: 2-byte length header, max frame 8KB
        pipeline.addLast("frameDecoder",
                new LengthFieldBasedFrameDecoder(8192, 0, 2, 0, 2));

        // Frame encoder: prepend 2-byte length header
        pipeline.addLast("frameEncoder",
                new LengthFieldPrepender(2));
```

**HANDLER 2-3: Frame Codec (2-byte length prefix)**

```
INBOUND (from routing service):
  [0x00, 0x2F] [47 bytes of ISO 8583 data]
   ↑ 2-byte length = 47
  
  LengthFieldBasedFrameDecoder:
    Read first 2 bytes → length = 47
    Wait until 47 bytes arrive
    Strip 2-byte prefix → pass 47 bytes to next handler

OUTBOUND (to routing service):
  "0110411111111111111..." (response string)
  
  LengthFieldPrepender:
    Calculate length → prepend 2 bytes
    [0x00, 0x35] + response bytes → TCP
```

**🆕 2-BYTE LENGTH PREFIX (not 4-byte like routing service's internal pipeline)**

| Service | Length Prefix | Max Frame |
|---|---|---|
| Routing service (internal pipeline) | **4 bytes** | 8192 |
| Bank simulator (this server) | **2 bytes** | 8192 |

The routing service's `BankNettyClient` and this simulator must use the **same framing protocol**. Both sides use 2-byte length prefix.

```java
        // String codec
        pipeline.addLast("stringDecoder", new StringDecoder(StandardCharsets.UTF_8));
        pipeline.addLast("stringEncoder", new StringEncoder(StandardCharsets.UTF_8));
```

**HANDLER 4-5: String Codec**

**🆕 THIS IS DIFFERENT FROM ROUTING SERVICE.** Routing service used custom `Iso8583Encoder`/`Iso8583Decoder` that work with `Iso8583Message` objects and raw binary. The bank simulator uses simple `StringDecoder`/`StringEncoder` that convert between `ByteBuf` and `String`.

**WHY STRING AND NOT BINARY?**
```
Routing service (production-grade):
  Iso8583Message → Iso8583Encoder → binary bytes → TCP
  Complex: full bitmap encoding, field type handling, padding

Bank simulator (test tool):
  String → StringEncoder → UTF-8 bytes → TCP
  Simple: just convert string to bytes
  
The simulator doesn't need full ISO 8583 encoding/decoding.
It receives a string, parses fixed positions, generates a string response.
Good enough for testing.
```

**THIS MEANS `Iso8583RequestHandler` receives `String` messages** (not `ByteBuf` or `Iso8583Message`):
```java
public class Iso8583RequestHandler extends SimpleChannelInboundHandler<String> {
    // channelRead0 receives String, not byte[]
}
```

```java
        // Business logic handler
        pipeline.addLast("requestHandler", requestHandler);
    }
}
```

**HANDLER 6: `Iso8583RequestHandler`** — the business logic. Receives the decoded String, parses fields, generates response, writes it back.

**NOTE:** This is a **shared instance** (same handler for all connections). See `@ChannelHandler.Sharable` in Part 10c.

---

## 5. The Complete Server Pipeline Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│               BANK SIMULATOR — NETTY SERVER PIPELINE                    │
│               (configured in BankChannelInitializer)                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  INBOUND (receiving from routing service)                               │
│  ════════════════════════════════════                                   │
│                                                                         │
│  TCP bytes from routing service                                         │
│       │                                                                 │
│       ▼                                                                 │
│  ┌───────────────────────────┐                                         │
│  │ 1. IdleStateHandler       │  Close if no data for 60 seconds        │
│  └───────────┬───────────────┘                                         │
│              │                                                          │
│              ▼                                                          │
│  ┌───────────────────────────┐                                         │
│  │ 2. LengthFieldBased      │  Read 2-byte length prefix              │
│  │    FrameDecoder           │  Wait for complete message              │
│  │    (8192, 0, 2, 0, 2)    │  Strip prefix → pass data              │
│  └───────────┬───────────────┘                                         │
│              │                                                          │
│              ▼                                                          │
│  ┌───────────────────────────┐                                         │
│  │ 4. StringDecoder (UTF-8)  │  ByteBuf → Java String                 │
│  └───────────┬───────────────┘                                         │
│              │                                                          │
│              ▼                                                          │
│  ┌───────────────────────────┐                                         │
│  │ 6. Iso8583RequestHandler  │  Parse fields → decide → respond       │
│  │    (business logic)       │  ctx.writeAndFlush(responseString)      │
│  └───────────────────────────┘                                         │
│                                                                         │
│                                                                         │
│  OUTBOUND (sending to routing service)                                  │
│  ════════════════════════════════════                                   │
│                                                                         │
│  Response String from handler                                           │
│       │                                                                 │
│       ▼                                                                 │
│  ┌───────────────────────────┐                                         │
│  │ 5. StringEncoder (UTF-8)  │  Java String → ByteBuf                 │
│  └───────────┬───────────────┘                                         │
│              │                                                          │
│              ▼                                                          │
│  ┌───────────────────────────┐                                         │
│  │ 3. LengthFieldPrepender  │  Prepend 2-byte length header           │
│  │    (2)                    │  [0x00, 0x35] + data bytes             │
│  └───────────┬───────────────┘                                         │
│              │                                                          │
│              ▼                                                          │
│  TCP bytes sent to routing service                                      │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

COMPARISON WITH ROUTING SERVICE CLIENT PIPELINE (Part 9e):

Bank Simulator (SERVER):                Routing Service (CLIENT):
  IdleStateHandler (60s)                  LengthFieldBasedFrameDecoder (4-byte)
  LengthFieldBasedFrameDecoder (2-byte)   LengthFieldPrepender (4-byte)
  LengthFieldPrepender (2-byte)           ReadTimeoutHandler (30s)
  StringDecoder (UTF-8)                   Iso8583Decoder (binary → Iso8583Message)
  StringEncoder (UTF-8)                   Iso8583Encoder (Iso8583Message → binary)
  Iso8583RequestHandler (String)          BankResponseHandler (CompletableFuture)
```

---

## 6. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **`ServerBootstrap` vs `Bootstrap`** | Server uses `ServerBootstrap` with 2 EventLoopGroups; client uses `Bootstrap` with 1 |
| 2 | **Boss + Worker pattern** | Boss (1 thread) accepts connections; Workers (N threads) handle data |
| 3 | **`NioServerSocketChannel`** | Server socket that LISTENS for connections (vs `NioSocketChannel` that CONNECTS) |
| 4 | **`.childHandler()` vs `.handler()`** | `childHandler` applies to each accepted connection; `handler` applies to the server socket |
| 5 | **`.option()` vs `.childOption()`** | `option` for server socket; `childOption` for accepted connections |
| 6 | **`SO_BACKLOG = 128`** | Queue up to 128 pending connections when all workers are busy |
| 7 | **`closeFuture().sync()` blocks forever** | Server runs until shutdown — must be on a separate thread |
| 8 | **Double cleanup** | `finally` in start() + `@PreDestroy` — both call `shutdownGracefully()` |
| 9 | **`IdleStateHandler(60, 0, 0)`** | Close stale connections with no read activity for 60 seconds |
| 10 | **2-byte length prefix** | Both server and client must agree on framing protocol |
| 11 | **`StringDecoder`/`StringEncoder`** | Simplified codec — converts ByteBuf ↔ String (not binary Iso8583Message) |
| 12 | **Why String not binary** | Simulator is a test tool — simplified string parsing is good enough |
| 13 | **Shared handler instance** | Same `Iso8583RequestHandler` for all connections (requires `@Sharable`) |
| 14 | **`@Component` initializer** | Spring manages the initializer — handler is injected via constructor |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part10-bank-simulator-overview.md) | Bank Simulator Overview |
| [Part 10a](./phase4-part10a-bank-project-setup.md) | Project Setup |
| **Part 10b** | **Netty TCP Server** (You are here) |
| [Part 10c](./phase4-part10c-bank-handler-logic.md) | Request Handler + Decision Logic |
| [Part 10d](./phase4-part10d-bank-docker-testing.md) | Dockerfile + Testing + Connections |

---

*Next: [Part 10c — Request Handler + Decision Logic](./phase4-part10c-bank-handler-logic.md) →*
