# Phase 4 Part 9a: Routing Service — ISO 8583 Protocol Implementation

| Field | Details |
|-------|---------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Hands-On Coding |
| **Part** | 9a of 16 (Routing Service — ISO 8583) |
| **Previous** | [Phase 4 Part 8c: Payment Controllers](./phase4-part08c-payment-controllers.md) |
| **Next** | [Phase 4 Part 9b: Routing Service — Netty TCP](./phase4-part09b-routing-netty.md) |
| **Time to Complete** | 3-4 hours |
| **Difficulty** | Advanced |
| **Prerequisites** | Understanding of binary data, Part 8c completed |
| **What You'll Build** | ISO 8583 message model, builder, parser, bitmap utilities, field definitions |
| **Git Commit** | "Phase 4 Part 9a: Implement ISO 8583 protocol (message builder, parser, bitmap)" |

---

## Table of Contents
- [What is ISO 8583?](#what-is-iso8583)
- [Step 1: Iso8583Field — Field Definition](#field-definition)
- [Step 2: BitmapUtils — The Heart of ISO 8583](#bitmap-utils)
- [Step 3: Iso8583Constants — MTI Codes & Field Numbers](#constants)
- [Step 4: Iso8583Message — The Message Model](#message-model)
- [Step 5: Iso8583MessageBuilder — Fluent Construction](#builder)
- [Step 6: Iso8583MessageParser — Decoding Binary](#parser)
- [Step 7: Unit Tests — Verify Roundtrip](#tests)
- [Verification](#verification)
- [What You Learned](#what-you-learned)
- [Common Errors & Fixes](#common-errors)
- [Git Commit](#git-commit)

---

## What You'll Learn in This Part
- What ISO 8583 is and why banks still use it (since 1987!)
- How bitmaps work (a 64-bit number that tells you which fields are present)
- How to build and parse binary protocol messages in Java
- The Builder pattern for constructing complex objects
- Bit manipulation in Java (shift, AND, OR operations)

---

<a name="what-is-iso8583"></a>
## What is ISO 8583? — Explained From Zero

### The Simple Explanation

When you swipe your card at a shop, the card machine doesn't send a JSON request to the bank. It sends a **binary message** in a format called **ISO 8583**. This format was invented by Visa in 1987 and is still used by EVERY bank and card network in the world.

**Why binary and not JSON?**

| | JSON | ISO 8583 |
|---|------|----------|
| Amount ₹500.00 | `{"amount": "50000"}` → 20 bytes | Field 4: `000000050000` → 6 bytes (packed) |
| Human-readable? | Yes | No (raw bytes) |
| Parse speed | Slower (text parsing) | Faster (fixed positions) |
| Size | Larger | 3-5x smaller |
| Used since | 2000s | 1987 |

At **1000 transactions per second**, those extra bytes and parse time add up. Banks chose efficiency over readability.

### Message Structure (3 Parts)

Every ISO 8583 message has exactly 3 parts:

```
┌──────────────────────────────────────────────────────────────────────┐
│                     ISO 8583 MESSAGE STRUCTURE                         │
├───────────────┬────────────────────┬─────────────────────────────────┤
│     MTI       │      BITMAP        │          DATA FIELDS             │
│   (4 bytes)   │    (8 bytes)       │      (variable length)          │
│               │                    │                                  │
│   "0100"      │  64 bits that      │  Actual field values:            │
│   = auth      │  tell which        │  card number, amount,            │
│   request     │  fields are        │  merchant, time, etc.            │
│               │  present           │                                  │
├───────────────┼────────────────────┼─────────────────────────────────┤
│  Always same  │  Like a checklist: │  Only fields marked "present"    │
│  length       │  "field 2? YES"    │  in the bitmap appear here       │
│               │  "field 3? YES"    │                                  │
│               │  "field 5? NO"     │                                  │
│               │  "field 11? YES"   │                                  │
└───────────────┴────────────────────┴─────────────────────────────────┘
```

### How the Bitmap Works (The Key Insight)

The bitmap is 8 bytes = 64 bits. Each bit corresponds to a field number:

```
Byte 0:  [Bit1][Bit2][Bit3][Bit4][Bit5][Bit6][Bit7][Bit8]
Byte 1:  [Bit9][Bit10][Bit11][Bit12][Bit13][Bit14][Bit15][Bit16]
...
Byte 7:  [Bit57][Bit58][Bit59][Bit60][Bit61][Bit62][Bit63][Bit64]

If Bit 2 = 1 → Field 2 (PAN/card number) IS in this message
If Bit 2 = 0 → Field 2 (PAN/card number) is NOT in this message
```

**Example:** An authorization request with fields 2, 3, 4, 11, 12, 41, 49:

```
Fields present: 2, 3, 4, 11, 12, 41, 49

Bitmap (binary):
  Bit positions: 1  2  3  4  5  6  7  8 | 9 10 11 12 13 14 15 16 | ...
  Values:        0  1  1  1  0  0  0  0 | 0  0  1  1  0  0  0  0 | ...
                    ✓  ✓  ✓                    ✓  ✓

  Byte 0 = 01110000 = 0x70
  Byte 1 = 00110000 = 0x30
  ... (remaining bytes have bits for fields 41 and 49)
```

The receiver reads the bitmap first, then knows exactly which fields to expect in the data section and in what order.

---

<a name="field-definition"></a>
## Step 1: Iso8583Field — Field Definition

Each ISO 8583 field has a defined type and maximum length. This record describes the structure:

**File:** `backend/routing-service/src/main/java/com/payflow/routing/iso8583/Iso8583Field.java`

```java
package com.payflow.routing.iso8583;

/**
 * Defines the structure of one ISO 8583 field.
 *
 * Java Records (Java 16+):
 * A record is an immutable data class. Java automatically generates:
 * - Constructor with all fields
 * - Getter methods: number(), name(), type(), maxLength()
 * - equals(), hashCode(), toString()
 *
 * It's like @Data in Lombok but built into the language.
 */
public record Iso8583Field(
        int number,
        // The field number (2 = PAN, 4 = Amount, etc.)

        String name,
        // Human-readable name for debugging ("Primary Account Number")

        FieldType type,
        // How this field is encoded (see below)

        int maxLength
        // Maximum allowed length for this field
) {

    /**
     * Field encoding types:
     *
     * NUMERIC: Fixed-length, digits only (padded with zeros on the left)
     *   Example: Amount (field 4) = "000000050000" (always 12 digits)
     *
     * ALPHA: Fixed-length, alphanumeric (padded with spaces on the right)
     *   Example: Terminal ID (field 41) = "TERM0001" (always 8 chars)
     *
     * LLVAR: Variable-length with 2-digit length prefix
     *   Example: PAN (field 2) = "16" + "4111111111111111"
     *   The "16" says "the next 16 characters are the card number"
     *
     * LLLVAR: Variable-length with 3-digit length prefix
     *   Example: Merchant Name (field 43) = "025" + "AMAZON INDIA BANGALORE"
     *   The "025" says "the next 25 characters are the merchant name"
     */
    public enum FieldType {
        NUMERIC,   // Fixed length, zero-padded
        ALPHA,     // Fixed length, space-padded
        LLVAR,     // Variable, 2-digit length prefix (LL = Length Length = 2)
        LLLVAR     // Variable, 3-digit length prefix
    }
}
```

**Why different types?**
- Fixed-length fields (NUMERIC, ALPHA) are faster to parse — you know exactly how many bytes to read.
- Variable-length fields (LLVAR, LLLVAR) save space — a 16-digit card number doesn't waste space when the field allows up to 19.

---

<a name="bitmap-utils"></a>
## Step 2: BitmapUtils — Bit Manipulation for the Bitmap

This is the most "computer science" code in the project. It does **bitwise operations** to set and check individual bits in the 8-byte bitmap.

**File:** `backend/routing-service/src/main/java/com/payflow/routing/iso8583/BitmapUtils.java`

```java
package com.payflow.routing.iso8583;

/**
 * Utility class for ISO 8583 64-bit primary bitmap operations.
 *
 * The bitmap is 8 bytes (64 bits). Bit N indicates field N is present.
 *
 * Memory layout:
 *   bitmap[0] → bits 1-8   (fields 1-8)
 *   bitmap[1] → bits 9-16  (fields 9-16)
 *   bitmap[2] → bits 17-24 (fields 17-24)
 *   ...
 *   bitmap[7] → bits 57-64 (fields 57-64)
 *
 * Within each byte, bit 7 (leftmost) is the highest field number for that byte.
 * Example: bitmap[0], bit 7 (MSB) = field 1, bit 6 = field 2, ..., bit 0 = field 8
 */
public final class BitmapUtils {

    private BitmapUtils() {
    }

    /**
     * Check if a field is present in the bitmap.
     *
     * How it works:
     *   1. Find which byte the field is in: (fieldNumber - 1) / 8
     *      Field 2 → byte 0, Field 11 → byte 1, Field 41 → byte 5
     *
     *   2. Find which bit within that byte: 7 - ((fieldNumber - 1) % 8)
     *      Field 2 → bit 6 of byte 0
     *      Field 11 → bit 5 of byte 1
     *
     *   3. Check if that bit is 1:
     *      (bitmap[byteIndex] & (1 << bitIndex)) != 0
     *
     * The expression (1 << bitIndex) creates a "mask" with only that bit set:
     *   bitIndex=6 → 01000000
     *   bitIndex=5 → 00100000
     *
     * Then AND (&) with the bitmap byte:
     *   If the bit was 1: result != 0 → true (field is present)
     *   If the bit was 0: result == 0 → false (field not present)
     */
    public static boolean isFieldPresent(byte[] bitmap, int fieldNumber) {
        if (fieldNumber < 1 || fieldNumber > 64) {
            throw new IllegalArgumentException("Field number must be between 1 and 64");
        }
        int byteIndex = (fieldNumber - 1) / 8;
        int bitIndex = 7 - ((fieldNumber - 1) % 8);
        return (bitmap[byteIndex] & (1 << bitIndex)) != 0;
    }

    /**
     * Mark a field as present in the bitmap.
     *
     * Uses OR (|=) to set a bit to 1 without affecting other bits:
     *   bitmap[byteIndex] |= (1 << bitIndex)
     *
     * Example: Set field 4 present
     *   byteIndex = (4-1)/8 = 0
     *   bitIndex = 7 - ((4-1) % 8) = 7 - 3 = 4
     *   mask = 1 << 4 = 00010000
     *   bitmap[0] |= 00010000
     *   → bit 4 is now 1, all other bits unchanged
     */
    public static void setFieldPresent(byte[] bitmap, int fieldNumber) {
        if (fieldNumber < 1 || fieldNumber > 64) {
            throw new IllegalArgumentException("Field number must be between 1 and 64");
        }
        int byteIndex = (fieldNumber - 1) / 8;
        int bitIndex = 7 - ((fieldNumber - 1) % 8);
        bitmap[byteIndex] |= (byte) (1 << bitIndex);
    }

    /**
     * Remove a field from the bitmap (clear the bit to 0).
     *
     * Uses AND with inverted mask (~) to clear a specific bit:
     *   bitmap[byteIndex] &= ~(1 << bitIndex)
     *
     * Example: Clear field 4
     *   mask = 1 << 4 = 00010000
     *   ~mask = 11101111
     *   bitmap[0] &= 11101111
     *   → bit 4 is now 0, all other bits unchanged
     */
    public static void clearField(byte[] bitmap, int fieldNumber) {
        if (fieldNumber < 1 || fieldNumber > 64) {
            throw new IllegalArgumentException("Field number must be between 1 and 64");
        }
        int byteIndex = (fieldNumber - 1) / 8;
        int bitIndex = 7 - ((fieldNumber - 1) % 8);
        bitmap[byteIndex] &= (byte) ~(1 << bitIndex);
    }

    /** Create a fresh 64-bit bitmap (all fields absent). */
    public static byte[] createEmptyBitmap() {
        return new byte[8];
    }

    /** Convert bitmap to hex string for debugging: "7030000000000100" */
    public static String toHexString(byte[] bitmap) {
        StringBuilder sb = new StringBuilder(16);
        for (byte b : bitmap) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /** Parse hex string back to bitmap bytes. */
    public static byte[] fromHexString(String hex) {
        if (hex == null || hex.length() != 16) {
            throw new IllegalArgumentException("Hex string must be exactly 16 characters");
        }
        byte[] bitmap = new byte[8];
        for (int i = 0; i < 8; i++) {
            bitmap[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bitmap;
    }

    /** Count how many fields are present in the bitmap. */
    public static int countFields(byte[] bitmap) {
        int count = 0;
        for (int i = 1; i <= 64; i++) {
            if (isFieldPresent(bitmap, i)) {
                count++;
            }
        }
        return count;
    }
}
```

### Bitmap Visual Example

Let's trace through setting field 2 (PAN) as present:

```
Field number: 2
byteIndex = (2-1) / 8 = 0     → bitmap[0]
bitIndex = 7 - ((2-1) % 8) = 7 - 1 = 6

Before: bitmap[0] = 00000000
Mask: 1 << 6 = 01000000
After OR: bitmap[0] = 01000000  ← bit 6 is now 1 (field 2 present!)

Now set field 4 (Amount):
byteIndex = (4-1) / 8 = 0     → bitmap[0]
bitIndex = 7 - ((4-1) % 8) = 7 - 3 = 4

Before: bitmap[0] = 01000000
Mask: 1 << 4 = 00010000
After OR: bitmap[0] = 01010000  ← bits 6 AND 4 are set (fields 2 and 4)
```

---

<a name="constants"></a>
## Step 3: Iso8583Constants — MTI Codes & Field Numbers

**File:** `backend/routing-service/src/main/java/com/payflow/routing/iso8583/Iso8583Constants.java`

This class defines all the "vocabulary" of ISO 8583:

```java
package com.payflow.routing.iso8583;

import java.util.HashMap;
import java.util.Map;

public final class Iso8583Constants {

    private Iso8583Constants() {}

    // ═══════════════════ MTI Codes ═══════════════════
    // MTI = "What kind of message is this?"
    // First 2 digits = version (0 = 1987 spec)
    // Digit 3 = message class (1 = auth, 2 = financial, 4 = reversal)
    // Digit 4 = function (0 = request, 10 = response)

    public static final String MTI_AUTH_REQUEST = "0100";
    // "0100" → 01=version, 0=auth class, 0=request
    // "Hey bank, can this customer pay ₹500?"

    public static final String MTI_AUTH_RESPONSE = "0110";
    // "0110" → response to auth request
    // "Yes approved!" or "No, declined"

    public static final String MTI_FINANCIAL_REQUEST = "0200";
    // "0200" → financial transaction (purchase that includes capture)
    // "Charge ₹500 to this card NOW"

    public static final String MTI_FINANCIAL_RESPONSE = "0210";
    // Response to financial request

    public static final String MTI_REVERSAL_REQUEST = "0420";
    // "Cancel the previous transaction"
    // Used for voids and timeouts (couldn't confirm → cancel to be safe)

    public static final String MTI_REVERSAL_RESPONSE = "0430";

    // ═══════════════════ Field Numbers ═══════════════════
    // These are standardized by ISO — same numbers worldwide

    public static final int FIELD_PAN = 2;              // Card number
    public static final int FIELD_PROCESSING_CODE = 3;  // Transaction type
    public static final int FIELD_AMOUNT = 4;           // Amount in minor units
    public static final int FIELD_TRACE = 11;           // Unique trace number
    public static final int FIELD_TIME = 12;            // Transaction time
    public static final int FIELD_AUTH_CODE = 38;       // Bank's auth code
    public static final int FIELD_RESPONSE_CODE = 39;   // Result code
    public static final int FIELD_TERMINAL_ID = 41;     // POS terminal ID
    public static final int FIELD_MERCHANT_NAME = 43;   // Merchant info
    public static final int FIELD_CURRENCY_CODE = 49;   // Currency (356=INR)

    // ═══════════════════ Response Codes ═══════════════════
    // The bank puts this in field 39 to tell us the result

    public static final String RESPONSE_APPROVED = "00";         // ✅ Success!
    public static final String RESPONSE_DECLINED = "05";         // ❌ Bank says no
    public static final String RESPONSE_INSUFFICIENT_FUNDS = "51"; // ❌ No money
    public static final String RESPONSE_EXPIRED_CARD = "54";     // ❌ Card expired
    public static final String RESPONSE_SUSPECTED_FRAUD = "59";  // ❌ Fraud flag
    public static final String RESPONSE_SYSTEM_ERROR = "96";     // ❌ Bank system down

    // ═══════════════════ Processing Codes ═══════════════════
    public static final String PROC_CODE_PURCHASE = "000000";
    public static final String PROC_CODE_CASH_ADVANCE = "010000";
    public static final String PROC_CODE_REFUND = "200000";

    /**
     * Returns field definitions map (used by parser and encoder).
     * Maps field number → definition (type, max length).
     */
    public static Map<Integer, Iso8583Field> getFieldDefinitions() {
        Map<Integer, Iso8583Field> defs = new HashMap<>();

        defs.put(FIELD_PAN, new Iso8583Field(
                FIELD_PAN, "Primary Account Number",
                Iso8583Field.FieldType.LLVAR, 19));
        // LLVAR: variable-length, 2-digit prefix
        // Card numbers are 13-19 digits (Visa=16, Amex=15)

        defs.put(FIELD_PROCESSING_CODE, new Iso8583Field(
                FIELD_PROCESSING_CODE, "Processing Code",
                Iso8583Field.FieldType.NUMERIC, 6));
        // Always 6 digits: "000000" = purchase

        defs.put(FIELD_AMOUNT, new Iso8583Field(
                FIELD_AMOUNT, "Transaction Amount",
                Iso8583Field.FieldType.NUMERIC, 12));
        // Always 12 digits, in MINOR units (paise/cents)
        // ₹500.00 → "000000050000" (50000 paise)

        defs.put(FIELD_TRACE, new Iso8583Field(
                FIELD_TRACE, "System Trace",
                Iso8583Field.FieldType.NUMERIC, 6));
        // Unique per transaction, helps bank match request/response

        defs.put(FIELD_TIME, new Iso8583Field(
                FIELD_TIME, "Transaction Time",
                Iso8583Field.FieldType.NUMERIC, 6));
        // Format: hhmmss (e.g., "143025" = 2:30:25 PM)

        defs.put(FIELD_AUTH_CODE, new Iso8583Field(
                FIELD_AUTH_CODE, "Authorization Code",
                Iso8583Field.FieldType.ALPHA, 6));
        // Bank's approval code (e.g., "A12345")
        // Only present in RESPONSE messages

        defs.put(FIELD_RESPONSE_CODE, new Iso8583Field(
                FIELD_RESPONSE_CODE, "Response Code",
                Iso8583Field.FieldType.ALPHA, 2));
        // "00" = approved, "05" = declined, "51" = insufficient funds

        defs.put(FIELD_TERMINAL_ID, new Iso8583Field(
                FIELD_TERMINAL_ID, "Terminal ID",
                Iso8583Field.FieldType.ALPHA, 8));
        // Identifies which terminal/system sent the request

        defs.put(FIELD_MERCHANT_NAME, new Iso8583Field(
                FIELD_MERCHANT_NAME, "Merchant Name",
                Iso8583Field.FieldType.LLLVAR, 40));
        // LLLVAR: 3-digit length prefix (up to 40 chars)

        defs.put(FIELD_CURRENCY_CODE, new Iso8583Field(
                FIELD_CURRENCY_CODE, "Currency Code",
                Iso8583Field.FieldType.NUMERIC, 3));
        // ISO 4217: "356" = INR, "840" = USD, "978" = EUR

        return defs;
    }
}
```

---

<a name="message-model"></a>
## Step 4: Iso8583Message — The Message Model

**File:** `backend/routing-service/src/main/java/com/payflow/routing/iso8583/Iso8583Message.java`

```java
package com.payflow.routing.iso8583;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a complete ISO 8583 message.
 *
 * This is a plain model class (POJO) — it just holds data.
 * The Builder creates it, the Parser populates it, the Encoder serializes it.
 */
public class Iso8583Message {

    private String mti;
    // Message Type Indicator: "0100", "0110", "0200", etc.

    private Map<Integer, String> fields;
    // Field number → value mapping
    // Example: {2: "4111111111111111", 4: "000000050000", 11: "123456"}

    private byte[] bitmap;
    // 8-byte bitmap indicating which fields are present

    public Iso8583Message() {
        this.fields = new HashMap<>();
        this.bitmap = new byte[8];
    }

    public Iso8583Message(String mti, Map<Integer, String> fields, byte[] bitmap) {
        this.mti = mti;
        this.fields = fields != null ? new HashMap<>(fields) : new HashMap<>();
        this.bitmap = bitmap != null ? bitmap.clone() : new byte[8];
        // .clone() — defensive copy so external modification doesn't affect us
    }

    // ─── Getters & Setters ───

    public String getMti() { return mti; }
    public void setMti(String mti) { this.mti = mti; }

    public String getField(int fieldNumber) { return fields.get(fieldNumber); }

    public void setField(int fieldNumber, String value) {
        fields.put(fieldNumber, value);
        BitmapUtils.setFieldPresent(bitmap, fieldNumber);
        // When you add a field, automatically mark it in the bitmap
    }

    public boolean hasField(int fieldNumber) { return fields.containsKey(fieldNumber); }

    public byte[] getBitmap() { return bitmap != null ? bitmap.clone() : null; }
    public void setBitmap(byte[] bitmap) { this.bitmap = bitmap != null ? bitmap.clone() : new byte[8]; }

    public Map<Integer, String> getFields() { return fields; }

    @Override
    public String toString() {
        return "Iso8583Message{mti='" + mti + "', fields=" + fields.keySet() + '}';
    }
}
```

---

<a name="builder"></a>
## Step 5: Iso8583MessageBuilder — Fluent Construction

The Builder pattern lets you construct messages step by step with a readable chain:

**File:** `backend/routing-service/src/main/java/com/payflow/routing/iso8583/Iso8583MessageBuilder.java`

```java
// Usage example (beautiful fluent API):
Iso8583Message authRequest = new Iso8583MessageBuilder()
        .setMti("0100")                           // Authorization request
        .setPan("4111111111111111")                // Card number
        .setProcessingCode("000000")              // Purchase
        .setAmount("000000050000")                // ₹500.00 (in paise)
        .setTraceNumber("123456")                 // Unique trace
        .setTime("143025")                        // 2:30:25 PM
        .setTerminalId("TERM0001")                // Our terminal ID
        .setCurrencyCode("356")                   // INR
        .build();

// This is the BUILDER PATTERN:
// Instead of: new Iso8583Message("0100", fields, bitmap) ← ugly, error-prone
// We use: builder.setMti().setPan().setAmount().build() ← readable, validated
```

The builder implementation validates inputs and manages the bitmap automatically:

```java
public Iso8583MessageBuilder setMti(String mti) {
    if (mti == null || mti.length() != 4) {
        throw new IllegalArgumentException("MTI must be a 4-digit string");
        // Catch errors early — don't wait until encoding to discover bad MTI
    }
    this.mti = mti;
    return this;  // Return 'this' for method chaining
}

public Iso8583MessageBuilder setField(int fieldNumber, String value) {
    if (fieldNumber < 2 || fieldNumber > 128) {
        throw new IllegalArgumentException("Field number must be between 2 and 128");
        // Field 1 is the secondary bitmap indicator — can't be set directly
    }
    fields.put(fieldNumber, value);
    BitmapUtils.setFieldPresent(bitmap, fieldNumber);
    // Automatically update bitmap when a field is set
    return this;
}

public Iso8583Message build() {
    if (mti == null) {
        throw new IllegalStateException("MTI must be set before building the message");
        // Fail fast: catch programming errors at build time
    }
    return new Iso8583Message(mti, fields, bitmap);
}
```

**Why use Builder pattern?**
1. **Validation at build time** — not at encoding time (earlier = easier to debug)
2. **Readability** — `.setPan("4111...")` is clearer than `fields.put(2, "4111...")`
3. **Immutability** — once built, the message doesn't change
4. **Convenience methods** — `.setPan()` instead of remembering field number 2

---

<a name="parser"></a>
## Step 6: Iso8583MessageParser — Decoding Binary Messages

The parser does the REVERSE of encoding: takes raw bytes → produces an Iso8583Message.

**File:** `backend/routing-service/src/main/java/com/payflow/routing/iso8583/Iso8583MessageParser.java`

```java
package com.payflow.routing.iso8583;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Parses raw byte arrays into Iso8583Message objects.
 *
 * Parse flow:
 * 1. Read first 4 bytes → MTI ("0110")
 * 2. Read next 8 bytes → bitmap (which fields are present)
 * 3. For each field marked present in bitmap (in order 2→64):
 *    - Look up field definition (NUMERIC? LLVAR? etc.)
 *    - Read the appropriate number of bytes
 *    - Store the value in the fields map
 */
public class Iso8583MessageParser {

    private static final int MTI_LENGTH = 4;
    private static final int BITMAP_LENGTH = 8;
    private static final int MIN_MESSAGE_LENGTH = MTI_LENGTH + BITMAP_LENGTH; // 12 bytes

    /**
     * Parse a byte array into an ISO 8583 message.
     *
     * @param data raw bytes (MTI + bitmap + field data)
     * @return parsed message
     * @throws Iso8583ParseException if data is invalid
     */
    public Iso8583Message parse(byte[] data) {
        if (data == null) {
            throw new Iso8583ParseException("Cannot parse null data");
        }
        if (data.length < MIN_MESSAGE_LENGTH) {
            throw new Iso8583ParseException(
                    "Message too short: insufficient data (need at least " +
                    MIN_MESSAGE_LENGTH + " bytes, got " + data.length + ")");
        }

        int offset = 0;

        // Step 1: Read MTI (4 bytes, ASCII)
        String mti = new String(data, offset, MTI_LENGTH, StandardCharsets.US_ASCII);
        offset += MTI_LENGTH;

        // Step 2: Read bitmap (8 bytes, binary)
        byte[] bitmap = new byte[BITMAP_LENGTH];
        System.arraycopy(data, offset, bitmap, 0, BITMAP_LENGTH);
        offset += BITMAP_LENGTH;

        // Step 3: Read data fields based on bitmap
        Iso8583Message message = new Iso8583Message();
        message.setMti(mti);
        message.setBitmap(bitmap);

        Map<Integer, Iso8583Field> fieldDefs = Iso8583Constants.getFieldDefinitions();

        // Iterate fields 2-64 (field 1 = secondary bitmap indicator, skip)
        for (int fieldNum = 2; fieldNum <= 64; fieldNum++) {
            if (!BitmapUtils.isFieldPresent(bitmap, fieldNum)) {
                continue; // This field not present → skip
            }

            Iso8583Field fieldDef = fieldDefs.get(fieldNum);
            if (fieldDef == null) {
                continue; // Unknown field definition → skip
            }

            // Read field value based on its type
            String value;
            switch (fieldDef.type()) {
                case NUMERIC -> {
                    // Fixed length: read exactly maxLength bytes
                    int len = fieldDef.maxLength();
                    value = new String(data, offset, len, StandardCharsets.US_ASCII).trim();
                    offset += len;
                }
                case ALPHA -> {
                    // Fixed length: read exactly maxLength bytes
                    int len = fieldDef.maxLength();
                    value = new String(data, offset, len, StandardCharsets.US_ASCII).trim();
                    offset += len;
                }
                case LLVAR -> {
                    // 2-digit length prefix, then variable data
                    String lenStr = new String(data, offset, 2, StandardCharsets.US_ASCII);
                    int len = Integer.parseInt(lenStr.trim());
                    offset += 2;
                    value = new String(data, offset, len, StandardCharsets.US_ASCII);
                    offset += len;
                }
                case LLLVAR -> {
                    // 3-digit length prefix, then variable data
                    String lenStr = new String(data, offset, 3, StandardCharsets.US_ASCII);
                    int len = Integer.parseInt(lenStr.trim());
                    offset += 3;
                    value = new String(data, offset, len, StandardCharsets.US_ASCII);
                    offset += len;
                }
                default -> throw new Iso8583ParseException(
                        "Unknown field type for field " + fieldNum);
            }

            message.setField(fieldNum, value);
        }

        return message;
    }

    /**
     * Custom exception for parse errors.
     */
    public static class Iso8583ParseException extends RuntimeException {
        public Iso8583ParseException(String message) {
            super(message);
        }
    }
}
```

---

<a name="tests"></a>
## Step 7: Unit Tests — Verify Roundtrip

The most important test: **build a message → encode it → parse it → verify all fields match**.

If this roundtrip works, our ISO 8583 implementation is correct.

```java
@Test
@DisplayName("parse - roundtrip: build → encode → parse → verify")
void parse_Roundtrip_AuthRequest() {
    // Build a message
    Iso8583Message original = new Iso8583MessageBuilder()
            .setMti("0100")
            .setProcessingCode("000000")
            .setAmount("000000010000")  // ₹100.00
            .setTraceNumber("654321")
            .setTime("153042")
            .setTerminalId("TERM0001")
            .setCurrencyCode("356")     // INR
            .build();

    // Encode to bytes
    byte[] encoded = encodeMessage(original);

    // Parse bytes back to message
    Iso8583Message parsed = parser.parse(encoded);

    // Verify all fields survived the roundtrip
    assertThat(parsed.getMti()).isEqualTo("0100");
    assertThat(parsed.getField(3)).isEqualTo("000000");
    assertThat(parsed.getField(4)).isEqualTo("000000010000");
    assertThat(parsed.getField(11)).isEqualTo("654321");
    assertThat(parsed.getField(12)).isEqualTo("153042");
    assertThat(parsed.getField(41)).isEqualTo("TERM0001");
    assertThat(parsed.getField(49)).isEqualTo("356");
}
```

### Running Tests

```powershell
cd backend
mvn test -pl routing-service -Dtest="Iso8583*"
# Expected: Tests run: X, Failures: 0
```

---

<a name="verification"></a>
## Verification

```powershell
cd backend
mvn clean compile -pl routing-service -am
# Expected: BUILD SUCCESS

mvn test -pl routing-service
# Expected: All tests pass (builder, parser, bitmap tests)
```

---

## What You Learned

| # | Concept | What You Practiced |
|---|---------|-------------------|
| 1 | ISO 8583 protocol | Understood message structure (MTI + bitmap + fields) |
| 2 | Binary protocols | Why binary beats JSON for performance-critical paths |
| 3 | Bitmap (bit manipulation) | Set, check, and clear individual bits in byte arrays |
| 4 | Builder pattern | Fluent API for constructing complex objects with validation |
| 5 | Java Records | Immutable data classes (Iso8583Field) |
| 6 | Bitwise operators | AND (&), OR (\|), NOT (~), shift (<<) |
| 7 | Field encoding types | Fixed (NUMERIC/ALPHA) vs variable (LLVAR/LLLVAR) |
| 8 | Roundtrip testing | Build → encode → parse → verify (proves correctness) |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `ArrayIndexOutOfBoundsException` in parser | Message shorter than expected | Check total message length before parsing |
| `NumberFormatException` in LLVAR | Length prefix contains non-digits | Ensure length prefix is zero-padded: "04" not " 4" |
| Bitmap shows wrong fields | Byte/bit index calculation off | Remember: field 1 = bit 7 of byte 0 (MSB first) |
| Builder allows field 0 or 1 | Missing validation | Validate: field numbers 2-128 only |
| `MTI must be 4-digit string` | Passing "100" instead of "0100" | Always use 4 characters with leading zero |
| Roundtrip test fails | Fixed-length field not padded correctly | NUMERIC: left-pad with zeros, ALPHA: right-pad with spaces |

---

## Git Commit

```bash
git add .
git commit -m "Phase 4 Part 9a: Implement ISO 8583 protocol

- Created Iso8583Field record (NUMERIC, ALPHA, LLVAR, LLLVAR types)
- Created BitmapUtils (set/check/clear bits in 64-bit bitmap)
- Created Iso8583Constants (MTI codes, field numbers, response codes)
- Created Iso8583Message (model: MTI + bitmap + fields map)
- Created Iso8583MessageBuilder (fluent API with validation)
- Created Iso8583MessageParser (decode binary → message object)
- Unit tests: builder validation, bitmap operations, roundtrip parsing
- All ISO 8583 tests pass"

git push origin main
```

---

## Document Index

| Phase | Part | Document | Status |
|-------|------|----------|--------|
| 4 | 8c | [Payment Controllers](./phase4-part08c-payment-controllers.md) | ✅ |
| 4 | **9a** | **[Routing — ISO 8583](./phase4-part09a-routing-iso8583.md)** | ← You are here |
| 4 | 9b | [Routing — Netty TCP](./phase4-part09b-routing-netty.md) | ⬜ Next |
| 4 | 9c | [Routing — Fraud & Smart Routing](./phase4-part09c-routing-fraud-smartrouting.md) | ⬜ |

---

## Next Steps

**What's coming in Part 9b (Netty TCP Client):**
- What is Netty? (non-blocking I/O, event loop model)
- Build a TCP client that sends ISO 8583 messages to the bank
- Channel pipeline: frame decoder → ISO 8583 decoder → handler → encoder
- CompletableFuture for async-to-sync bridge (send message, wait for response)
- Connection pooling and timeout handling

**Before moving on, verify:**
- [ ] `mvn test -pl routing-service` → all ISO 8583 tests pass
- [ ] You understand how the bitmap encodes field presence
- [ ] You can trace through building a message manually
- [ ] Git commit pushed

---
*End of Phase 4 Part 9a — Routing Service: ISO 8583 Protocol*
*Next: [Phase 4 Part 9b — Routing Service: Netty TCP Client](./phase4-part09b-routing-netty.md)*
