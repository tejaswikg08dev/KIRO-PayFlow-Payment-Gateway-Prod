# 🏗️ Phase 4 Part 9b: Routing Service — ISO 8583 Foundation

> **"A 64-bit bitmap tells you which fields are present. One bit per field. Zero means absent. One means present. That's it — and it's been working since 1987."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 9b — ISO 8583 Foundation |
| **What You Build** | Iso8583Field.java, BitmapUtils.java, Iso8583Constants.java |
| **Previous** | [Part 9a — Project Setup](./phase4-part09a-routing-project-setup.md) |
| **Next** | [Part 9c — ISO 8583 Messages](./phase4-part09c-iso8583-messages.md) |

---

## 📖 Table of Contents

1. [What Is ISO 8583? — From Zero](#1-what-is-iso-8583--from-zero)
2. [How an ISO 8583 Message Works](#2-how-an-iso-8583-message-works)
3. [Folder Structure After This Part](#3-folder-structure-after-this-part)
4. [Step-by-Step: Iso8583Field.java](#4-step-by-step-iso8583fieldjava)
5. [Step-by-Step: BitmapUtils.java](#5-step-by-step-bitmaputilsjava)
6. [Step-by-Step: Iso8583Constants.java](#6-step-by-step-iso8583constantsjava)
7. [How These 3 Files Connect](#7-how-these-3-files-connect)
8. [What You Learned](#8-what-you-learned)

---

## 1. What Is ISO 8583? — From Zero

When you swipe your card at a shop, the card machine doesn't send a JSON request to the bank. It sends a **binary message** in a format called **ISO 8583**. This format was invented by Visa in 1987 and is still used by EVERY bank and card network in the world.

### Why Binary and Not JSON?

| | JSON (what we've used so far) | ISO 8583 (what banks use) |
|---|---|---|
| Amount ₹500.00 | `{"amount": "50000"}` → 20 bytes | Field 4: `000000050000` → 6 bytes (packed) |
| Human-readable? | ✅ Yes | ❌ No (raw bytes) |
| Parse speed | Slower (text parsing) | Faster (fixed positions) |
| Size | Larger (headers + keys + values) | 3-5× smaller (no field names) |
| Used since | 2000s (REST APIs) | 1987 (Visa specification) |

At **1,000 transactions per second**, those extra bytes and parse time add up. Banks chose efficiency over readability 37 years ago, and the standard stuck.

### Who Uses ISO 8583?

Every card network and bank in the world:
- **Visa** — invented it
- **Mastercard** — adopted it
- **RuPay** — India's card network uses it
- **NPCI** — National Payments Corporation of India uses it for UPI backend
- **HDFC, SBI, ICICI** — all Indian banks speak ISO 8583

---

## 2. How an ISO 8583 Message Works

Every ISO 8583 message has exactly **3 parts**:

```
┌──────────────────────────────────────────────────────────────────────┐
│                     ISO 8583 MESSAGE STRUCTURE                       │
├───────────────┬────────────────────┬─────────────────────────────────┤
│     MTI       │      BITMAP        │          DATA FIELDS            │
│   (4 bytes)   │    (8 bytes)       │      (variable length)         │
│               │                    │                                 │
│   "0100"      │  64 bits that      │  Actual field values:           │
│   = auth      │  tell which        │  card number, amount,           │
│   request     │  fields are        │  merchant, time, etc.           │
│               │  present           │                                 │
├───────────────┼────────────────────┼─────────────────────────────────┤
│  Always 4     │  Like a checklist: │  Only fields marked "present"   │
│  bytes        │  "field 2? YES"    │  in the bitmap appear here      │
│               │  "field 3? YES"    │                                 │
│               │  "field 5? NO"     │                                 │
└───────────────┴────────────────────┴─────────────────────────────────┘
```

### Part 1: MTI (Message Type Indicator) — 4 Bytes

The MTI is a 4-digit code that tells the receiver "what kind of message is this?"

```
MTI format: XYZW
  X = ISO version (0 = 1987 spec)
  Y = Message class (1 = auth, 2 = financial, 4 = reversal)
  Z = Message function (0 = request)
  W = Message origin (0 = response to Z)

Examples:
  "0100" → Authorization Request ("Can this customer pay ₹500?")
  "0110" → Authorization Response ("Yes, approved / No, declined")
  "0200" → Financial Request ("Charge ₹500 to this card NOW")
  "0210" → Financial Response
  "0420" → Reversal Request ("Cancel the previous transaction")
  "0430" → Reversal Response
```

### Part 2: Bitmap — 8 Bytes (64 bits)

The bitmap is the **most clever part**. It's 8 bytes = 64 bits. Each bit represents one field:

```
Byte 0:  [Bit1][Bit2][Bit3][Bit4][Bit5][Bit6][Bit7][Bit8]
Byte 1:  [Bit9][Bit10][Bit11][Bit12][Bit13][Bit14][Bit15][Bit16]
...
Byte 7:  [Bit57][Bit58][Bit59][Bit60][Bit61][Bit62][Bit63][Bit64]

If Bit 2 = 1 → Field 2 (card number) IS in this message
If Bit 2 = 0 → Field 2 is NOT in this message
```

**WHY A BITMAP?** A message doesn't always have all 64 fields. An authorization request might have fields 2, 3, 4, 11, 12, 49. Instead of sending empty placeholders for the other 58 fields, the bitmap says "only these 6 fields are present."

### Part 3: Data Fields — Variable Length

Each present field is encoded in order. The encoding depends on the field type:

| Type | How It Works | Example |
|---|---|---|
| **NUMERIC** | Fixed-length, left-padded with zeros | Amount: `000000050000` (always 12 digits) |
| **ALPHA** | Fixed-length, right-padded with spaces | Terminal: `TERM0001` (always 8 chars) |
| **LLVAR** | 2-digit length prefix + variable data | PAN: `16` + `4111111111111111` |
| **LLLVAR** | 3-digit length prefix + variable data | Merchant: `025` + `AMAZON INDIA BANGALORE` |

---

## 3. Folder Structure After This Part

```
backend/routing-service/src/main/java/com/payflow/routing/
├── RoutingServiceApplication.java    ← from 9a
└── iso8583/                          ← YOU CREATE THIS FOLDER
    ├── Iso8583Field.java             ← YOU CREATE THIS
    ├── BitmapUtils.java              ← YOU CREATE THIS
    └── Iso8583Constants.java         ← YOU CREATE THIS
```

---

## 4. Step-by-Step: Iso8583Field.java

**File:** `src/main/java/com/payflow/routing/iso8583/Iso8583Field.java`

This defines the **metadata** for each field — its number, name, type, and max length.

### Full Source Code

```java
package com.payflow.routing.iso8583;
```

```java
/**
 * Defines an ISO 8583 field with its metadata.
 *
 * @param number    Field number (1-128)
 * @param name      Human-readable field name
 * @param type      Data type (NUMERIC, ALPHA, LLVAR, LLLVAR)
 * @param maxLength Maximum length of the field value
 */
public record Iso8583Field(
        int number,
        String name,
        FieldType type,
        int maxLength
) {
```

**🆕 `record` — FIRST TIME IN PAYFLOW**

A Java `record` (Java 16+) is an **immutable data class**. Java automatically generates:
- Constructor with all fields
- Getter methods: `number()`, `name()`, `type()`, `maxLength()`
- `equals()`, `hashCode()`, `toString()`

**COMPARISON WITH WHAT YOU'VE SEEN BEFORE:**

| Approach | Example | Boilerplate |
|---|---|---|
| Regular class + Lombok `@Data` | `@Data public class Merchant { ... }` | Lombok generates getters/setters/etc at compile time |
| **Java Record** | `public record Iso8583Field(int number, ...) {}` | **Java itself generates getters/equals/hashCode** |

**WHY RECORD HERE, NOT @Data?**
- Iso8583Field is **truly immutable** — once created, it never changes
- Records make immutability explicit at the language level
- Records don't have setters — you CAN'T accidentally modify them
- Records are simpler — no Lombok dependency needed for this class

**RECORDS vs @Data:**

| Feature | @Data (Lombok) | record (Java) |
|---|---|---|
| Mutable? | Yes (has setters) | No (no setters) |
| Inheritance? | Can extend classes | Cannot extend (implicitly final) |
| Builder? | Need @Builder | No builder (use constructor) |
| When to use | Entities, DTOs with setters | Truly immutable value objects |

```java
    /**
     * ISO 8583 field data types.
     */
    public enum FieldType {
        /** Fixed-length numeric field */
        NUMERIC,
        /** Fixed-length alphanumeric field */
        ALPHA,
        /** Variable-length field with 2-digit length prefix (LL) */
        LLVAR,
        /** Variable-length field with 3-digit length prefix (LLL) */
        LLLVAR
    }
```

**THE 4 FIELD ENCODING TYPES — explained with examples:**

```
NUMERIC (fixed length, zero-padded LEFT):
  Field 4 (Amount), maxLength=12
  Value: "50000" (₹500.00 in paise)
  Encoded: "000000050000"  ← padded to exactly 12 digits
  Decode: read exactly 12 bytes, trim leading zeros

ALPHA (fixed length, space-padded RIGHT):
  Field 41 (Terminal ID), maxLength=8
  Value: "TERM001"
  Encoded: "TERM001 "  ← padded to exactly 8 chars (trailing space)
  Decode: read exactly 8 bytes, trim trailing spaces

LLVAR (variable length, 2-digit length prefix):
  Field 2 (PAN), maxLength=19
  Value: "4111111111111111" (16 digits)
  Encoded: "164111111111111111"  ← "16" = length, then the actual value
  Decode: read 2 bytes → "16" → read next 16 bytes → PAN

LLLVAR (variable length, 3-digit length prefix):
  Field 43 (Merchant Name), maxLength=40
  Value: "AMAZON INDIA BANGALORE" (22 chars)
  Encoded: "022AMAZON INDIA BANGALORE"  ← "022" = length
  Decode: read 3 bytes → "022" → read next 22 bytes → merchant name
```

**WHY VARIABLE LENGTH FOR PAN (CARD NUMBER)?**
Card numbers vary: Visa = 16 digits, Amex = 15 digits, some Maestro = 13 digits.
LLVAR saves space: "15" + 15 digits vs forcing all to 19 digits (4 wasted bytes per message × millions of messages).

```java
    /**
     * Validates the field value against the field definition.
     */
    public boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        if (value.length() > maxLength) {
            return false;
        }
        if (type == FieldType.NUMERIC) {
            return value.chars().allMatch(Character::isDigit);
        }
        return true;
    }
```

**VALIDATION LOGIC:**
1. Null → invalid
2. Too long → invalid
3. NUMERIC but has non-digit characters → invalid
4. Everything else → valid

**`value.chars().allMatch(Character::isDigit)`** — Java Stream API on a String:
- `value.chars()` → IntStream of character codes
- `.allMatch(Character::isDigit)` → true only if EVERY character is 0-9

```java
    /**
     * Returns the byte length required to encode the field value.
     */
    public int getEncodedLength(String value) {
        if (value == null) return 0;
        return switch (type) {
            case NUMERIC, ALPHA -> maxLength;
            case LLVAR -> 2 + value.length();
            case LLLVAR -> 3 + value.length();
        };
    }
}
```

**WHY THIS METHOD EXISTS:** The encoder needs to know how many bytes each field will take in the output. Fixed-length fields always take `maxLength` bytes. Variable-length fields take `prefix + actual length`.

**`switch` expression (Java 14+)** — returns a value directly. No `break` needed:
```java
// Old style (statement):
int len;
switch (type) {
    case NUMERIC: len = maxLength; break;
    case LLVAR: len = 2 + value.length(); break;
    ...
}

// New style (expression — what we use):
int len = switch (type) {
    case NUMERIC, ALPHA -> maxLength;          // Arrow syntax, no break
    case LLVAR -> 2 + value.length();
    case LLLVAR -> 3 + value.length();
};
```

---

## 5. Step-by-Step: BitmapUtils.java

**File:** `src/main/java/com/payflow/routing/iso8583/BitmapUtils.java`

This is the **most "computer science" file** in the entire PayFlow project. It does bitwise operations to manipulate individual bits in the 8-byte bitmap.

### Full Source Code

```java
package com.payflow.routing.iso8583;

/**
 * Utility class for encoding and decoding ISO 8583 64-bit primary bitmaps.
 * <p>
 * The bitmap is stored as 8 bytes (64 bits), where each bit represents
 * the presence of a field (bit 1 = field 1, bit 2 = field 2, etc.).
 * Bit 1 indicates secondary bitmap presence.
 */
public final class BitmapUtils {

    private BitmapUtils() {
        // Utility class
    }
```

**`public final class`** — `final` prevents subclassing. This is a utility class with only static methods.

**Private constructor** — prevents instantiation. You call `BitmapUtils.isFieldPresent()`, not `new BitmapUtils().isFieldPresent()`. Same pattern as `java.util.Collections` or `java.lang.Math`.

### The Bitmap Memory Layout

Before we look at the code, understand the memory layout:

```
Bitmap = 8 bytes = 64 bits

bitmap[0] contains fields 1-8:
  Bit 7 (MSB) = Field 1
  Bit 6       = Field 2
  Bit 5       = Field 3
  Bit 4       = Field 4
  Bit 3       = Field 5
  Bit 2       = Field 6
  Bit 1       = Field 7
  Bit 0 (LSB) = Field 8

bitmap[1] contains fields 9-16:
  Bit 7 (MSB) = Field 9
  Bit 6       = Field 10
  ...etc

bitmap[7] contains fields 57-64
```

**KEY FORMULA:**
```
Given fieldNumber (1-64):
  byteIndex = (fieldNumber - 1) / 8      → which byte in the array
  bitIndex  = 7 - ((fieldNumber - 1) % 8) → which bit within that byte (MSB first)
```

### isFieldPresent — Check If a Field Exists

```java
    /**
     * Checks whether a specific field is present in the bitmap.
     *
     * @param bitmap     8-byte bitmap array
     * @param fieldNumber Field number (1-64)
     * @return true if the field is present
     */
    public static boolean isFieldPresent(byte[] bitmap, int fieldNumber) {
        if (fieldNumber < 1 || fieldNumber > 64) {
            throw new IllegalArgumentException("Field number must be between 1 and 64");
        }
        int byteIndex = (fieldNumber - 1) / 8;
        int bitIndex = 7 - ((fieldNumber - 1) % 8);
        return (bitmap[byteIndex] & (1 << bitIndex)) != 0;
    }
```

**LET'S TRACE THIS FOR FIELD 2 (PAN — card number):**

```
fieldNumber = 2

Step 1: byteIndex = (2 - 1) / 8 = 1 / 8 = 0    → bitmap[0]
Step 2: bitIndex = 7 - ((2 - 1) % 8) = 7 - 1 = 6 → bit 6

Step 3: Create a mask with only bit 6 set:
  1 << 6 = 01000000  (binary)

Step 4: AND the mask with bitmap[0]:
  If bitmap[0] = 01110000 (fields 2, 3, 4 are present)
  01110000
& 01000000  (mask for field 2)
  --------
  01000000  ← NOT zero → field 2 IS present → return true

  If bitmap[0] = 00110000 (fields 3, 4 are present, NOT field 2)
  00110000
& 01000000  (mask for field 2)
  --------
  00000000  ← IS zero → field 2 is NOT present → return false
```

**THE BITWISE AND (`&`) TRICK:** ANDing with a single-bit mask tells you if that specific bit is set. It's like asking "is the light switch for room 6 on?" by checking only that switch.

### setFieldPresent — Mark a Field As Present

```java
    /**
     * Sets a field as present in the bitmap.
     *
     * @param bitmap      8-byte bitmap array (modified in place)
     * @param fieldNumber Field number (1-64)
     */
    public static void setFieldPresent(byte[] bitmap, int fieldNumber) {
        if (fieldNumber < 1 || fieldNumber > 64) {
            throw new IllegalArgumentException("Field number must be between 1 and 64");
        }
        int byteIndex = (fieldNumber - 1) / 8;
        int bitIndex = 7 - ((fieldNumber - 1) % 8);
        bitmap[byteIndex] |= (byte) (1 << bitIndex);
    }
```

**TRACE FOR FIELD 4 (Amount):**

```
fieldNumber = 4

Step 1: byteIndex = (4 - 1) / 8 = 0    → bitmap[0]
Step 2: bitIndex = 7 - ((4 - 1) % 8) = 7 - 3 = 4 → bit 4

Step 3: Create mask:
  1 << 4 = 00010000

Step 4: OR (|=) with bitmap[0]:
  Before: bitmap[0] = 01000000  (field 2 was already set)
  01000000
| 00010000  (mask for field 4)
  --------
  01010000  ← bit 6 AND bit 4 are now set (fields 2 and 4)

  After: bitmap[0] = 01010000
```

**THE BITWISE OR (`|=`) TRICK:** ORing with a single-bit mask turns ON that bit without affecting others. It's like flipping on the light switch for room 4 without touching any other switches.

**`(byte)` CAST:** Java's `1 << bitIndex` produces an `int`. The `|=` operator on a `byte` needs a `byte`, so we cast.

### clearField — Remove a Field

```java
    /**
     * Clears a field from the bitmap.
     *
     * @param bitmap      8-byte bitmap array (modified in place)
     * @param fieldNumber Field number (1-64)
     */
    public static void clearField(byte[] bitmap, int fieldNumber) {
        if (fieldNumber < 1 || fieldNumber > 64) {
            throw new IllegalArgumentException("Field number must be between 1 and 64");
        }
        int byteIndex = (fieldNumber - 1) / 8;
        int bitIndex = 7 - ((fieldNumber - 1) % 8);
        bitmap[byteIndex] &= (byte) ~(1 << bitIndex);
    }
```

**TRACE FOR CLEARING FIELD 4:**

```
Step 1-2: Same as before → byteIndex=0, bitIndex=4

Step 3: Create mask and INVERT it:
  1 << 4 = 00010000
  ~(1 << 4) = 11101111  (invert all bits)

Step 4: AND (&=) with bitmap[0]:
  Before: bitmap[0] = 01010000  (fields 2 and 4 set)
  01010000
& 11101111  (inverted mask — everything except bit 4)
  --------
  01000000  ← bit 4 is now 0, bit 6 unchanged

  After: bitmap[0] = 01000000  (only field 2 remains)
```

**THREE BITWISE OPERATIONS SUMMARY:**

| Operation | Operator | Purpose | Analogy |
|---|---|---|---|
| **Check** if bit is set | `& mask` | Test one bit | "Is this switch ON?" |
| **Set** a bit to 1 | `\|= mask` | Turn on one bit | "Flip this switch ON" |
| **Clear** a bit to 0 | `&= ~mask` | Turn off one bit | "Flip this switch OFF" |

### Helper Methods

```java
    /**
     * Creates an empty 64-bit bitmap (all zeros).
     */
    public static byte[] createEmptyBitmap() {
        return new byte[8];
    }
```

All zeros = no fields present. `new byte[8]` in Java is already zero-initialized.

```java
    /**
     * Converts bitmap bytes to a hex string representation.
     *
     * @param bitmap 8-byte bitmap array
     * @return Hex string (16 characters)
     */
    public static String toHexString(byte[] bitmap) {
        StringBuilder sb = new StringBuilder(16);
        for (byte b : bitmap) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
```

**FOR DEBUGGING:** Converts `[0x70, 0x30, 0x00, ...]` → `"7030000000000000"`. Each byte becomes 2 hex characters.

**`%02X`:** `%X` = hex, `02` = always 2 digits (pad with zero). So byte `0x05` becomes `"05"`, not `"5"`.

```java
    /**
     * Parses a hex string into a bitmap byte array.
     *
     * @param hex 16-character hex string
     * @return 8-byte bitmap array
     */
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
```

**THE REVERSE of `toHexString`.** Takes `"7030000000000000"` → `[0x70, 0x30, 0x00, ...]`.

**`Integer.parseInt(hex, 16)`** — parse a hex string. `parseInt("70", 16)` → `112` decimal → `0x70`.

```java
    /**
     * Counts the number of fields present in the bitmap.
     */
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

Simple: check every field 1-64, count how many are present. Used for debugging and validation.

---

## 6. Step-by-Step: Iso8583Constants.java

**File:** `src/main/java/com/payflow/routing/iso8583/Iso8583Constants.java`

This is the **vocabulary** of ISO 8583 — all the codes, field numbers, and field definitions in one place.

### Full Source Code

```java
package com.payflow.routing.iso8583;

import java.util.HashMap;
import java.util.Map;

/**
 * ISO 8583 constants including MTI codes, field numbers, and field definitions.
 */
public final class Iso8583Constants {

    private Iso8583Constants() {
        // Utility class
    }
```

Same pattern as BitmapUtils — `final` class, private constructor, only static members.

### MTI Codes — "What Kind of Message?"

```java
    // ==================== MTI Codes ====================

    /** Authorization Request */
    public static final String MTI_AUTH_REQUEST = "0100";

    /** Authorization Response */
    public static final String MTI_AUTH_RESPONSE = "0110";

    /** Financial Transaction Request (Purchase) */
    public static final String MTI_FINANCIAL_REQUEST = "0200";

    /** Financial Transaction Response */
    public static final String MTI_FINANCIAL_RESPONSE = "0210";

    /** Reversal Request */
    public static final String MTI_REVERSAL_REQUEST = "0420";

    /** Reversal Response */
    public static final String MTI_REVERSAL_RESPONSE = "0430";
```

**REQUEST/RESPONSE PAIRS:**

| Request | MTI | Response | MTI | Used When |
|---|---|---|---|---|
| Authorization Request | `0100` | Authorization Response | `0110` | "Can this card pay ₹500?" / "Yes approved" |
| Financial Request | `0200` | Financial Response | `0210` | "Charge ₹500 NOW" (authorize + capture) |
| Reversal Request | `0420` | Reversal Response | `0430` | "Cancel the previous charge" |

**HOW TO READ MTI DIGITS:**
```
"0100" = version=0, class=1(auth), function=0(request), origin=0
"0110" = version=0, class=1(auth), function=1(response), origin=0
"0200" = version=0, class=2(financial), function=0(request), origin=0
"0420" = version=0, class=4(reversal), function=2(advice), origin=0

Pattern: Request MTI + 10 = Response MTI
  0100 + 10 = 0110 ✓
  0200 + 10 = 0210 ✓
  0420 + 10 = 0430 ✓
```

### Field Numbers — "What Data Goes Where?"

```java
    // ==================== Field Numbers ====================

    /** Primary Account Number (PAN) */
    public static final int FIELD_PAN = 2;

    /** Processing Code */
    public static final int FIELD_PROCESSING_CODE = 3;

    /** Transaction Amount */
    public static final int FIELD_AMOUNT = 4;

    /** System Trace Audit Number */
    public static final int FIELD_TRACE = 11;

    /** Transaction Time (hhmmss) */
    public static final int FIELD_TIME = 12;

    /** Authorization Code */
    public static final int FIELD_AUTH_CODE = 38;

    /** Response Code */
    public static final int FIELD_RESPONSE_CODE = 39;

    /** Terminal ID */
    public static final int FIELD_TERMINAL_ID = 41;

    /** Merchant Name / Location */
    public static final int FIELD_MERCHANT_NAME = 43;

    /** Currency Code */
    public static final int FIELD_CURRENCY_CODE = 49;
```

**FIELD NUMBER MAP — What Each Field Contains:**

| Field # | Name | In Request? | In Response? | Example Value |
|---|---|---|---|---|
| 2 | PAN (card number) | ✅ | ❌ | `"4111111111111111"` |
| 3 | Processing Code | ✅ | ✅ | `"000000"` (purchase) |
| 4 | Amount | ✅ | ✅ | `"000000150000"` (₹1,500.00) |
| 11 | Trace Number | ✅ | ✅ | `"654321"` (unique per txn) |
| 12 | Time | ✅ | ✅ | `"143025"` (2:30:25 PM) |
| 38 | Auth Code | ❌ | ✅ | `"A12345"` (bank approval code) |
| 39 | Response Code | ❌ | ✅ | `"00"` (approved) |
| 41 | Terminal ID | ✅ | ❌ | `"TERM0001"` |
| 43 | Merchant Name | ✅ | ❌ | `"AMAZON INDIA BANGALORE"` |
| 49 | Currency Code | ✅ | ✅ | `"356"` (INR) |

**WHY THESE SPECIFIC NUMBERS?** They're defined by the ISO 8583 standard. Field 2 is ALWAYS the card number, field 4 is ALWAYS the amount — across every bank and card network worldwide. You can't change them.

**FIELD 1 IS MISSING** — Field 1 is reserved for the "secondary bitmap indicator." If bit 1 is set, there's a second 8-byte bitmap for fields 65-128. We don't use fields 65+ so we skip it.

### Response Codes — "What Did the Bank Say?"

```java
    // ==================== Response Codes ====================

    /** Approved */
    public static final String RESPONSE_APPROVED = "00";

    /** Declined - Do Not Honor */
    public static final String RESPONSE_DECLINED = "05";

    /** Insufficient Funds */
    public static final String RESPONSE_INSUFFICIENT_FUNDS = "51";

    /** Expired Card */
    public static final String RESPONSE_EXPIRED_CARD = "54";

    /** Suspected Fraud */
    public static final String RESPONSE_SUSPECTED_FRAUD = "59";

    /** System Error */
    public static final String RESPONSE_SYSTEM_ERROR = "96";
```

**The bank puts this in Field 39.** The code tells us exactly what happened:

| Code | Meaning | What PayFlow Does |
|---|---|---|
| `"00"` | ✅ Approved | Payment status → AUTHORIZED |
| `"05"` | ❌ Do Not Honor | Payment status → FAILED, reason="Do Not Honor" |
| `"51"` | ❌ Insufficient Funds | Payment status → FAILED, reason="Insufficient Funds" |
| `"54"` | ❌ Expired Card | Payment status → FAILED, reason="Expired Card" |
| `"59"` | ❌ Suspected Fraud | Payment status → FAILED, reason="Suspected Fraud" |
| `"96"` | ❌ System Error | Payment status → FAILED, reason="System Malfunction" |

**ONLY CODE "00" MEANS SUCCESS.** Every other code is a decline of some kind.

### Processing Codes — "What Type of Transaction?"

```java
    // ==================== Processing Codes ====================

    /** Purchase */
    public static final String PROC_CODE_PURCHASE = "000000";

    /** Cash Advance */
    public static final String PROC_CODE_CASH_ADVANCE = "010000";

    /** Refund */
    public static final String PROC_CODE_REFUND = "200000";
```

**6-digit code in Field 3.** First 2 digits = transaction type:
```
"00XXXX" = Purchase (most common)
"01XXXX" = Cash Advance
"20XXXX" = Refund
```

### Field Definitions — The Registry

```java
    /**
     * Returns the complete map of ISO 8583 field definitions.
     */
    public static Map<Integer, Iso8583Field> getFieldDefinitions() {
        Map<Integer, Iso8583Field> definitions = new HashMap<>();

        definitions.put(FIELD_PAN, new Iso8583Field(
                FIELD_PAN, "Primary Account Number", Iso8583Field.FieldType.LLVAR, 19));

        definitions.put(FIELD_PROCESSING_CODE, new Iso8583Field(
                FIELD_PROCESSING_CODE, "Processing Code", Iso8583Field.FieldType.NUMERIC, 6));

        definitions.put(FIELD_AMOUNT, new Iso8583Field(
                FIELD_AMOUNT, "Transaction Amount", Iso8583Field.FieldType.NUMERIC, 12));

        definitions.put(FIELD_TRACE, new Iso8583Field(
                FIELD_TRACE, "System Trace Audit Number", Iso8583Field.FieldType.NUMERIC, 6));

        definitions.put(FIELD_TIME, new Iso8583Field(
                FIELD_TIME, "Transaction Time", Iso8583Field.FieldType.NUMERIC, 6));

        definitions.put(FIELD_AUTH_CODE, new Iso8583Field(
                FIELD_AUTH_CODE, "Authorization Code", Iso8583Field.FieldType.ALPHA, 6));

        definitions.put(FIELD_RESPONSE_CODE, new Iso8583Field(
                FIELD_RESPONSE_CODE, "Response Code", Iso8583Field.FieldType.ALPHA, 2));

        definitions.put(FIELD_TERMINAL_ID, new Iso8583Field(
                FIELD_TERMINAL_ID, "Card Acceptor Terminal ID", Iso8583Field.FieldType.ALPHA, 8));

        definitions.put(FIELD_MERCHANT_NAME, new Iso8583Field(
                FIELD_MERCHANT_NAME, "Card Acceptor Name/Location", Iso8583Field.FieldType.LLLVAR, 40));

        definitions.put(FIELD_CURRENCY_CODE, new Iso8583Field(
                FIELD_CURRENCY_CODE, "Transaction Currency Code", Iso8583Field.FieldType.NUMERIC, 3));

        return definitions;
    }
}
```

**THIS IS THE FIELD REGISTRY.** The Builder, Parser, Encoder, and Decoder all call `getFieldDefinitions()` to know:
- What type is field 2? → LLVAR, max 19
- What type is field 4? → NUMERIC, max 12
- What type is field 43? → LLLVAR, max 40

**ALL 10 FIELDS AT A GLANCE:**

| Field # | Name | Type | Max Length | Encoding |
|---|---|---|---|---|
| 2 | PAN | LLVAR | 19 | `"16" + "4111111111111111"` |
| 3 | Processing Code | NUMERIC | 6 | `"000000"` (zero-padded) |
| 4 | Amount | NUMERIC | 12 | `"000000150000"` (in minor units) |
| 11 | Trace | NUMERIC | 6 | `"654321"` |
| 12 | Time | NUMERIC | 6 | `"143025"` (hhmmss) |
| 38 | Auth Code | ALPHA | 6 | `"A12345"` (space-padded) |
| 39 | Response Code | ALPHA | 2 | `"00"` |
| 41 | Terminal ID | ALPHA | 8 | `"TERM0001"` |
| 43 | Merchant Name | LLLVAR | 40 | `"022AMAZON INDIA BANGALORE"` |
| 49 | Currency Code | NUMERIC | 3 | `"356"` (INR) |

**WHY `HashMap` AND NOT `Map.of()`?** `Map.of()` creates an unmodifiable map and has a limit of 10 entries. `HashMap` allows future expansion (more fields can be added easily).

---

## 7. How These 3 Files Connect

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                ISO 8583 FOUNDATION — HOW THEY CONNECT                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Iso8583Field (record)                                                      │
│  ├── Defines FieldType enum (NUMERIC, ALPHA, LLVAR, LLLVAR)                │
│  ├── isValid() checks field values                                          │
│  └── getEncodedLength() calculates wire size                               │
│                                                                             │
│       ▲ used by                                                             │
│       │                                                                     │
│  Iso8583Constants                                                           │
│  ├── MTI codes ("0100", "0110", "0200", etc.)                              │
│  ├── Field numbers (FIELD_PAN=2, FIELD_AMOUNT=4, etc.)                     │
│  ├── Response codes ("00"=approved, "51"=insufficient funds, etc.)         │
│  └── getFieldDefinitions() → Map<Integer, Iso8583Field>                    │
│       Creates Iso8583Field records for each known field                     │
│                                                                             │
│       ▲ used by                     ▲ used by                              │
│       │                             │                                       │
│  Iso8583MessageBuilder (9c)   Iso8583MessageParser (9c)                    │
│  "I need to know field 4       "I need to know field 4                     │
│   is NUMERIC, maxLength=12"     is NUMERIC, maxLength=12                   │
│                                  to read exactly 12 bytes"                  │
│                                                                             │
│  BitmapUtils                                                                │
│  ├── setFieldPresent() → Builder calls when adding fields                  │
│  ├── isFieldPresent() → Parser calls when reading message                  │
│  ├── clearField() → utility for modifying bitmap                           │
│  └── toHexString() / fromHexString() → debugging                          │
│                                                                             │
│       ▲ used by                     ▲ used by                              │
│       │                             │                                       │
│  Iso8583Message (9c)          Iso8583Encoder (9e)                          │
│  "setField() automatically     "Check bitmap to know which                 │
│   updates the bitmap"           fields to encode"                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **ISO 8583** | Binary financial protocol from 1987, 3-5× smaller than JSON, used by ALL banks |
| 2 | **Message structure** | MTI (4 bytes) + Bitmap (8 bytes) + Data Fields (variable) |
| 3 | **Bitmap** | 64 bits, each bit = one field. Bit ON = field present. Bit OFF = field absent |
| 4 | **Java `record`** | Immutable data class — auto-generates constructor, getters, equals, hashCode, toString |
| 5 | **Record vs @Data** | Records are truly immutable (no setters). @Data has setters. Use records for value objects |
| 6 | **FieldType enum** | NUMERIC (zero-padded), ALPHA (space-padded), LLVAR (2-digit prefix), LLLVAR (3-digit prefix) |
| 7 | **Switch expression** | `return switch (type) { case X -> value; };` — Java 14+ feature, no `break` needed |
| 8 | **Bitwise AND (`&`)** | Check if a specific bit is set: `bitmap[i] & (1 << bit) != 0` |
| 9 | **Bitwise OR (`\|=`)** | Set a bit to 1 without affecting others: `bitmap[i] \|= (1 << bit)` |
| 10 | **Bitwise NOT + AND (`&= ~`)** | Clear a bit to 0 without affecting others: `bitmap[i] &= ~(1 << bit)` |
| 11 | **Bit shifting (`<<`)** | `1 << 6` creates `01000000` — a mask with only bit 6 set |
| 12 | **byteIndex formula** | `(fieldNumber - 1) / 8` — maps field 1-64 to byte 0-7 |
| 13 | **bitIndex formula** | `7 - ((fieldNumber - 1) % 8)` — maps field to bit within byte (MSB first) |
| 14 | **Hex string conversion** | `%02X` format: byte → 2-char hex. `Integer.parseInt(hex, 16)`: hex → byte |
| 15 | **MTI pattern** | Request MTI + 10 = Response MTI. `0100` → `0110`, `0200` → `0210` |
| 16 | **Response code "00"** | The ONLY success code. Everything else (05, 51, 54, 59, 96) is a decline |
| 17 | **Field registry** | `getFieldDefinitions()` → `Map<Integer, Iso8583Field>` — central source of truth for all field metadata |
| 18 | **Utility class pattern** | `final class` + private constructor + static methods — Java best practice for stateless helpers |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part09-routing-service-overview.md) | Routing Service Overview |
| [Part 9a](./phase4-part09a-routing-project-setup.md) | Project Setup |
| **Part 9b** | **ISO 8583 Foundation** (You are here) |
| [Part 9c](./phase4-part09c-iso8583-messages.md) | ISO 8583 Messages + Tests |
| [Part 9d](./phase4-part09d-netty-config-client.md) | Netty Config + Client |
| [Part 9e](./phase4-part09e-netty-pipeline.md) | Netty Pipeline |
| [Part 9f](./phase4-part09f-fraud-rule-engine.md) | Fraud Rule Engine |
| [Part 9g](./phase4-part09g-fraud-ml-service.md) | Fraud ML + Service + Tests |
| [Part 9h](./phase4-part09h-smart-routing.md) | Smart Routing + Tests |
| [Part 9i](./phase4-part09i-config-classes.md) | Config Classes (DynamoDB, Resilience4j) |
| [Part 9j](./phase4-part09j-controller-docker.md) | Controller + DTOs + Docker + curl |
| [Part 9k](./phase4-part09k-connections-flows.md) | How Everything Connects |

---

*Next: [Part 9c — ISO 8583 Messages (Message, Builder, Parser + Tests)](./phase4-part09c-iso8583-messages.md) →*
