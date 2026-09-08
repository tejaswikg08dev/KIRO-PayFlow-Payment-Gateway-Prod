# 🏗️ Phase 4 Part 9e: Routing Service — Netty Pipeline (Initializer, Encoder, Decoder, Handler)

> **"Data flows through the pipeline like an assembly line. Each handler does ONE job: frame it, encode it, decode it, deliver it. The order matters."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 9e — Netty Pipeline |
| **What You Build** | BankChannelInitializer.java, Iso8583Encoder.java, Iso8583Decoder.java, BankResponseHandler.java |
| **Previous** | [Part 9d — Netty Config + Client](./phase4-part09d-netty-config-client.md) |
| **Next** | [Part 9f — Fraud Rule Engine](./phase4-part09f-fraud-rule-engine.md) |

---

## 📖 Table of Contents

1. [What Is a Netty Pipeline?](#1-what-is-a-netty-pipeline)
2. [The Complete Pipeline Diagram](#2-the-complete-pipeline-diagram)
3. [Folder Structure After This Part](#3-folder-structure-after-this-part)
4. [Step-by-Step: BankChannelInitializer.java](#4-step-by-step-bankchannelinitializerjava)
5. [Step-by-Step: Iso8583Encoder.java](#5-step-by-step-iso8583encoderjava)
6. [Step-by-Step: Iso8583Decoder.java](#6-step-by-step-iso8583decoderjava)
7. [Step-by-Step: BankResponseHandler.java](#7-step-by-step-bankresponsehandlerjava)
8. [Data Flow — Outbound and Inbound](#8-data-flow--outbound-and-inbound)
9. [What You Learned](#9-what-you-learned)

---

## 1. What Is a Netty Pipeline?

A **pipeline** is a chain of handlers that process data as it flows through a channel (TCP connection). Each handler does ONE job, then passes data to the next handler.

```
ANALOGY: A car assembly line

Station 1: Weld the frame       → Netty: Frame the message (add length header)
Station 2: Paint the body        → Netty: Encode the message (Java → binary)
Station 3: Install the engine    → Netty: Send over TCP
Station 4: Quality inspection    → Netty: Receive from TCP
Station 5: Add interior          → Netty: Decode the message (binary → Java)
Station 6: Final delivery        → Netty: Deliver to application (complete Future)
```

**TWO DIRECTIONS:**
- **Outbound (sending):** Application → Encoder → Frame Prepender → TCP socket
- **Inbound (receiving):** TCP socket → Frame Decoder → Decoder → Response Handler → Application

---

## 2. The Complete Pipeline Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      NETTY CHANNEL PIPELINE                             │
│                  (configured in BankChannelInitializer)                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  OUTBOUND (sending to bank)         INBOUND (receiving from bank)       │
│  ══════════════════════             ════════════════════════════        │
│                                                                         │
│  Iso8583Message object              Raw TCP bytes from bank             │
│         │                                    │                          │
│         ▼                                    ▼                          │
│  ┌──────────────────┐              ┌─────────────────────────────┐     │
│  │ Iso8583Encoder   │              │ LengthFieldBasedFrameDecoder │     │
│  │ Iso8583Message → │              │ Strip 4-byte length prefix   │     │
│  │ raw bytes        │              │ Wait for complete message    │     │
│  └────────┬─────────┘              └──────────────┬──────────────┘     │
│           │                                       │                     │
│           ▼                                       ▼                     │
│  ┌──────────────────────┐              ┌───────────────────────┐       │
│  │ LengthFieldPrepender │              │ ReadTimeoutHandler    │       │
│  │ Add 4-byte length    │              │ Timeout if no data in │       │
│  │ header to outgoing   │              │ configured seconds    │       │
│  └────────┬─────────────┘              └──────────┬────────────┘       │
│           │                                       │                     │
│           ▼                                       ▼                     │
│  ┌──────────────────┐              ┌──────────────────────┐            │
│  │   TCP Socket     │              │  Iso8583Decoder      │            │
│  │ (to bank network)│              │  raw bytes →         │            │
│  └──────────────────┘              │  Iso8583Message      │            │
│                                    └──────────┬───────────┘            │
│                                               │                        │
│                                               ▼                        │
│                                    ┌──────────────────────────┐        │
│                                    │  BankResponseHandler     │        │
│                                    │  Completes the           │        │
│                                    │  CompletableFuture       │        │
│                                    └──────────────────────────┘        │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Folder Structure After This Part

```
backend/routing-service/src/main/java/com/payflow/routing/netty/
├── BankNettyClient.java              ← from 9d
├── BankChannelInitializer.java       ← YOU CREATE THIS
├── Iso8583Encoder.java               ← YOU CREATE THIS
├── Iso8583Decoder.java               ← YOU CREATE THIS
└── BankResponseHandler.java          ← YOU CREATE THIS
```

---

## 4. Step-by-Step: BankChannelInitializer.java

**File:** `src/main/java/com/payflow/routing/netty/BankChannelInitializer.java`

The initializer wires all handlers into the pipeline in the correct order.

### Full Source Code

```java
package com.payflow.routing.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
```

**KEY IMPORTS from Netty:**

| Import | Role in Pipeline |
|---|---|
| `ChannelInitializer<SocketChannel>` | Base class — Netty calls `initChannel()` for each new connection |
| `LengthFieldBasedFrameDecoder` | **INBOUND** — reads 4-byte length prefix, waits for complete message |
| `LengthFieldPrepender` | **OUTBOUND** — adds 4-byte length prefix before sending |
| `ReadTimeoutHandler` | **INBOUND** — fires timeout if no data received in N seconds |

```java
/**
 * Initializes the Netty channel pipeline for bank communication.
 * <p>
 * Pipeline:
 * 1. LengthFieldBasedFrameDecoder - Frames incoming messages by 4-byte length prefix
 * 2. LengthFieldPrepender - Prepends 4-byte length to outgoing messages
 * 3. ReadTimeoutHandler - Times out if no response within configured timeout
 * 4. Iso8583Decoder - Decodes binary frames to Iso8583Message
 * 5. Iso8583Encoder - Encodes Iso8583Message to binary frames
 * 6. BankResponseHandler - Completes the CompletableFuture with response
 */
public class BankChannelInitializer extends ChannelInitializer<SocketChannel> {

    private static final Logger log = LoggerFactory.getLogger(BankChannelInitializer.class);

    private static final int MAX_FRAME_LENGTH = 8192;
    private static final int LENGTH_FIELD_OFFSET = 0;
    private static final int LENGTH_FIELD_LENGTH = 4;
    private static final int LENGTH_ADJUSTMENT = 0;
    private static final int INITIAL_BYTES_TO_STRIP = 4;
```

**FRAME DECODER CONSTANTS — The TCP Framing Problem:**

```
PROBLEM: TCP is a STREAM protocol, not a MESSAGE protocol.

The bank sends two messages (80 bytes + 60 bytes):
  [80 bytes][60 bytes]

TCP might deliver them as:
  Scenario A: [80 bytes] then [60 bytes]  ← perfect, but NOT guaranteed
  Scenario B: [140 bytes] at once         ← both messages merged!
  Scenario C: [50 bytes] then [90 bytes]  ← first message split!

Without framing, you can't tell where one message ends and another begins.

SOLUTION: Length-prefixed framing
  Each message starts with a 4-byte length field:
  [0x00, 0x00, 0x00, 0x50][80 bytes of ISO 8583][0x00, 0x00, 0x00, 0x3C][60 bytes]
   ↑ length = 80                                   ↑ length = 60
  
  Now the decoder reads: "next 80 bytes = one message, next 60 bytes = another"
```

| Constant | Value | Meaning |
|---|---|---|
| `MAX_FRAME_LENGTH` | 8192 | Reject messages larger than 8KB (ISO 8583 messages are usually < 1KB) |
| `LENGTH_FIELD_OFFSET` | 0 | Length field starts at byte 0 (beginning of the frame) |
| `LENGTH_FIELD_LENGTH` | 4 | Length field is 4 bytes (big-endian integer) |
| `LENGTH_ADJUSTMENT` | 0 | No adjustment — length value = exact message size |
| `INITIAL_BYTES_TO_STRIP` | 4 | Remove the 4-byte length prefix before passing to next handler |

```java
    private final int readTimeoutSeconds;
    private final BankResponseHandler responseHandler;

    public BankChannelInitializer(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
        this.responseHandler = new BankResponseHandler();
    }
```

**Creates a `BankResponseHandler` per initializer.** Since BankNettyClient creates a new initializer per request, each request gets its own handler with its own CompletableFuture.

### initChannel — The Pipeline Assembly

```java
    @Override
    protected void initChannel(SocketChannel ch) {
        log.debug("Initializing bank channel pipeline");

        ChannelPipeline pipeline = ch.pipeline();

        // Frame decoders/encoders (4-byte length prefix, big-endian)
        pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(
                MAX_FRAME_LENGTH,
                LENGTH_FIELD_OFFSET,
                LENGTH_FIELD_LENGTH,
                LENGTH_ADJUSTMENT,
                INITIAL_BYTES_TO_STRIP
        ));
        pipeline.addLast("framePrepender", new LengthFieldPrepender(LENGTH_FIELD_LENGTH));
```

**HANDLER 1: `LengthFieldBasedFrameDecoder`** (INBOUND)
- Reads the first 4 bytes as the message length
- Waits until ALL bytes arrive (solves the TCP framing problem)
- Strips the 4-byte prefix before passing to the next handler
- Rejects frames > 8192 bytes (security: prevents memory exhaustion)

**HANDLER 2: `LengthFieldPrepender`** (OUTBOUND)
- Before sending, prepends a 4-byte big-endian length header
- Example: 80-byte message → `[0x00,0x00,0x00,0x50]` + 80 bytes = 84 bytes total

```java
        // Read timeout
        pipeline.addLast("readTimeout", new ReadTimeoutHandler(readTimeoutSeconds, TimeUnit.SECONDS));
```

**HANDLER 3: `ReadTimeoutHandler`** (INBOUND)
- If no data is received for `readTimeoutSeconds` (30), fires a `ReadTimeoutException`
- This is the **pipeline-level timeout** (in addition to CompletableFuture's `.orTimeout()`)
- Two layers of timeout protection: Netty pipeline AND Java Future

```java
        // ISO 8583 codec
        pipeline.addLast("iso8583Decoder", new Iso8583Decoder());
        pipeline.addLast("iso8583Encoder", new Iso8583Encoder());
```

**HANDLER 4: `Iso8583Decoder`** (INBOUND) — binary bytes → `Iso8583Message`

**HANDLER 5: `Iso8583Encoder`** (OUTBOUND) — `Iso8583Message` → binary bytes

```java
        // Response handler
        pipeline.addLast("responseHandler", responseHandler);
    }

    public BankResponseHandler getResponseHandler() {
        return responseHandler;
    }
}
```

**HANDLER 6: `BankResponseHandler`** (INBOUND) — delivers `Iso8583Message` to the `CompletableFuture`

**`getResponseHandler()`** — BankNettyClient calls this to access the handler and set the CompletableFuture on it.

**HANDLER ORDER MATTERS:**
```
INBOUND data flows TOP to BOTTOM:
  1. frameDecoder → 3. readTimeout → 4. iso8583Decoder → 6. responseHandler

OUTBOUND data flows BOTTOM to TOP:
  5. iso8583Encoder → 2. framePrepender → TCP
```

---

## 5. Step-by-Step: Iso8583Encoder.java

**File:** `src/main/java/com/payflow/routing/netty/Iso8583Encoder.java`

Converts `Iso8583Message` Java objects into binary bytes for the wire.

### Full Source Code

```java
package com.payflow.routing.netty;

import com.payflow.routing.iso8583.BitmapUtils;
import com.payflow.routing.iso8583.Iso8583Constants;
import com.payflow.routing.iso8583.Iso8583Field;
import com.payflow.routing.iso8583.Iso8583Message;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
```

```java
/**
 * Netty encoder that serializes Iso8583Message objects to binary bytes.
 * <p>
 * Wire format (big-endian):
 * [MTI - 4 bytes ASCII] [Bitmap - 8 bytes binary] [Data Fields...]
 */
public class Iso8583Encoder extends MessageToByteEncoder<Iso8583Message> {
```

**`MessageToByteEncoder<Iso8583Message>`** — Netty base class for OUTBOUND encoders:
- Generic type `<Iso8583Message>` = "I encode Iso8583Message objects"
- Override `encode()` to write bytes into the output `ByteBuf`
- Netty calls this automatically when `channel.writeAndFlush(message)` is invoked

```java
    private static final Logger log = LoggerFactory.getLogger(Iso8583Encoder.class);

    private static final Map<Integer, Iso8583Field> FIELD_DEFINITIONS = Iso8583Constants.getFieldDefinitions();

    @Override
    protected void encode(ChannelHandlerContext ctx, Iso8583Message msg, ByteBuf out) throws Exception {
        log.debug("Encoding ISO 8583 message: MTI={}", msg.getMti());

        // Write MTI (4 bytes ASCII)
        out.writeBytes(msg.getMti().getBytes(StandardCharsets.US_ASCII));

        // Write Bitmap (8 bytes binary)
        out.writeBytes(msg.getBitmap());

        // Write data fields in order
        for (int fieldNum = 2; fieldNum <= 64; fieldNum++) {
            if (BitmapUtils.isFieldPresent(msg.getBitmap(), fieldNum)) {
                String value = msg.getField(fieldNum);
                if (value != null) {
                    encodeField(out, fieldNum, value);
                }
            }
        }

        log.debug("Encoded message size: {} bytes", out.readableBytes());
    }
```

**THE ENCODING FLOW — 3 STEPS:**
1. Write MTI (always 4 bytes): `"0100"` → `[0x30, 0x31, 0x30, 0x30]`
2. Write bitmap (always 8 bytes): raw binary bits indicating which fields are present
3. For each present field (in order 2→64): encode based on field type

**`ByteBuf out`** — Netty's buffer. Unlike `ByteBuffer`, Netty's `ByteBuf` has separate read/write pointers and automatic resizing. `out.writeBytes()` appends data.

### encodeField — Type-Specific Encoding

```java
    /**
     * Encodes a single field to the output buffer.
     */
    private void encodeField(ByteBuf out, int fieldNumber, String value) {
        Iso8583Field fieldDef = FIELD_DEFINITIONS.get(fieldNumber);
        if (fieldDef == null) {
            // Unknown field, encode as LLVAR
            String lengthPrefix = String.format("%02d", value.length());
            out.writeBytes(lengthPrefix.getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
            return;
        }

        switch (fieldDef.type()) {
            case NUMERIC -> {
                // Right-justify with leading zeros
                String padded = padLeft(value, fieldDef.maxLength(), '0');
                out.writeBytes(padded.getBytes(StandardCharsets.US_ASCII));
            }
            case ALPHA -> {
                // Left-justify with trailing spaces
                String padded = padRight(value, fieldDef.maxLength(), ' ');
                out.writeBytes(padded.getBytes(StandardCharsets.US_ASCII));
            }
            case LLVAR -> {
                String lengthPrefix = String.format("%02d", value.length());
                out.writeBytes(lengthPrefix.getBytes(StandardCharsets.US_ASCII));
                out.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
            }
            case LLLVAR -> {
                String lengthPrefix = String.format("%03d", value.length());
                out.writeBytes(lengthPrefix.getBytes(StandardCharsets.US_ASCII));
                out.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
            }
        }
    }
```

**ENCODING RULES BY TYPE — with examples:**

```
NUMERIC (padLeft with '0'):
  Value: "50000", maxLength: 12
  padLeft("50000", 12, '0') → "000000050000"
  → Write 12 bytes: [0x30,0x30,...,0x30,0x30]

ALPHA (padRight with ' '):
  Value: "TERM001", maxLength: 8
  padRight("TERM001", 8, ' ') → "TERM001 "
  → Write 8 bytes

LLVAR (2-digit length prefix):
  Value: "4111111111111111" (16 chars)
  Length prefix: String.format("%02d", 16) → "16"
  → Write "16" + "4111111111111111" = 18 bytes total

LLLVAR (3-digit length prefix):
  Value: "AMAZON INDIA" (12 chars)
  Length prefix: String.format("%03d", 12) → "012"
  → Write "012" + "AMAZON INDIA" = 15 bytes total
```

**UNKNOWN FIELD FALLBACK:** If a field number isn't in the registry, encode it as LLVAR. This is defensive — prevents crashes from unexpected fields.

### Padding Helpers

```java
    private String padLeft(String value, int length, char padChar) {
        if (value.length() >= length) return value.substring(0, length);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length - value.length(); i++) {
            sb.append(padChar);
        }
        sb.append(value);
        return sb.toString();
    }

    private String padRight(String value, int length, char padChar) {
        if (value.length() >= length) return value.substring(0, length);
        StringBuilder sb = new StringBuilder(length);
        sb.append(value);
        for (int i = 0; i < length - value.length(); i++) {
            sb.append(padChar);
        }
        return sb.toString();
    }
}
```

**`padLeft`** — for NUMERIC: `"50000"` → `"000000050000"` (zeros on the left)
**`padRight`** — for ALPHA: `"TERM001"` → `"TERM001 "` (spaces on the right)

Both handle the edge case where value is already at or beyond max length: `value.substring(0, length)` truncates.

---

## 6. Step-by-Step: Iso8583Decoder.java

**File:** `src/main/java/com/payflow/routing/netty/Iso8583Decoder.java`

Converts binary bytes from the bank into `Iso8583Message` objects.

### Full Source Code

```java
package com.payflow.routing.netty;

import com.payflow.routing.iso8583.Iso8583Message;
import com.payflow.routing.iso8583.Iso8583MessageParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
```

```java
/**
 * Netty decoder that converts incoming binary bytes to Iso8583Message objects.
 * <p>
 * Expects the frame to already be delimited by LengthFieldBasedFrameDecoder
 * in the pipeline.
 */
public class Iso8583Decoder extends ByteToMessageDecoder {
```

**`ByteToMessageDecoder`** — Netty base class for INBOUND decoders:
- Called when bytes arrive from the network
- Override `decode()` to convert bytes into Java objects
- Add decoded objects to the `out` list — Netty passes them to the next inbound handler

**KEY ASSUMPTION:** The `LengthFieldBasedFrameDecoder` upstream has already solved the framing problem. By the time `decode()` is called, `in` contains exactly ONE complete ISO 8583 message (the 4-byte length prefix is stripped).

```java
    private static final Logger log = LoggerFactory.getLogger(Iso8583Decoder.class);

    private final Iso8583MessageParser parser;

    public Iso8583Decoder() {
        this.parser = new Iso8583MessageParser();
    }
```

**DELEGATES TO `Iso8583MessageParser`** — the parser we built in Part 9c. The decoder is a thin Netty wrapper around the parser.

```java
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < 12) {
            // Minimum: 4 (MTI) + 8 (bitmap) = 12 bytes
            return;
        }

        byte[] data = new byte[in.readableBytes()];
        in.readBytes(data);
```

**`in.readableBytes()`** — how many bytes are available. Thanks to the frame decoder, this is the complete message.

**`in.readBytes(data)`** — copies bytes from Netty's `ByteBuf` to a Java `byte[]` for the parser.

```java
        try {
            Iso8583Message message = parser.parse(data);
            out.add(message);
            log.debug("Decoded ISO 8583 message: MTI={}, fields={}",
                    message.getMti(), message.getFields().keySet());
        } catch (Iso8583MessageParser.Iso8583ParseException e) {
            log.error("Failed to decode ISO 8583 message: {}", e.getMessage());
            ctx.fireExceptionCaught(e);
        }
    }
```

**`out.add(message)`** — this is HOW Netty passes decoded objects to the next handler. The `BankResponseHandler` downstream receives the `Iso8583Message` in its `channelRead0()` method.

**`ctx.fireExceptionCaught(e)`** — propagates the parse error through the pipeline. The `BankResponseHandler.exceptionCaught()` will handle it.

```java
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error in ISO 8583 decoder: {}", cause.getMessage(), cause);
        ctx.close();
    }
}
```

**SAFETY NET:** If any unhandled exception reaches this decoder, close the connection. Better to disconnect than process corrupted data.

---

## 7. Step-by-Step: BankResponseHandler.java

**File:** `src/main/java/com/payflow/routing/netty/BankResponseHandler.java`

The final pipeline handler — delivers the decoded response to the waiting `CompletableFuture`.

### Full Source Code

```java
package com.payflow.routing.netty;

import com.payflow.routing.iso8583.Iso8583Message;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
```

```java
/**
 * Netty handler that receives ISO 8583 response messages from the bank
 * and completes the associated CompletableFuture.
 */
public class BankResponseHandler extends SimpleChannelInboundHandler<Iso8583Message> {
```

**`SimpleChannelInboundHandler<Iso8583Message>`** — Netty's convenience class:
- Generic `<Iso8583Message>` = "I handle Iso8583Message objects"
- Auto-releases the message after `channelRead0()` (memory management)
- Override `channelRead0()` for business logic

```java
    private static final Logger log = LoggerFactory.getLogger(BankResponseHandler.class);

    private CompletableFuture<Iso8583Message> responseFuture;

    public BankResponseHandler() {
    }

    /**
     * Sets the CompletableFuture that will be completed when a response is received.
     */
    public void setResponseFuture(CompletableFuture<Iso8583Message> responseFuture) {
        this.responseFuture = responseFuture;
    }

    public CompletableFuture<Iso8583Message> getResponseFuture() {
        return responseFuture;
    }
```

**THE BRIDGE:** BankNettyClient creates the `CompletableFuture` and gives it to this handler via `setResponseFuture()`. When the bank response arrives, `channelRead0()` completes the future — unblocking the waiting controller.

### channelRead0 — Response Arrives

```java
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Iso8583Message response) {
        log.debug("Received bank response: MTI={}, fields={}",
                response.getMti(), response.getFields().keySet());

        if (responseFuture != null && !responseFuture.isDone()) {
            responseFuture.complete(response);
        } else {
            log.warn("Received unexpected response (no pending future): MTI={}", response.getMti());
        }
    }
```

**THIS IS WHERE ASYNC MEETS SYNC:**
1. Bank sends binary response over TCP
2. `LengthFieldBasedFrameDecoder` frames it
3. `Iso8583Decoder` parses it into `Iso8583Message`
4. `BankResponseHandler.channelRead0()` receives the `Iso8583Message`
5. `responseFuture.complete(response)` — **unblocks** the controller's `.join()`

**`!responseFuture.isDone()`** — safety check. If the future was already completed (by timeout), don't try to complete it again.

**THE WARNING LOG:** If a response arrives after timeout, the future is already done. The response is discarded with a warning. This is normal for late bank responses.

### Error Handling

```java
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error in bank response handler: {}", cause.getMessage(), cause);
        if (responseFuture != null && !responseFuture.isDone()) {
            responseFuture.completeExceptionally(cause);
        }
        ctx.close();
    }
```

**ANY EXCEPTION** (decode error, I/O error, timeout) → complete the future with the exception → close the connection.

The caller (RoutingController) catches this as a `RuntimeException` and returns an error response to Payment Service.

```java
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.debug("Bank connection closed");
        if (responseFuture != null && !responseFuture.isDone()) {
            responseFuture.completeExceptionally(
                    new RuntimeException("Connection closed before response received"));
        }
    }
}
```

**`channelInactive()`** — called when the TCP connection closes. If the bank closes the connection BEFORE sending a response (crash, network issue), complete the future with an error.

**THREE WAYS the future gets completed:**

| Scenario | Method | Future Completion |
|---|---|---|
| ✅ Bank responds | `channelRead0()` | `future.complete(response)` |
| ❌ Exception in pipeline | `exceptionCaught()` | `future.completeExceptionally(cause)` |
| ❌ Connection drops | `channelInactive()` | `future.completeExceptionally(new RuntimeException(...))` |
| ❌ Timeout (from BankNettyClient) | `.orTimeout()` | `future.completeExceptionally(TimeoutException)` |

---

## 8. Data Flow — Outbound and Inbound

### Outbound: Sending Authorization Request

```
RoutingController calls: bankNettyClient.sendMessage(isoRequest)

  channel.writeAndFlush(Iso8583Message)
       │
       ▼
  Iso8583Encoder.encode()
       MTI "0100" → [0x30,0x31,0x30,0x30]           4 bytes
       Bitmap     → [0x60,0x10,0x00,...,0x01]         8 bytes
       Field 2    → "16" + "4111111111111111"         18 bytes
       Field 4    → "000000150000"                    12 bytes
       Field 49   → "356"                             3 bytes
       Total: ~45 bytes of ISO 8583 data
       │
       ▼
  LengthFieldPrepender
       Prepends: [0x00,0x00,0x00,0x2D]              4 bytes (length = 45)
       │
       ▼
  TCP Socket → sends 49 bytes to bank at localhost:9090
```

### Inbound: Receiving Bank Response

```
  TCP Socket ← receives bytes from bank
       │
       ▼
  LengthFieldBasedFrameDecoder
       Reads first 4 bytes: [0x00,0x00,0x00,0x28] → length = 40
       Waits until 40 bytes arrive
       Strips 4-byte prefix → passes 40 bytes downstream
       │
       ▼
  ReadTimeoutHandler
       No timeout → passes bytes through
       (If no data for 30s → fires ReadTimeoutException)
       │
       ▼
  Iso8583Decoder.decode()
       parser.parse(data) →
       MTI: "0110" (auth response)
       Bitmap → fields 3, 4, 38, 39 present
       Field 38: "A12345" (authorization code)
       Field 39: "00" (APPROVED!)
       → Iso8583Message object
       │
       ▼
  BankResponseHandler.channelRead0()
       responseFuture.complete(Iso8583Message)
       → Unblocks RoutingController's .join()
       → Controller reads field 39 = "00" → APPROVED
       → Returns RoutingResponse.approved("A12345", bankId, latencyMs)
```

---

## 9. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **Netty Pipeline** | Ordered chain of handlers — each does ONE job, passes data to the next |
| 2 | **Inbound vs Outbound** | Inbound: TCP → application (top to bottom). Outbound: application → TCP (bottom to top) |
| 3 | **TCP framing problem** | TCP is a stream — messages can merge or split. Length-prefix framing solves this |
| 4 | **LengthFieldBasedFrameDecoder** | Reads 4-byte length prefix, waits for complete message, strips prefix |
| 5 | **LengthFieldPrepender** | Adds 4-byte length header before sending — bank knows message size |
| 6 | **ReadTimeoutHandler** | Fires exception if no data for N seconds — detects dead connections |
| 7 | **MessageToByteEncoder** | Base class for outbound encoders: Java object → binary bytes |
| 8 | **ByteToMessageDecoder** | Base class for inbound decoders: binary bytes → Java object |
| 9 | **SimpleChannelInboundHandler** | Convenience handler with auto-release and typed message handling |
| 10 | **`out.add(message)`** | How decoders pass objects to the next handler in the pipeline |
| 11 | **`ctx.fireExceptionCaught()`** | Propagates exceptions through the pipeline to the error handler |
| 12 | **NUMERIC encoding** | `padLeft("50000", 12, '0')` → `"000000050000"` |
| 13 | **ALPHA encoding** | `padRight("TERM001", 8, ' ')` → `"TERM001 "` |
| 14 | **LLVAR encoding** | `String.format("%02d", length)` + value → `"16" + "4111..."` |
| 15 | **Decoder delegates to Parser** | Iso8583Decoder is a thin Netty wrapper around Iso8583MessageParser |
| 16 | **3 ways future completes** | Success (channelRead0), error (exceptionCaught), disconnect (channelInactive) |
| 17 | **Handler order matters** | frameDecoder MUST come before iso8583Decoder; encoder MUST come before prepender |
| 18 | **Named handlers** | `pipeline.addLast("name", handler)` — names help with debugging and removal |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part09-routing-service-overview.md) | Routing Service Overview |
| [Part 9a](./phase4-part09a-routing-project-setup.md) | Project Setup |
| [Part 9b](./phase4-part09b-iso8583-foundation.md) | ISO 8583 Foundation |
| [Part 9c](./phase4-part09c-iso8583-messages.md) | ISO 8583 Messages + Tests |
| [Part 9d](./phase4-part09d-netty-config-client.md) | Netty Config + Client |
| **Part 9e** | **Netty Pipeline** (You are here) |
| [Part 9f](./phase4-part09f-fraud-rule-engine.md) | Fraud Rule Engine |
| [Part 9g](./phase4-part09g-fraud-ml-service.md) | Fraud ML + Service + Tests |
| [Part 9h](./phase4-part09h-smart-routing.md) | Smart Routing + Tests |
| [Part 9i](./phase4-part09i-config-classes.md) | Config Classes (DynamoDB, Resilience4j) |
| [Part 9j](./phase4-part09j-controller-docker.md) | Controller + DTOs + Docker + curl |
| [Part 9k](./phase4-part09k-connections-flows.md) | How Everything Connects |

---

*Next: [Part 9f — Fraud Rule Engine (RuleEngine, FraudFeatureExtractor)](./phase4-part09f-fraud-rule-engine.md) →*
