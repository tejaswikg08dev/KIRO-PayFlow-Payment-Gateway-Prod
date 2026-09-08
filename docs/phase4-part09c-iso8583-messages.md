# 🏗️ Phase 4 Part 9c: Routing Service — ISO 8583 Messages (Model, Builder, Parser + Tests)

> **"Build a message field by field. Encode it to bytes. Send it across the wire. Parse it back. If all fields survive the roundtrip, your protocol implementation is correct."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 9c — ISO 8583 Messages |
| **What You Build** | Iso8583Message.java, Iso8583MessageBuilder.java, Iso8583MessageParser.java, Iso8583MessageBuilderTest.java, Iso8583MessageParserTest.java |
| **Previous** | [Part 9b — ISO 8583 Foundation](./phase4-part09b-iso8583-foundation.md) |
| **Next** | [Part 9d — Netty Config + Client](./phase4-part09d-netty-config-client.md) |

---

## 📖 Table of Contents

1. [What We Build — 3 Files + 2 Test Files](#1-what-we-build--3-files--2-test-files)
2. [Folder Structure After This Part](#2-folder-structure-after-this-part)
3. [Step-by-Step: Iso8583Message.java](#3-step-by-step-iso8583messagejava)
4. [Step-by-Step: Iso8583MessageBuilder.java](#4-step-by-step-iso8583messagebuilderjava)
5. [Step-by-Step: Iso8583MessageParser.java](#5-step-by-step-iso8583messageparserjava)
6. [Step-by-Step: Iso8583MessageBuilderTest.java](#6-step-by-step-iso8583messagebuildertest)
7. [Step-by-Step: Iso8583MessageParserTest.java](#7-step-by-step-iso8583messageparsertest)
8. [The Build → Encode → Parse → Verify Roundtrip](#8-the-build--encode--parse--verify-roundtrip)
9. [What You Learned](#9-what-you-learned)

---

## 1. What We Build — 3 Files + 2 Test Files

| File | Pattern | Purpose |
|---|---|---|
| `Iso8583Message` | Model (POJO) | Holds MTI + bitmap + fields — the data container |
| `Iso8583MessageBuilder` | Builder Pattern | Fluent API to construct messages step-by-step |
| `Iso8583MessageParser` | Parser | Converts raw binary bytes → Iso8583Message object |
| `Iso8583MessageBuilderTest` | Unit Test | 6 tests validating builder behavior |
| `Iso8583MessageParserTest` | Unit Test | 5 tests including roundtrip verification |

**HOW THEY WORK TOGETHER:**
```
BUILD:  Iso8583MessageBuilder → creates → Iso8583Message
SEND:   Iso8583Encoder (Part 9e) → serializes → byte[]  → TCP → bank
RECEIVE: byte[] → Iso8583MessageParser → creates → Iso8583Message
```

---

## 2. Folder Structure After This Part

```
backend/routing-service/src/
├── main/java/com/payflow/routing/iso8583/
│   ├── Iso8583Field.java               ← from 9b
│   ├── BitmapUtils.java                ← from 9b
│   ├── Iso8583Constants.java           ← from 9b
│   ├── Iso8583Message.java             ← YOU CREATE THIS
│   ├── Iso8583MessageBuilder.java      ← YOU CREATE THIS
│   └── Iso8583MessageParser.java       ← YOU CREATE THIS
└── test/java/com/payflow/routing/iso8583/
    ├── Iso8583MessageBuilderTest.java   ← YOU CREATE THIS
    └── Iso8583MessageParserTest.java    ← YOU CREATE THIS
```

---

## 3. Step-by-Step: Iso8583Message.java

**File:** `src/main/java/com/payflow/routing/iso8583/Iso8583Message.java`

This is a plain POJO — it just holds data. The Builder creates it. The Parser populates it. The Encoder serializes it.

### Full Source Code

```java
package com.payflow.routing.iso8583;

import java.util.HashMap;
import java.util.Map;
```

```java
/**
 * Represents an ISO 8583 financial transaction message.
 * Contains the Message Type Indicator (MTI), bitmap, and data fields.
 */
public class Iso8583Message {

    private String mti;
    private Map<Integer, String> fields;
    private byte[] bitmap;
```

**THREE FIELDS — that's ALL an ISO 8583 message is:**

| Field | Type | What It Holds | Example |
|---|---|---|---|
| `mti` | `String` | Message Type Indicator | `"0100"` (auth request) |
| `fields` | `Map<Integer, String>` | Field number → value | `{2: "4111...", 4: "000000050000"}` |
| `bitmap` | `byte[]` | 64-bit bitmap (8 bytes) | Which fields are present |

**WHY `Map<Integer, String>` NOT a Class with Named Fields?**
```
With named fields (what you might expect):
  message.setPan("4111...");
  message.setAmount("000000050000");
  → You'd need a field for all 64 possible ISO 8583 fields!

With Map<Integer, String> (what we use):
  message.setField(2, "4111...");     // PAN
  message.setField(4, "000000050000"); // Amount
  → Flexible: supports any field number without changing the class
  → Sparse: only stores fields that are actually present
```

```java
    public Iso8583Message() {
        this.fields = new HashMap<>();
        this.bitmap = new byte[8]; // 64-bit primary bitmap
    }
```

**No-arg constructor** — used by the Parser when creating an empty message and populating it.

```java
    public Iso8583Message(String mti, Map<Integer, String> fields, byte[] bitmap) {
        this.mti = mti;
        this.fields = fields != null ? new HashMap<>(fields) : new HashMap<>();
        this.bitmap = bitmap != null ? bitmap.clone() : new byte[8];
    }
```

**DEFENSIVE COPIES — Important Security Pattern:**

| Without defensive copy | With defensive copy (our code) |
|---|---|
| `this.fields = fields;` | `this.fields = new HashMap<>(fields);` |
| External code can modify OUR fields | External code modifies its copy, not ours |
| `this.bitmap = bitmap;` | `this.bitmap = bitmap.clone();` |
| External code can corrupt OUR bitmap | External code modifies its copy, not ours |

**`new HashMap<>(fields)`** — creates a new HashMap containing all entries from the original. Changes to the original don't affect our copy.

**`bitmap.clone()`** — creates a new byte array with the same values. Changes to the original array don't affect our copy.

**WHY THIS MATTERS:** The Builder creates fields and bitmap, then passes them to the Message constructor. Without defensive copies, the Builder could accidentally modify the message's internal state after construction.

```java
    public String getMti() {
        return mti;
    }

    public void setMti(String mti) {
        this.mti = mti;
    }

    public Map<Integer, String> getFields() {
        return fields;
    }

    public void setFields(Map<Integer, String> fields) {
        this.fields = fields;
    }

    public String getField(int fieldNumber) {
        return fields.get(fieldNumber);
    }
```

**`getField(int)`** — convenience method. Instead of `message.getFields().get(4)`, just `message.getField(4)`.

```java
    public void setField(int fieldNumber, String value) {
        fields.put(fieldNumber, value);
        BitmapUtils.setFieldPresent(bitmap, fieldNumber);
    }
```

**KEY BEHAVIOR: `setField()` automatically updates the bitmap.**

When you add a field, the corresponding bit in the bitmap is set to 1. This ensures the bitmap always reflects which fields are actually present. You don't have to manage the bitmap manually.

```java
    public boolean hasField(int fieldNumber) {
        return fields.containsKey(fieldNumber);
    }

    public byte[] getBitmap() {
        return bitmap != null ? bitmap.clone() : null;
    }

    public void setBitmap(byte[] bitmap) {
        this.bitmap = bitmap != null ? bitmap.clone() : new byte[8];
    }
```

**`getBitmap()` also returns a clone** — prevents external code from modifying the message's bitmap through the getter.

```java
    @Override
    public String toString() {
        return "Iso8583Message{" +
                "mti='" + mti + '\'' +
                ", fields=" + fields.keySet() +
                '}';
    }
}
```

**`fields.keySet()`** — prints only the field NUMBERS, not the values. This is important for security: you don't want `toString()` printing card numbers in logs.

**WHY NO LOMBOK `@Data`?** This class is intentionally plain Java:
- Defensive copies in constructor and getters require manual implementation
- `toString()` intentionally omits sensitive field values
- `@Data` would generate `toString()` that includes all field values (PAN, etc.)

---

## 4. Step-by-Step: Iso8583MessageBuilder.java

**File:** `src/main/java/com/payflow/routing/iso8583/Iso8583MessageBuilder.java`

The Builder Pattern — construct messages step-by-step with a fluent, readable API.

### Full Source Code

```java
package com.payflow.routing.iso8583;

import java.util.HashMap;
import java.util.Map;

/**
 * Fluent builder for constructing ISO 8583 messages.
 * <p>
 * Usage:
 * <pre>
 * Iso8583Message message = new Iso8583MessageBuilder()
 *     .setMti("0100")
 *     .setField(Iso8583Constants.FIELD_PAN, "4111111111111111")
 *     .setField(Iso8583Constants.FIELD_AMOUNT, "000000010000")
 *     .build();
 * </pre>
 */
public class Iso8583MessageBuilder {

    private String mti;
    private final Map<Integer, String> fields;
    private final byte[] bitmap;

    public Iso8583MessageBuilder() {
        this.fields = new HashMap<>();
        this.bitmap = new byte[8]; // 64-bit primary bitmap
    }
```

**BUILDER STATE:** The builder accumulates fields as you chain calls. `build()` creates the final Iso8583Message from the accumulated state.

### Core Methods

```java
    /**
     * Sets the Message Type Indicator (MTI).
     *
     * @param mti 4-digit MTI code (e.g., "0100", "0200")
     * @return this builder for chaining
     */
    public Iso8583MessageBuilder setMti(String mti) {
        if (mti == null || mti.length() != 4) {
            throw new IllegalArgumentException("MTI must be a 4-digit string");
        }
        this.mti = mti;
        return this;
    }
```

**VALIDATION AT CALL TIME:** If you pass `"01"` instead of `"0100"`, you get an immediate error — not a confusing failure later during encoding.

**`return this;`** — enables method chaining: `builder.setMti("0100").setPan("4111...").build()`

```java
    /**
     * Sets a field value by field number.
     *
     * @param fieldNumber ISO 8583 field number (2-128)
     * @param value       Field value
     * @return this builder for chaining
     */
    public Iso8583MessageBuilder setField(int fieldNumber, String value) {
        if (fieldNumber < 2 || fieldNumber > 128) {
            throw new IllegalArgumentException("Field number must be between 2 and 128");
        }
        if (value == null) {
            throw new IllegalArgumentException("Field value cannot be null");
        }
        fields.put(fieldNumber, value);
        BitmapUtils.setFieldPresent(bitmap, fieldNumber);
        return this;
    }
```

**TWO THINGS HAPPEN on `setField()`:**
1. `fields.put(fieldNumber, value)` — store the value
2. `BitmapUtils.setFieldPresent(bitmap, fieldNumber)` — set the bit

**WHY FIELD 2-128?** Field 1 is the secondary bitmap indicator — it's managed automatically by the bitmap itself, not set directly.

### Convenience Methods

```java
    /**
     * Sets the Primary Account Number (PAN) - Field 2.
     */
    public Iso8583MessageBuilder setPan(String pan) {
        return setField(Iso8583Constants.FIELD_PAN, pan);
    }

    /**
     * Sets the Processing Code - Field 3.
     */
    public Iso8583MessageBuilder setProcessingCode(String code) {
        return setField(Iso8583Constants.FIELD_PROCESSING_CODE, code);
    }

    /**
     * Sets the Transaction Amount - Field 4.
     */
    public Iso8583MessageBuilder setAmount(String amount) {
        return setField(Iso8583Constants.FIELD_AMOUNT, amount);
    }

    /**
     * Sets the System Trace Audit Number - Field 11.
     */
    public Iso8583MessageBuilder setTraceNumber(String trace) {
        return setField(Iso8583Constants.FIELD_TRACE, trace);
    }

    /**
     * Sets the Transaction Time - Field 12.
     */
    public Iso8583MessageBuilder setTime(String time) {
        return setField(Iso8583Constants.FIELD_TIME, time);
    }

    /**
     * Sets the Terminal ID - Field 41.
     */
    public Iso8583MessageBuilder setTerminalId(String terminalId) {
        return setField(Iso8583Constants.FIELD_TERMINAL_ID, terminalId);
    }

    /**
     * Sets the Merchant Name - Field 43.
     */
    public Iso8583MessageBuilder setMerchantName(String name) {
        return setField(Iso8583Constants.FIELD_MERCHANT_NAME, name);
    }

    /**
     * Sets the Currency Code - Field 49.
     */
    public Iso8583MessageBuilder setCurrencyCode(String currencyCode) {
        return setField(Iso8583Constants.FIELD_CURRENCY_CODE, currencyCode);
    }
```

**ALL 8 CONVENIENCE METHODS** delegate to `setField()`. They exist for readability:

```java
// Without convenience methods (hard to read):
builder.setField(2, "4111111111111111")
       .setField(3, "000000")
       .setField(4, "000000050000")
       .setField(49, "356");

// With convenience methods (clear intent):
builder.setPan("4111111111111111")
       .setProcessingCode("000000")
       .setAmount("000000050000")
       .setCurrencyCode("356");
```

### The Build Method

```java
    /**
     * Builds the ISO 8583 message from the configured fields.
     *
     * @return constructed Iso8583Message
     * @throws IllegalStateException if MTI is not set
     */
    public Iso8583Message build() {
        if (mti == null) {
            throw new IllegalStateException("MTI must be set before building the message");
        }
        return new Iso8583Message(mti, fields, bitmap);
    }
}
```

**FAIL FAST:** If you forget to set the MTI, `build()` throws immediately. This catches programming errors early.

**`IllegalStateException` vs `IllegalArgumentException`:**
- `IllegalArgumentException` = "you passed a bad parameter" (wrong input)
- `IllegalStateException` = "the object is in the wrong state" (forgot a step)

Forgetting to call `setMti()` before `build()` is a state problem, not an input problem.

**THE BUILDER PATTERN — WHY?**

| Without Builder | With Builder |
|---|---|
| `new Iso8583Message("0100", fields, bitmap)` | `builder.setMti("0100").setPan("4111...").setAmount("...").build()` |
| Must construct fields map first | Chain calls naturally |
| Must manage bitmap manually | Bitmap managed automatically |
| Error-prone: wrong field numbers | Readable: `.setPan()` is self-documenting |
| No validation until runtime | Validates at each step |

---

## 5. Step-by-Step: Iso8583MessageParser.java

**File:** `src/main/java/com/payflow/routing/iso8583/Iso8583MessageParser.java`

The Parser does the **reverse** of the Builder: takes raw bytes → produces an Iso8583Message.

### Full Source Code

```java
package com.payflow.routing.iso8583;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses binary ISO 8583 messages into Iso8583Message objects.
 * <p>
 * Binary format:
 * [MTI - 4 bytes ASCII] [Bitmap - 8 bytes] [Data Fields...]
 */
public class Iso8583MessageParser {

    private static final Logger log = LoggerFactory.getLogger(Iso8583MessageParser.class);

    private static final Map<Integer, Iso8583Field> FIELD_DEFINITIONS = Iso8583Constants.getFieldDefinitions();
```

**`FIELD_DEFINITIONS`** — loaded once at class init. The parser needs to know "field 4 is NUMERIC, maxLength 12" to read exactly 12 bytes for that field.

**`static final`** — the field definitions never change, so load them once and reuse.

### The Parse Method — 3 Steps

```java
    /**
     * Parses a binary byte array into an Iso8583Message.
     *
     * @param data Raw binary ISO 8583 message bytes
     * @return Parsed Iso8583Message
     * @throws Iso8583ParseException if the message cannot be parsed
     */
    public Iso8583Message parse(byte[] data) {
        if (data == null || data.length < 12) {
            throw new Iso8583ParseException("Invalid ISO 8583 message: insufficient data length");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);
```

**MINIMUM SIZE = 12 bytes:** 4 (MTI) + 8 (bitmap) = 12. A message with no data fields is at least 12 bytes.

**`ByteBuffer.wrap(data)`** — wraps the byte array in a ByteBuffer for sequential reading. ByteBuffer tracks the current position automatically — each `get()` advances the position.

```java
        // Parse MTI (4 bytes ASCII)
        byte[] mtiBytes = new byte[4];
        buffer.get(mtiBytes);
        String mti = new String(mtiBytes, StandardCharsets.US_ASCII);
```

**STEP 1: Read MTI.** First 4 bytes, always ASCII.

```
Bytes: [0x30, 0x31, 0x30, 0x30, ...]
        '0'    '1'    '0'    '0'
→ mti = "0100"
```

```java
        // Parse Bitmap (8 bytes - 64 bits primary bitmap)
        byte[] bitmap = new byte[8];
        buffer.get(bitmap);
```

**STEP 2: Read Bitmap.** Next 8 bytes, raw binary. Each bit = one field.

```java
        // Parse data fields based on bitmap
        Map<Integer, String> fields = new HashMap<>();
        for (int fieldNum = 2; fieldNum <= 64; fieldNum++) {
            if (BitmapUtils.isFieldPresent(bitmap, fieldNum)) {
                String value = parseField(buffer, fieldNum);
                if (value != null) {
                    fields.put(fieldNum, value);
                }
            }
        }

        log.debug("Parsed ISO 8583 message: MTI={}, fields={}", mti, fields.keySet());
        return new Iso8583Message(mti, fields, bitmap);
    }
```

**STEP 3: Read Data Fields.** For each field 2-64, check the bitmap. If the bit is set, read that field from the buffer. The field type determines HOW to read it.

**WHY START AT FIELD 2?** Field 1 is the secondary bitmap indicator (we don't use fields 65+).

**THE ORDER MATTERS:** Fields appear in the data in ascending order by field number. So the parser reads 2, then 3, then 4, then 11, etc. — exactly the order the encoder wrote them.

### parseField — Dispatching by Type

```java
    /**
     * Parses a single field from the buffer based on field definitions.
     */
    private String parseField(ByteBuffer buffer, int fieldNumber) {
        Iso8583Field fieldDef = FIELD_DEFINITIONS.get(fieldNumber);
        if (fieldDef == null) {
            log.warn("No field definition for field {}, skipping", fieldNumber);
            return null;
        }

        try {
            return switch (fieldDef.type()) {
                case NUMERIC -> parseFixedField(buffer, fieldDef.maxLength());
                case ALPHA -> parseFixedField(buffer, fieldDef.maxLength());
                case LLVAR -> parseVariableField(buffer, 2);
                case LLLVAR -> parseVariableField(buffer, 3);
            };
        } catch (Exception e) {
            log.error("Error parsing field {}: {}", fieldNumber, e.getMessage());
            throw new Iso8583ParseException("Failed to parse field " + fieldNumber, e);
        }
    }
```

**DISPATCH BY FIELD TYPE:**

| Type | Parse Method | How It Reads |
|---|---|---|
| NUMERIC | `parseFixedField(maxLength)` | Read exactly `maxLength` bytes, trim |
| ALPHA | `parseFixedField(maxLength)` | Read exactly `maxLength` bytes, trim |
| LLVAR | `parseVariableField(2)` | Read 2-byte length prefix, then that many data bytes |
| LLLVAR | `parseVariableField(3)` | Read 3-byte length prefix, then that many data bytes |

**NUMERIC and ALPHA both call `parseFixedField`** — same logic, different padding (zero-padded vs space-padded, but `.trim()` handles both).

### parseFixedField + parseVariableField

```java
    /**
     * Parses a fixed-length field.
     */
    private String parseFixedField(ByteBuffer buffer, int length) {
        if (buffer.remaining() < length) {
            throw new Iso8583ParseException("Insufficient data for fixed field");
        }
        byte[] fieldBytes = new byte[length];
        buffer.get(fieldBytes);
        return new String(fieldBytes, StandardCharsets.US_ASCII).trim();
    }
```

**FIXED FIELD:** Read exactly `length` bytes, convert to String, trim padding.

```
Field 4 (NUMERIC, maxLength=12):
  Buffer contains: "000000050000" (12 bytes)
  → Read 12 bytes → "000000050000" → trim → "000000050000" (no change, zeros are significant)

Field 41 (ALPHA, maxLength=8):
  Buffer contains: "TERM0001" (8 bytes)
  → Read 8 bytes → "TERM0001" → trim → "TERM0001"
```

```java
    /**
     * Parses a variable-length field with LL or LLL prefix.
     */
    private String parseVariableField(ByteBuffer buffer, int lengthDigits) {
        if (buffer.remaining() < lengthDigits) {
            throw new Iso8583ParseException("Insufficient data for variable field length prefix");
        }
        byte[] lengthBytes = new byte[lengthDigits];
        buffer.get(lengthBytes);
        int length = Integer.parseInt(new String(lengthBytes, StandardCharsets.US_ASCII));

        if (buffer.remaining() < length) {
            throw new Iso8583ParseException("Insufficient data for variable field value");
        }
        byte[] fieldBytes = new byte[length];
        buffer.get(fieldBytes);
        return new String(fieldBytes, StandardCharsets.US_ASCII);
    }
```

**VARIABLE FIELD — TWO-STEP READ:**

```
Field 2 (LLVAR, PAN):
  Step 1: Read 2 bytes → "16" → length = 16
  Step 2: Read 16 bytes → "4111111111111111" → PAN

Field 43 (LLLVAR, Merchant Name):
  Step 1: Read 3 bytes → "022" → length = 22
  Step 2: Read 22 bytes → "AMAZON INDIA BANGALORE" → merchant name
```

**THREE DEFENSIVE CHECKS:**
1. `data == null || data.length < 12` — reject garbage input
2. `buffer.remaining() < length` — reject truncated fixed fields
3. `buffer.remaining() < lengthDigits` and `buffer.remaining() < length` — reject truncated variable fields

### Custom Exception Class

```java
    /**
     * Exception thrown when an ISO 8583 message cannot be parsed.
     */
    public static class Iso8583ParseException extends RuntimeException {
        public Iso8583ParseException(String message) {
            super(message);
        }

        public Iso8583ParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
```

**NESTED STATIC CLASS** — `Iso8583MessageParser.Iso8583ParseException`. Common Java pattern: keep related exceptions with the class that throws them.

**`RuntimeException`** — unchecked exception. The caller doesn't HAVE to catch it (no `throws` clause needed). This is appropriate because parse failures indicate bad data — the caller should handle them, but the compiler doesn't force it.

**TWO CONSTRUCTORS:**
- `Iso8583ParseException(message)` — for errors we detect ourselves
- `Iso8583ParseException(message, cause)` — wraps underlying exceptions (like `NumberFormatException` from `Integer.parseInt`)

---

## 6. Step-by-Step: Iso8583MessageBuilderTest.java

**File:** `src/test/java/com/payflow/routing/iso8583/Iso8583MessageBuilderTest.java`

### Full Source Code

```java
package com.payflow.routing.iso8583;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

**TESTING LIBRARY: AssertJ** (not JUnit assertions)

| JUnit assertion | AssertJ assertion (what we use) |
|---|---|
| `assertEquals("0100", message.getMti())` | `assertThat(message.getMti()).isEqualTo("0100")` |
| `assertNotNull(message)` | `assertThat(message).isNotNull()` |
| `assertThrows(Exception.class, () -> ...)` | `assertThatThrownBy(() -> ...).isInstanceOf(Exception.class)` |

**WHY ASSERTJ?** More readable, better error messages, fluent chaining.

```java
@DisplayName("Iso8583MessageBuilder Unit Tests")
class Iso8583MessageBuilderTest {

    private Iso8583MessageBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new Iso8583MessageBuilder();
    }
```

**`@BeforeEach`** — creates a fresh builder before every test. Tests don't share state.

### Test 1: Build Auth Request with Correct MTI

```java
    @Test
    @DisplayName("build - should create auth request message with correct MTI")
    void build_AuthRequestMessage_HasCorrectMti() {
        Iso8583Message message = builder
                .setMti(Iso8583Constants.MTI_AUTH_REQUEST)
                .setPan("4111111111111111")
                .setProcessingCode(Iso8583Constants.PROC_CODE_PURCHASE)
                .setAmount("000000010000")
                .setTraceNumber("123456")
                .setTime("143025")
                .setTerminalId("TERM0001")
                .setCurrencyCode("356")
                .build();

        assertThat(message).isNotNull();
        assertThat(message.getMti()).isEqualTo("0100");
    }
```

**WHAT IT TESTS:** Build a complete auth request with 8 fields and verify MTI is correct.

**THIS IS THE MOST REALISTIC TEST** — it mimics what `RoutingController.buildIso8583Request()` does in production.

### Test 2: Verify Field Values

```java
    @Test
    @DisplayName("build - should set PAN in field 2")
    void build_SetsFieldValues() {
        Iso8583Message message = builder
                .setMti("0100")
                .setPan("4111111111111111")
                .setAmount("000000010000")
                .setTraceNumber("123456")
                .build();

        assertThat(message.getField(Iso8583Constants.FIELD_PAN)).isEqualTo("4111111111111111");
        assertThat(message.getField(Iso8583Constants.FIELD_AMOUNT)).isEqualTo("000000010000");
        assertThat(message.getField(Iso8583Constants.FIELD_TRACE)).isEqualTo("123456");
    }
```

**WHAT IT TESTS:** Convenience methods (`setPan`, `setAmount`, `setTraceNumber`) store values in the correct field numbers (2, 4, 11).

### Test 3: Bitmap Correctness

```java
    @Test
    @DisplayName("build - should set bitmap correctly for present fields")
    void build_SetsBitmapForPresentFields() {
        Iso8583Message message = builder
                .setMti("0200")
                .setPan("5500000000000004")
                .setAmount("000000025000")
                .setTerminalId("TERM0002")
                .setCurrencyCode("840")
                .build();

        byte[] bitmap = message.getBitmap();
        assertThat(bitmap).isNotNull();
        assertThat(bitmap).hasSize(8);

        // Verify fields are present in bitmap
        assertThat(BitmapUtils.isFieldPresent(bitmap, Iso8583Constants.FIELD_PAN)).isTrue();
        assertThat(BitmapUtils.isFieldPresent(bitmap, Iso8583Constants.FIELD_AMOUNT)).isTrue();
        assertThat(BitmapUtils.isFieldPresent(bitmap, Iso8583Constants.FIELD_TERMINAL_ID)).isTrue();
        assertThat(BitmapUtils.isFieldPresent(bitmap, Iso8583Constants.FIELD_CURRENCY_CODE)).isTrue();

        // Verify fields that are NOT present
        assertThat(BitmapUtils.isFieldPresent(bitmap, Iso8583Constants.FIELD_TRACE)).isFalse();
    }
```

**THE MOST IMPORTANT BUILDER TEST.** Verifies that:
1. Setting a field → its bit is ON in the bitmap
2. NOT setting a field → its bit is OFF in the bitmap

This ensures the bitmap and fields map stay in sync.

### Test 4-6: Error Cases

```java
    @Test
    @DisplayName("build - should throw IllegalStateException when MTI is not set")
    void build_NoMti_ThrowsException() {
        builder.setPan("4111111111111111")
                .setAmount("000000010000");

        assertThatThrownBy(() -> builder.build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MTI must be set");
    }

    @Test
    @DisplayName("setMti - should throw IllegalArgumentException for invalid MTI")
    void setMti_InvalidMti_ThrowsException() {
        assertThatThrownBy(() -> builder.setMti("01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MTI must be a 4-digit string");
    }

    @Test
    @DisplayName("setField - should throw IllegalArgumentException for invalid field number")
    void setField_InvalidFieldNumber_ThrowsException() {
        assertThatThrownBy(() -> builder.setField(0, "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Field number must be between 2 and 128");

        assertThatThrownBy(() -> builder.setField(129, "value"))
                .isInstanceOf(IllegalArgumentException.class);
    }
```

**THREE VALIDATION TESTS:**

| Test | Input | Expected Error |
|---|---|---|
| Build without MTI | No `setMti()` call | `IllegalStateException: "MTI must be set"` |
| Invalid MTI | `"01"` (2 chars, not 4) | `IllegalArgumentException: "MTI must be a 4-digit string"` |
| Invalid field number | 0 or 129 | `IllegalArgumentException: "Field number must be between 2 and 128"` |

---

## 7. Step-by-Step: Iso8583MessageParserTest.java

**File:** `src/test/java/com/payflow/routing/iso8583/Iso8583MessageParserTest.java`

### Setup

```java
package com.payflow.routing.iso8583;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Iso8583MessageParser Unit Tests")
class Iso8583MessageParserTest {

    private Iso8583MessageParser parser;
    private Iso8583MessageBuilder builder;

    @BeforeEach
    void setUp() {
        parser = new Iso8583MessageParser();
        builder = new Iso8583MessageBuilder();
    }
```

**BOTH parser AND builder** are created — the tests use the builder to create messages, then encode and parse them back.

### Test 1: Roundtrip — The Most Critical Test

```java
    @Test
    @DisplayName("parse - should roundtrip build and parse an auth request message")
    void parse_Roundtrip_AuthRequest() {
        // Build a message
        Iso8583Message original = builder
                .setMti(Iso8583Constants.MTI_AUTH_REQUEST)
                .setProcessingCode(Iso8583Constants.PROC_CODE_PURCHASE)
                .setAmount("000000010000")
                .setTraceNumber("654321")
                .setTime("153042")
                .setTerminalId("TERM0001")
                .setCurrencyCode("356")
                .build();

        // Encode the message to bytes
        byte[] encoded = encodeMessage(original);

        // Parse the bytes back
        Iso8583Message parsed = parser.parse(encoded);

        // Verify roundtrip integrity
        assertThat(parsed.getMti()).isEqualTo("0100");
        assertThat(parsed.getField(Iso8583Constants.FIELD_PROCESSING_CODE)).isEqualTo("000000");
        assertThat(parsed.getField(Iso8583Constants.FIELD_AMOUNT)).isEqualTo("000000010000");
        assertThat(parsed.getField(Iso8583Constants.FIELD_TRACE)).isEqualTo("654321");
        assertThat(parsed.getField(Iso8583Constants.FIELD_TIME)).isEqualTo("153042");
        assertThat(parsed.getField(Iso8583Constants.FIELD_TERMINAL_ID)).isEqualTo("TERM0001");
        assertThat(parsed.getField(Iso8583Constants.FIELD_CURRENCY_CODE)).isEqualTo("356");
    }
```

**THE ROUNDTRIP TEST** — proves the entire ISO 8583 implementation is correct:

```
Build → Encode → Parse → Verify
  ↓        ↓       ↓        ↓
Message  bytes   Message  All fields match original?
```

If this test passes, it means:
- Builder creates messages correctly
- Encoding produces valid binary
- Parser reads the binary correctly
- All field types (NUMERIC, ALPHA, LLVAR, LLLVAR) work

### Test 2: MTI Parsing

```java
    @Test
    @DisplayName("parse - should correctly parse MTI from byte array")
    void parse_CorrectMti() {
        Iso8583Message original = builder
                .setMti(Iso8583Constants.MTI_FINANCIAL_REQUEST)
                .setAmount("000000099900")
                .setTraceNumber("000001")
                .build();

        byte[] encoded = encodeMessage(original);
        Iso8583Message parsed = parser.parse(encoded);

        assertThat(parsed.getMti()).isEqualTo("0200");
    }
```

**Tests a different MTI** (`"0200"` = financial request) to ensure parser isn't hardcoded for `"0100"`.

### Test 3-4: Error Cases

```java
    @Test
    @DisplayName("parse - should throw Iso8583ParseException for insufficient data")
    void parse_InsufficientData_ThrowsException() {
        byte[] shortData = new byte[5]; // Less than minimum (12 bytes)

        assertThatThrownBy(() -> parser.parse(shortData))
                .isInstanceOf(Iso8583MessageParser.Iso8583ParseException.class)
                .hasMessageContaining("insufficient data");
    }

    @Test
    @DisplayName("parse - should throw Iso8583ParseException for null data")
    void parse_NullData_ThrowsException() {
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOf(Iso8583MessageParser.Iso8583ParseException.class);
    }
```

**EDGE CASES:** Truncated data and null data both throw `Iso8583ParseException`. No `NullPointerException` leaks out.

### Test 5: LLVAR Roundtrip (Variable-Length Fields)

```java
    @Test
    @DisplayName("parse - roundtrip should preserve all field values with LLVAR fields")
    void parse_Roundtrip_WithLlvarFields() {
        Iso8583Message original = builder
                .setMti("0100")
                .setPan("4111111111111111")
                .setAmount("000000050000")
                .setTraceNumber("999999")
                .build();

        byte[] encoded = encodeMessage(original);
        Iso8583Message parsed = parser.parse(encoded);

        assertThat(parsed.getMti()).isEqualTo("0100");
        assertThat(parsed.getField(Iso8583Constants.FIELD_PAN)).isEqualTo("4111111111111111");
        assertThat(parsed.getField(Iso8583Constants.FIELD_AMOUNT)).isEqualTo("000000050000");
        assertThat(parsed.getField(Iso8583Constants.FIELD_TRACE)).isEqualTo("999999");
    }
```

**SPECIFICALLY TESTS LLVAR (Field 2 = PAN).** Test 1 had only NUMERIC/ALPHA fields. This test adds PAN to verify the variable-length encoding/decoding works correctly.

### The `encodeMessage` Helper

```java
    /**
     * Helper method to encode an Iso8583Message to bytes using the field definitions.
     * Format: [MTI 4 bytes] [Bitmap 8 bytes] [Data fields...]
     */
    private byte[] encodeMessage(Iso8583Message message) {
        Map<Integer, Iso8583Field> fieldDefs = Iso8583Constants.getFieldDefinitions();
        ByteBuffer buffer = ByteBuffer.allocate(1024);

        // Write MTI (4 bytes ASCII)
        buffer.put(message.getMti().getBytes(StandardCharsets.US_ASCII));

        // Write Bitmap (8 bytes)
        buffer.put(message.getBitmap());

        // Write data fields in order
        for (int fieldNum = 2; fieldNum <= 64; fieldNum++) {
            if (message.hasField(fieldNum)) {
                Iso8583Field fieldDef = fieldDefs.get(fieldNum);
                if (fieldDef == null) continue;

                String value = message.getField(fieldNum);
                switch (fieldDef.type()) {
                    case NUMERIC, ALPHA -> {
                        // Fixed-length: pad to maxLength
                        String padded = String.format("%-" + fieldDef.maxLength() + "s", value);
                        buffer.put(padded.getBytes(StandardCharsets.US_ASCII));
                    }
                    case LLVAR -> {
                        // 2-digit length prefix + value
                        String lengthPrefix = String.format("%02d", value.length());
                        buffer.put(lengthPrefix.getBytes(StandardCharsets.US_ASCII));
                        buffer.put(value.getBytes(StandardCharsets.US_ASCII));
                    }
                    case LLLVAR -> {
                        // 3-digit length prefix + value
                        String lengthPrefix3 = String.format("%03d", value.length());
                        buffer.put(lengthPrefix3.getBytes(StandardCharsets.US_ASCII));
                        buffer.put(value.getBytes(StandardCharsets.US_ASCII));
                    }
                }
            }
        }

        // Return only the written portion
        byte[] result = new byte[buffer.position()];
        buffer.flip();
        buffer.get(result);
        return result;
    }
}
```

**THIS IS A TEST HELPER, NOT PRODUCTION CODE.** The real encoder is `Iso8583Encoder` (Part 9e). This helper exists in the test to enable roundtrip testing without depending on the Netty encoder.

**`ByteBuffer.allocate(1024)`** — allocate 1KB buffer (more than enough for any ISO 8583 message).

**`buffer.position()`** — how many bytes were actually written (the used portion of the 1024).

**`buffer.flip()`** — switches from write mode to read mode. After writing, position points to the end. `flip()` sets position=0 and limit=written_bytes, so `get()` reads from the start.

**THE ENCODING LOGIC:**
```
For each present field (in order 2→64):
  NUMERIC/ALPHA: pad value to maxLength → write
  LLVAR:         write 2-digit length → write value
  LLLVAR:        write 3-digit length → write value
```

---

## 8. The Build → Encode → Parse → Verify Roundtrip

```
┌──────────────────────────────────────────────────────────────────────────┐
│                     THE ROUNDTRIP — PROOF OF CORRECTNESS                  │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  1. BUILD (Builder)                                                      │
│     Iso8583MessageBuilder                                                │
│       .setMti("0100")                                                    │
│       .setPan("4111111111111111")                                        │
│       .setAmount("000000050000")                                         │
│       .setCurrencyCode("356")                                            │
│       .build()                                                           │
│     → Iso8583Message { mti="0100", fields={2:.., 4:.., 49:..}, bitmap }  │
│                                                                          │
│  2. ENCODE (Encoder or test helper)                                      │
│     MTI:    [0x30, 0x31, 0x30, 0x30]     "0100"                         │
│     Bitmap: [0x60, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01]           │
│     Field 2: [0x31, 0x36] + [4111111111111111]  (LLVAR: "16" + PAN)    │
│     Field 4: [000000050000]                      (NUMERIC: 12 bytes)    │
│     Field 49: [356]                              (NUMERIC: 3 bytes)     │
│     → byte[] (approximately 45 bytes total)                              │
│                                                                          │
│  3. SEND OVER TCP (simulated in test, real in production via Netty)      │
│     byte[] → wire → byte[]                                               │
│                                                                          │
│  4. PARSE (Parser)                                                       │
│     Read 4 bytes → "0100" → mti                                         │
│     Read 8 bytes → bitmap → check which fields present                  │
│     Field 2 present? → YES → read LLVAR → "4111111111111111"            │
│     Field 3 present? → NO → skip                                        │
│     Field 4 present? → YES → read NUMERIC(12) → "000000050000"         │
│     ...                                                                  │
│     Field 49 present? → YES → read NUMERIC(3) → "356"                  │
│     → Iso8583Message { mti="0100", fields={2:.., 4:.., 49:..} }        │
│                                                                          │
│  5. VERIFY                                                               │
│     parsed.getMti() == "0100" ✓                                         │
│     parsed.getField(2) == "4111111111111111" ✓                          │
│     parsed.getField(4) == "000000050000" ✓                              │
│     parsed.getField(49) == "356" ✓                                      │
│     → ALL MATCH → Protocol implementation is CORRECT ✓                  │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 9. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **Iso8583Message as POJO** | Plain data container — Map<Integer, String> for sparse field storage |
| 2 | **Defensive copies** | `new HashMap<>(fields)` and `bitmap.clone()` prevent external modification |
| 3 | **setField auto-updates bitmap** | Adding a field automatically sets the corresponding bit |
| 4 | **toString omits values** | Only prints field NUMBERS (not PAN/amounts) for security |
| 5 | **Builder Pattern** | Step-by-step construction with validation at each step |
| 6 | **Method chaining** | `return this;` enables `builder.setMti().setPan().build()` |
| 7 | **Convenience methods** | `.setPan()` is more readable than `.setField(2, ...)` |
| 8 | **IllegalState vs IllegalArgument** | State error = "forgot a step", Argument error = "bad input" |
| 9 | **ByteBuffer** | Sequential byte reading: `wrap()` → `get()` advances position automatically |
| 10 | **Fixed vs variable field parsing** | NUMERIC/ALPHA: read N bytes. LLVAR: read 2-byte length, then N bytes |
| 11 | **Nested exception class** | `Iso8583MessageParser.Iso8583ParseException` — exception lives with thrower |
| 12 | **RuntimeException choice** | Parse errors are unchecked — caller handles them but isn't forced to |
| 13 | **Roundtrip testing** | Build → Encode → Parse → Verify. If all fields match, implementation is correct |
| 14 | **AssertJ fluent assertions** | `assertThat(x).isEqualTo(y)` — more readable than JUnit's `assertEquals` |
| 15 | **`assertThatThrownBy`** | Test that specific exceptions are thrown with specific messages |
| 16 | **Test helper vs production code** | `encodeMessage()` in test is NOT the production encoder (that's Iso8583Encoder) |
| 17 | **ByteBuffer.flip()** | Switches from write mode to read mode — essential for reading what you wrote |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part09-routing-service-overview.md) | Routing Service Overview |
| [Part 9a](./phase4-part09a-routing-project-setup.md) | Project Setup |
| [Part 9b](./phase4-part09b-iso8583-foundation.md) | ISO 8583 Foundation |
| **Part 9c** | **ISO 8583 Messages + Tests** (You are here) |
| [Part 9d](./phase4-part09d-netty-config-client.md) | Netty Config + Client |
| [Part 9e](./phase4-part09e-netty-pipeline.md) | Netty Pipeline |
| [Part 9f](./phase4-part09f-fraud-rule-engine.md) | Fraud Rule Engine |
| [Part 9g](./phase4-part09g-fraud-ml-service.md) | Fraud ML + Service + Tests |
| [Part 9h](./phase4-part09h-smart-routing.md) | Smart Routing + Tests |
| [Part 9i](./phase4-part09i-config-classes.md) | Config Classes (DynamoDB, Resilience4j) |
| [Part 9j](./phase4-part09j-controller-docker.md) | Controller + DTOs + Docker + curl |
| [Part 9k](./phase4-part09k-connections-flows.md) | How Everything Connects |

---

*Next: [Part 9d — Netty Config + Client (NettyConfig, BankNettyClient)](./phase4-part09d-netty-config-client.md) →*
