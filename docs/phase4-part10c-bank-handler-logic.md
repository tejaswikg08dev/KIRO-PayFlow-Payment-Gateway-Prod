# 🏗️ Phase 4 Part 10c: Bank Simulator — Request Handler + Decision Logic

> **"Card 4111 always approves. Card 4000 always declines. Amount over ₹1 lakh always fails. Everything else — roll the dice at 85% success. That's how you build a test bank."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 10c — Request Handler + Decision Logic |
| **What You Build** | Iso8583RequestHandler.java, ResponseGenerator.java, CardBinRules.java, AmountRules.java |
| **Previous** | [Part 10b — Netty TCP Server](./phase4-part10b-bank-netty-server.md) |
| **Next** | [Part 10d — Dockerfile + Testing](./phase4-part10d-bank-docker-testing.md) |

---

## 📖 Table of Contents

1. [The Decision Pipeline](#1-the-decision-pipeline)
2. [Folder Structure After This Part](#2-folder-structure-after-this-part)
3. [Step-by-Step: Iso8583RequestHandler.java](#3-step-by-step-iso8583requesthandlerjava)
4. [Step-by-Step: ResponseGenerator.java](#4-step-by-step-responsegeneratorjava)
5. [Step-by-Step: CardBinRules.java](#5-step-by-step-cardbinrulesjava)
6. [Step-by-Step: AmountRules.java](#6-step-by-step-amountrulesjava)
7. [End-to-End Examples](#7-end-to-end-examples)
8. [What You Learned](#8-what-you-learned)

---

## 1. The Decision Pipeline

Every incoming ISO 8583 request goes through a 3-priority decision cascade:

```
ISO 8583 String arrives from routing service
  │
  ▼
Iso8583RequestHandler (Netty handler)
  ├── simulateLatency() → random 50-500ms sleep
  ├── Parse: MTI(0-4), PAN(4-23), Amount(23-35), RRN(35-47)
  │
  ▼
ResponseGenerator.generateResponse(pan, amount)
  │
  ├── Priority 1: CardBinRules.evaluate(pan)
  │   ├── "4111" → "00" (APPROVE — Visa test card)
  │   ├── "5500" → "00" (APPROVE — Mastercard test card)
  │   ├── "4000" → "05" (DECLINE — Do Not Honor)
  │   ├── "5400" → "14" (DECLINE — Invalid Card)
  │   ├── "4917" → "51" (DECLINE — Insufficient Funds)
  │   └── default → null (no match, continue)
  │
  ├── Priority 2: AmountRules.evaluate(amount)
  │   ├── ≤ 0          → "12" (Invalid Transaction)
  │   ├── > 10,000,000 → "61" (Exceeds Limit)
  │   ├── ends in 13   → "05" (Do Not Honor)
  │   └── default      → null (no match, continue)
  │
  └── Priority 3: Random (config: 85% success)
      ├── random < 85  → "00" (APPROVE)
      └── random ≥ 85  → random("05", "51", "14")
  │
  ▼
Response: "0110" + PAN + Amount + RRN + ResponseCode
  → ctx.writeAndFlush(response)
  → through pipeline → TCP → routing service
```

**WHY THIS ORDER?**
1. **BIN rules first** — deterministic test cards must ALWAYS behave the same (tests depend on it)
2. **Amount rules second** — edge case testing (high amounts, zero amounts)
3. **Random last** — only if no specific rule matched

---

## 2. Folder Structure After This Part

```
backend/bank-simulator/src/main/java/com/payflow/bank/
├── BankSimulatorApplication.java    ← from 10a
├── config/
│   └── SimulatorConfig.java         ← from 10a
├── server/
│   ├── BankSimulatorServer.java     ← from 10b
│   ├── BankChannelInitializer.java  ← from 10b
│   └── Iso8583RequestHandler.java   ← YOU CREATE THIS
└── logic/                           ← YOU CREATE THIS FOLDER
    ├── ResponseGenerator.java       ← YOU CREATE THIS
    ├── CardBinRules.java            ← YOU CREATE THIS
    └── AmountRules.java             ← YOU CREATE THIS
```

---

## 3. Step-by-Step: Iso8583RequestHandler.java

**File:** `src/main/java/com/payflow/bank/server/Iso8583RequestHandler.java`

The Netty handler that receives ISO 8583 requests and sends responses.

### Full Source Code

```java
package com.payflow.bank.server;

import com.payflow.bank.config.SimulatorConfig;
import com.payflow.bank.logic.ResponseGenerator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
```

```java
/**
 * Netty channel handler that processes incoming ISO 8583 requests
 * and generates simulated bank responses.
 *
 * Simplified ISO 8583 format (for simulation):
 * Field layout: MTI(4) + PAN(19) + Amount(12) + RRN(12) = fixed-length message
 */
@Slf4j
@Component
@ChannelHandler.Sharable
public class Iso8583RequestHandler extends SimpleChannelInboundHandler<String> {
```

**THREE ANNOTATIONS — each important:**

| Annotation | Why |
|---|---|
| `@Component` | Spring-managed bean — injected into `BankChannelInitializer` |
| `@ChannelHandler.Sharable` | **🆕 NEW** — ONE instance shared across ALL connections |
| `@Slf4j` | Lombok logging |

**🆕 `@ChannelHandler.Sharable` — CRITICAL ANNOTATION:**

```
WITHOUT @Sharable:
  Each connection gets its OWN handler instance
  → BankChannelInitializer must create new Iso8583RequestHandler() per connection
  → Can't use constructor injection from Spring

WITH @Sharable:
  ONE handler instance is SHARED across all connections
  → BankChannelInitializer injects the Spring-managed singleton
  → Works with constructor injection
  → BUT: handler must be STATELESS (no per-connection fields)
```

**IS OUR HANDLER STATELESS?** Yes — it has no instance fields that change per request. `responseGenerator` and `config` are injected once and shared safely across threads.

**`SimpleChannelInboundHandler<String>`** — receives `String` messages (from `StringDecoder` in the pipeline). Not `ByteBuf`, not `Iso8583Message` — just plain Java String.

```java
    private final ResponseGenerator responseGenerator;
    private final SimulatorConfig config;

    public Iso8583RequestHandler(ResponseGenerator responseGenerator, SimulatorConfig config) {
        this.responseGenerator = responseGenerator;
        this.config = config;
    }
```

**2 DEPENDENCIES:**

| Dependency | Purpose |
|---|---|
| `ResponseGenerator` | Decision logic (BIN rules → amount rules → random) |
| `SimulatorConfig` | Latency settings (minLatencyMs, maxLatencyMs) |

### channelRead0 — Process the Request

```java
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String message) throws Exception {
        log.debug("Received ISO 8583 request: length={}", message.length());

        // Simulate network latency
        simulateLatency();

        // Parse simplified ISO 8583 fields
        String mti = extractField(message, 0, 4);
        String pan = extractField(message, 4, 23);
        String amount = extractField(message, 23, 35);
        String rrn = extractField(message, 35, 47);

        log.info("Processing: MTI={}, PAN={}****, Amount={}, RRN={}",
                mti, pan.substring(0, Math.min(6, pan.length())), amount, rrn);

        // Generate response
        String responseCode = responseGenerator.generateResponse(pan, amount);
        String responseMti = "0110"; // Response MTI

        // Build response message
        String response = responseMti + pan + amount + rrn + responseCode;

        ctx.writeAndFlush(response);
        log.info("Sent response: MTI={}, ResponseCode={}, RRN={}", responseMti, responseCode, rrn);
    }
```

**THE 5-STEP REQUEST PROCESSING:**

**Step 1: Simulate Latency**
Real banks take 50-2000ms to process. We simulate this so routing service's timeout handling gets tested.

**Step 2: Parse Fixed-Position Fields**

```
Message string positions:
  Position  0-3:   MTI          "0100" or "0200"
  Position  4-22:  PAN          "4111111111111111   " (19 chars, space-padded)
  Position 23-34:  Amount       "000000150000" (12 chars)
  Position 35-46:  RRN          "TST123456789" (12 chars, Retrieval Reference Number)
```

| Field | Start | End | Length | Example |
|---|---|---|---|---|
| MTI | 0 | 4 | 4 | `"0100"` |
| PAN | 4 | 23 | 19 | `"4111111111111111"` (trimmed) |
| Amount | 23 | 35 | 12 | `"000000150000"` |
| RRN | 35 | 47 | 12 | `"TST123456789"` |

**THIS IS SIMPLIFIED ISO 8583.** Real ISO 8583 uses bitmap-based field selection (Part 9b). The simulator uses fixed positions for simplicity — it's a test tool, not a production bank.

**Step 3: Log with PAN Masked**

```java
log.info("Processing: MTI={}, PAN={}****, Amount={}, RRN={}",
        mti, pan.substring(0, Math.min(6, pan.length())), amount, rrn);
```

**PCI SECURITY:** Only log the first 6 digits of PAN (the BIN). Never log the full card number.

`Math.min(6, pan.length())` — safety: if PAN is shorter than 6 chars, don't crash.

**Step 4: Generate Response Code**

```java
String responseCode = responseGenerator.generateResponse(pan, amount);
```

Delegates to `ResponseGenerator` which cascades through CardBinRules → AmountRules → Random.

**Step 5: Build and Send Response**

```java
String response = responseMti + pan + amount + rrn + responseCode;
ctx.writeAndFlush(response);
```

**RESPONSE FORMAT:** Same fixed-position layout with MTI changed to `"0110"` (auth response) and response code appended:

```
"0110" + "4111111111111111   " + "000000150000" + "TST123456789" + "00"
 MTI      PAN (19 chars)         Amount (12)      RRN (12)        Code(2)
```

**`ctx.writeAndFlush(response)`** — sends the String through the pipeline:
1. `StringEncoder` → converts String to ByteBuf
2. `LengthFieldPrepender` → adds 2-byte length header
3. TCP socket → bytes sent to routing service

### simulateLatency

```java
    private void simulateLatency() throws InterruptedException {
        int latency = ThreadLocalRandom.current().nextInt(
                config.getMinLatencyMs(), config.getMaxLatencyMs() + 1);
        Thread.sleep(latency);
    }
```

**RANDOM LATENCY between 50 and 500ms.**

`ThreadLocalRandom.current().nextInt(50, 501)` — random integer from 50 to 500 inclusive.

**WHY `+1`?** `nextInt(min, max)` is exclusive on the upper bound. `nextInt(50, 500)` returns 50-499. Adding 1 makes it 50-500.

**`Thread.sleep(latency)`** — blocks the Netty worker thread for this connection. This is acceptable because:
- Each connection has its own handler execution on a worker thread
- Other connections aren't affected (handled by different worker threads)
- In production, real banks also block while processing

### Connection Lifecycle Events

```java
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Channel error: {}", cause.getMessage(), cause);
        ctx.close();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("New connection from: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("Connection closed: {}", ctx.channel().remoteAddress());
    }
```

**THREE LIFECYCLE CALLBACKS:**

| Method | When | Log Message |
|---|---|---|
| `channelActive` | Connection accepted | `"New connection from: /127.0.0.1:54321"` |
| `channelInactive` | Connection closed | `"Connection closed: /127.0.0.1:54321"` |
| `exceptionCaught` | Any error | `"Channel error: ..."` → close connection |

### extractField — Safe Substring

```java
    private String extractField(String message, int start, int end) {
        if (message.length() >= end) {
            return message.substring(start, end).trim();
        }
        return message.substring(start).trim();
    }
}
```

**SAFE EXTRACTION:** If the message is shorter than expected, extract whatever is available instead of throwing `StringIndexOutOfBoundsException`.

**`.trim()`** — removes trailing spaces from fixed-length fields (PAN padded to 19 chars has trailing spaces).

---

## 4. Step-by-Step: ResponseGenerator.java

**File:** `src/main/java/com/payflow/bank/logic/ResponseGenerator.java`

The orchestrator that cascades through rules to decide approve/decline.

### Full Source Code

```java
package com.payflow.bank.logic;

import com.payflow.bank.config.SimulatorConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates bank approval/decline responses based on configurable rules.
 * Checks card BIN rules and amount rules before falling back to random success rate.
 *
 * Response codes:
 *   "00" = Approved
 *   "05" = Do Not Honor (general decline)
 *   "14" = Invalid Card Number
 *   "51" = Insufficient Funds
 *   "61" = Exceeds Amount Limit
 */
@Slf4j
@Component
public class ResponseGenerator {

    private final CardBinRules cardBinRules;
    private final AmountRules amountRules;
    private final SimulatorConfig config;

    public ResponseGenerator(CardBinRules cardBinRules, AmountRules amountRules, SimulatorConfig config) {
        this.cardBinRules = cardBinRules;
        this.amountRules = amountRules;
        this.config = config;
    }
```

**3 DEPENDENCIES — one for each priority level:**

| Dependency | Priority | Purpose |
|---|---|---|
| `CardBinRules` | 1st | Deterministic test card behavior |
| `AmountRules` | 2nd | Amount-based edge cases |
| `SimulatorConfig` | 3rd (fallback) | Random success rate percentage |

### generateResponse — The Decision Cascade

```java
    /**
     * Generate a response code based on card number and amount.
     *
     * @param pan    the Primary Account Number (card number)
     * @param amount the transaction amount as string
     * @return ISO 8583 response code
     */
    public String generateResponse(String pan, String amount) {
        // Check card BIN rules first
        String binResponse = cardBinRules.evaluate(pan);
        if (binResponse != null) {
            log.debug("BIN rule matched: PAN prefix={}, response={}", pan.substring(0, 4), binResponse);
            return binResponse;
        }

        // Check amount rules
        String amountResponse = amountRules.evaluate(amount);
        if (amountResponse != null) {
            log.debug("Amount rule matched: amount={}, response={}", amount, amountResponse);
            return amountResponse;
        }

        // Fall back to configurable random success rate
        int random = ThreadLocalRandom.current().nextInt(100);
        if (random < config.getSuccessRatePercent()) {
            return "00"; // Approved
        }

        // Random decline reason
        String[] declineCodes = {"05", "51", "14"};
        return declineCodes[ThreadLocalRandom.current().nextInt(declineCodes.length)];
    }
}
```

**THE CASCADE — step by step:**

```
Step 1: cardBinRules.evaluate(pan)
  → Returns "00" or "05" or "14" or "51" → DONE (return immediately)
  → Returns null → no BIN rule matched → continue to step 2

Step 2: amountRules.evaluate(amount)
  → Returns "12" or "61" or "05" or "30" → DONE
  → Returns null → no amount rule matched → continue to step 3

Step 3: Random fallback
  → Generate random number 0-99
  → If < 85 (85% chance) → return "00" (approved)
  → If ≥ 85 (15% chance) → pick random decline code from {"05", "51", "14"}
```

**EARLY RETURN PATTERN:** If a higher-priority rule matches, lower-priority rules are NEVER checked. This ensures:
- Test card 4111 ALWAYS approves (even if amount is over ₹1 lakh)
- Amount rules only apply to cards without BIN rules
- Random only applies when no deterministic rule matched

**THE 3 RANDOM DECLINE CODES:**

| Code | Meaning | Probability (when declining) |
|---|---|---|
| `"05"` | Do Not Honor | ~33% |
| `"51"` | Insufficient Funds | ~33% |
| `"14"` | Invalid Card Number | ~33% |

---

## 5. Step-by-Step: CardBinRules.java

**File:** `src/main/java/com/payflow/bank/logic/CardBinRules.java`

Deterministic rules based on the first 4 digits of the card number.

### Full Source Code

```java
package com.payflow.bank.logic;

import org.springframework.stereotype.Component;

/**
 * Card BIN-based approval rules for the bank simulator.
 *
 * Rules:
 *   - 4111 prefix = always approve (test Visa card)
 *   - 4000 prefix = always decline
 *   - 5500 prefix = always approve (test Mastercard)
 *   - 5400 prefix = always decline (Mastercard decline)
 *   - Others = no rule (fall through to random)
 */
@Component
public class CardBinRules {

    /**
     * Evaluate the PAN against BIN rules.
     *
     * @param pan the Primary Account Number
     * @return response code if a BIN rule matches, null otherwise
     */
    public String evaluate(String pan) {
        if (pan == null || pan.length() < 4) {
            return null;
        }

        String bin = pan.substring(0, 4);

        return switch (bin) {
            case "4111" -> "00"; // Always approve (Visa test card 4111111111111111)
            case "5500" -> "00"; // Always approve (Mastercard test card)
            case "4000" -> "05"; // Always decline (Do Not Honor)
            case "5400" -> "14"; // Always decline (Invalid Card Number)
            case "4917" -> "51"; // Insufficient Funds
            default -> null;     // No BIN rule — fall through
        };
    }
}
```

**NULL GUARD:** `pan == null || pan.length() < 4` → return null (no rule). Prevents `StringIndexOutOfBoundsException` on `substring(0, 4)`.

**`pan.substring(0, 4)`** — extract the BIN (first 4 digits). In real banking, BIN is 6-8 digits, but 4 is enough for our test cards.

**SWITCH EXPRESSION (Java 14+)** — returns a value directly. Clean, readable, exhaustive with `default`.

### 🆕 Test Cards — Industry Standard

These test card numbers are used by **every payment gateway** (Stripe, Razorpay, PayPal) for testing:

| Card Number | BIN | Network | Result | Response Code | Use Case |
|---|---|---|---|---|---|
| `4111111111111111` | 4111 | Visa | ✅ APPROVE | `"00"` | Happy path testing |
| `5500000000000004` | 5500 | Mastercard | ✅ APPROVE | `"00"` | Multi-network testing |
| `4000000000000002` | 4000 | Visa | ❌ DECLINE | `"05"` | Decline handling |
| `5400000000000001` | 5400 | Mastercard | ❌ DECLINE | `"14"` | Invalid card testing |
| `4917000000000000` | 4917 | Visa | ❌ DECLINE | `"51"` | Insufficient funds |
| Any other | — | — | 🎲 Random | 85% "00" | Realistic behavior |

**WHY DETERMINISTIC TEST CARDS?**
```
WITHOUT test cards (pure random):
  Test: "Send card 4111... → expect approve"
  Run 1: PASS (random approved) ✅
  Run 2: FAIL (random declined) ❌  ← Test is FLAKY!

WITH test cards (deterministic):
  Test: "Send card 4111... → expect approve"
  Run 1: PASS (always approved) ✅
  Run 2: PASS (always approved) ✅  ← Test is RELIABLE!
```

---

## 6. Step-by-Step: AmountRules.java

**File:** `src/main/java/com/payflow/bank/logic/AmountRules.java`

Amount-based rules for testing edge cases.

### Full Source Code

```java
package com.payflow.bank.logic;

import org.springframework.stereotype.Component;

/**
 * Amount-based approval rules for the bank simulator.
 *
 * Rules:
 *   - Amount > 100000 (1,00,000) = decline with "61" (Exceeds Amount Limit)
 *   - Amount ending in 13 = decline with "05" (Do Not Honor — unlucky number)
 *   - Amount = 0 = decline with "12" (Invalid Transaction)
 *   - Others = no rule (fall through to random)
 */
@Component
public class AmountRules {

    private static final long MAX_AMOUNT = 100000_00L; // 100,000 in minor units (paise/cents)
```

**`100000_00L` — JAVA UNDERSCORE IN NUMBERS (Java 7+):**

```java
100000_00L  →  10,000,000  →  ₹1,00,000 in paise

WHY UNDERSCORES?
  Without: 10000000L  (how many zeros? hard to count!)
  With:    100000_00L (clearly: 100,000 rupees and 00 paise)
```

**`L` suffix** — `long` literal. Without it, Java treats the number as `int` (max ~2.1 billion). Amounts in paise can exceed `int` range for very large transactions.

```java
    /**
     * Evaluate the transaction amount against rules.
     *
     * @param amountStr the amount as string (in minor units, e.g., "100000" = 1000.00)
     * @return response code if an amount rule matches, null otherwise
     */
    public String evaluate(String amountStr) {
        if (amountStr == null || amountStr.isBlank()) {
            return null;
        }

        try {
            long amount = Long.parseLong(amountStr.trim());

            // Zero amount
            if (amount <= 0) {
                return "12"; // Invalid Transaction
            }

            // Amount exceeds limit
            if (amount > MAX_AMOUNT) {
                return "61"; // Exceeds Amount Limit
            }

            // Amount ends in 13 (unlucky number test)
            if (amount % 100 == 13) {
                return "05"; // Do Not Honor
            }

            return null; // No amount rule matched
        } catch (NumberFormatException e) {
            return "30"; // Format Error
        }
    }
}
```

**4 RULES — each with a specific test purpose:**

| Rule | Condition | Response Code | Why | Test Example |
|---|---|---|---|---|
| Zero/negative | `amount <= 0` | `"12"` (Invalid Transaction) | Zero-amount payment is invalid | `amount = "000000000000"` |
| Over limit | `amount > 10,000,000` | `"61"` (Exceeds Amount Limit) | Simulates merchant's approved limit | `amount = "000015000000"` (₹1.5L) |
| Unlucky 13 | `amount % 100 == 13` | `"05"` (Do Not Honor) | Easy way to trigger decline in tests | `amount = "000000001013"` (₹10.13) |
| Format error | `NumberFormatException` | `"30"` (Format Error) | Malformed amount string | `amount = "INVALID"` |

**WHY "ENDS IN 13"?**
```
Problem: You want to test decline behavior but don't have a decline test card.
Solution: Just set the amount to end in 13:
  ₹10.13  → "000000001013" → ends in 13 → decline "05"
  ₹100.13 → "000000010013" → ends in 13 → decline "05"
  ₹1.13   → "000000000113" → ends in 13 → decline "05"

Any amount works. Just make the last two digits "13".
```

**`amount % 100 == 13`** — modulo extracts the last 2 digits:
```
1013 % 100 = 13  → matches!
5000 % 100 = 0   → doesn't match
9999 % 100 = 99  → doesn't match
```

**`try/catch NumberFormatException`** — if `Long.parseLong()` fails (non-numeric string), return "30" (Format Error) instead of crashing.

---

## 7. End-to-End Examples

### Example 1: Visa Test Card, ₹1,500 — APPROVE

```
Input:  "01004111111111111111   000000150000TST123456789"
         MTI  PAN (19 chars)         Amount       RRN

Step 1: CardBinRules.evaluate("4111111111111111")
  bin = "4111" → match! → return "00" ← DONE (skip steps 2-3)

Response: "01104111111111111111   000000150000TST12345678900"
           MTI  PAN                  Amount       RRN         Code
```

### Example 2: Random Card, ₹2,00,000 — DECLINE (Over Limit)

```
Input:  "01005555444433332222   000020000000TST987654321"

Step 1: CardBinRules.evaluate("5555444433332222")
  bin = "5555" → no match → return null → continue

Step 2: AmountRules.evaluate("000020000000")
  amount = 20,000,000 → > 10,000,000 → return "61" ← DONE

Response: "01105555444433332222   000020000000TST98765432161"
                                                           ↑ Code "61"
```

### Example 3: Random Card, ₹500 — RANDOM (85% approve)

```
Input:  "01007777888899990000   000000050000TST111222333"

Step 1: CardBinRules.evaluate("7777888899990000")
  bin = "7777" → no match → null → continue

Step 2: AmountRules.evaluate("000000050000")
  amount = 50,000 → not zero, not > 10M, not ends in 13 → null → continue

Step 3: Random fallback
  random = 42 (out of 0-99)
  42 < 85 (successRatePercent) → return "00" (APPROVE)

Response: "01107777888899990000   000000050000TST11122233300"
                                                           ↑ Code "00"
```

### Example 4: Any Card, ₹10.13 — DECLINE (Unlucky 13)

```
Input:  "01004111111111111111   000000001013TST444555666"

Step 1: CardBinRules.evaluate("4111111111111111")
  bin = "4111" → match! → return "00"
  ← BIN rule takes PRIORITY over amount rule!
  ← Card 4111 ALWAYS approves, even with "unlucky" amount

BUT if the card were "7777888899990000":
  Step 1: bin = "7777" → no match → null
  Step 2: amount = 1013, 1013 % 100 = 13 → return "05" (DECLINE)
```

**THIS SHOWS WHY PRIORITY ORDER MATTERS:** Card 4111 always approves regardless of amount. The BIN rule overrides everything.

---

## 8. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **`@ChannelHandler.Sharable`** | One handler instance shared across all connections — must be stateless |
| 2 | **`SimpleChannelInboundHandler<String>`** | Handler receives String messages (from StringDecoder in pipeline) |
| 3 | **Fixed-position field parsing** | MTI(0-4), PAN(4-23), Amount(23-35), RRN(35-47) — simplified ISO 8583 |
| 4 | **PAN masking in logs** | `pan.substring(0, Math.min(6, pan.length()))` — PCI: never log full card number |
| 5 | **Response format** | `"0110" + PAN + Amount + RRN + ResponseCode` — same positions, different MTI |
| 6 | **`simulateLatency()`** | `Thread.sleep(random(50, 501))` — realistic bank processing time |
| 7 | **`ThreadLocalRandom.nextInt(min, max+1)`** | Exclusive upper bound — add 1 for inclusive |
| 8 | **Decision cascade** | BIN rules → Amount rules → Random. First match wins (early return) |
| 9 | **`ResponseGenerator` separates logic from I/O** | Single Responsibility: handler does Netty I/O, generator does business logic |
| 10 | **CardBinRules switch expression** | 5 deterministic test cards + default null fallthrough |
| 11 | **Test cards (4111, 4000, 5500, etc.)** | Industry-standard test numbers — same ones Stripe/Razorpay use |
| 12 | **WHY deterministic test cards** | Flaky tests (random) vs reliable tests (deterministic cards always behave the same) |
| 13 | **`100000_00L` underscores** | Java 7+ readability: `100000_00L` = 10,000,000 = ₹1,00,000 in paise |
| 14 | **Modulo for "ends in 13"** | `amount % 100 == 13` — easy decline trigger for any card |
| 15 | **`NumberFormatException` → "30"** | Defensive: malformed amount returns Format Error, doesn't crash |
| 16 | **Priority order matters** | Card 4111 ALWAYS approves — even if amount triggers a decline rule |
| 17 | **`extractField` safe substring** | Handles short messages without `StringIndexOutOfBoundsException` |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part10-bank-simulator-overview.md) | Bank Simulator Overview |
| [Part 10a](./phase4-part10a-bank-project-setup.md) | Project Setup |
| [Part 10b](./phase4-part10b-bank-netty-server.md) | Netty TCP Server |
| **Part 10c** | **Request Handler + Decision Logic** (You are here) |
| [Part 10d](./phase4-part10d-bank-docker-testing.md) | Dockerfile + Testing + Connections |

---

*Next: [Part 10d — Dockerfile + Testing + Connections](./phase4-part10d-bank-docker-testing.md) →*
