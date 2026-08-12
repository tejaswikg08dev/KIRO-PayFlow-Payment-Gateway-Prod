# ISO 8583 Protocol Reference — PayFlow Payment Gateway

| Field | Details |
|-------|---------|
| **Project** | PayFlow Payment Gateway |
| **Document Type** | Protocol Reference Guide |
| **Protocol Version** | ISO 8583:1993 (used by Visa/Mastercard) |
| **Last Updated** | 2024 |
| **Audience** | Developers integrating with banking networks |

---

## Table of Contents
- [Overview](#overview)
- [Message Structure](#message-structure)
- [MTI Codes](#mti-codes)
- [Bitmap Encoding](#bitmap-encoding)
- [Field Types](#field-types)
- [Field Definitions](#field-definitions)
- [Response Codes](#response-codes)
- [Complete Example: Authorization Request](#complete-example)
- [PayFlow Implementation Classes](#payflow-classes)

---

## Overview

ISO 8583 is the international standard for financial transaction card-originated messages. Every time a card is swiped, tapped, or used online, the transaction travels as an ISO 8583 binary message between acquirer, network, and issuer.

**Why binary instead of JSON/XML?**
- Compact: a full authorization message is ~200 bytes vs ~2KB in JSON
- Fast: no parsing overhead, direct byte-offset access
- Proven: handles billions of transactions daily since 1987

---

## Message Structure

Every ISO 8583 message has exactly three parts:

```
┌─────────┬──────────────┬─────────────────────────────┐
│   MTI   │    Bitmap     │        Data Fields           │
│ 4 bytes │  8-16 bytes   │    Variable length           │
└─────────┴──────────────┴─────────────────────────────┘
```

```
Example (hex representation):
0100 F238000108C18000 1234567890123456...

│      │                  │
│      │                  └─ Data fields (variable)
│      └─ Primary bitmap (8 bytes = 64 bits)
└─ MTI: 0100 = Authorization Request
```

**MTI (Message Type Indicator):** 4-digit code identifying the message purpose.
**Bitmap:** 64/128-bit field indicating which data fields are present.
**Data Fields:** Up to 128 fields carrying transaction data.

---

## MTI Codes

The MTI is a 4-digit numeric code structured as: Version + Class + Function + Origin.

| MTI | Name | Description | Direction |
|-----|------|-------------|-----------|
| `0100` | Authorization Request | Merchant asks "can this card pay?" | Acquirer → Issuer |
| `0110` | Authorization Response | Issuer says "yes/no" with response code | Issuer → Acquirer |
| `0200` | Financial Request | Actual charge (combined auth+capture) | Acquirer → Issuer |
| `0210` | Financial Response | Confirmation of charge | Issuer → Acquirer |
| `0400` | Reversal Request | Undo a previous transaction | Acquirer → Issuer |
| `0420` | Reversal Advice | Notify reversal happened (no response needed) | Acquirer → Issuer |
| `0430` | Reversal Advice Response | Acknowledgement of reversal advice | Issuer → Acquirer |

**MTI Digit Breakdown:**
- Digit 1: Version (0 = ISO 8583:1987, 1 = 1993, 2 = 2003)
- Digit 2: Message Class (1 = Authorization, 2 = Financial, 4 = Reversal)
- Digit 3: Message Function (0 = Request, 1 = Response, 2 = Advice)
- Digit 4: Message Origin (0 = Acquirer, 1 = Repeat, 2 = Issuer)

---

## Bitmap Encoding

The bitmap is a 64-bit (8-byte) binary field where each bit indicates whether the corresponding field is present.

```
Bitmap: F238000108C18000 (hex)

Binary expansion:
F    2    3    8    0    0    0    1    0    8    C    1    8    0    0    0
1111 0010 0011 1000 0000 0000 0000 0001 0000 1000 1100 0001 1000 0000 0000 0000

Bit 1 = 1 → Secondary bitmap present
Bit 2 = 1 → Primary Account Number (PAN)
Bit 3 = 1 → Processing Code
Bit 4 = 1 → Transaction Amount
Bit 5 = 0 → (not present)
...
Bit 7 = 1 → Transmission Date
...
```

**Rule:** If bit 1 is set, a secondary bitmap follows (fields 65–128).

**Java bitmap check:**
```java
public static boolean isFieldPresent(byte[] bitmap, int fieldNumber) {
    int byteIndex = (fieldNumber - 1) / 8;
    int bitIndex = 7 - ((fieldNumber - 1) % 8);
    return (bitmap[byteIndex] & (1 << bitIndex)) != 0;
}
```

---

## Field Types

| Type | Name | Description | Length Prefix | Example |
|------|------|-------------|---------------|---------|
| `NUMERIC` | Fixed Numeric | Right-justified, zero-padded | None (fixed) | `000000010000` (amount) |
| `ALPHA` | Fixed Alpha | Left-justified, space-padded | None (fixed) | `PAYFLOW MERCHANT   ` |
| `LLVAR` | 2-digit Variable | Length in first 2 digits | 2 digits | `16 4532015112830366` |
| `LLLVAR` | 3-digit Variable | Length in first 3 digits | 3 digits | `027 ONLINE PURCHASE AT STORE` |

**LLVAR Example (PAN field):**
```
Field 2 (PAN): "164532015112830366"
                ││
                │└─ Actual PAN value (16 digits)
                └─ Length prefix: "16" means 16 characters follow
```

**LLLVAR Example (Additional Data):**
```
Field 48: "027PAYFLOW-REF-ABC123456789"
           │││
           ││└─ Actual data (27 characters)
           └└─ Length prefix: "027" means 27 characters follow
```

---

## Field Definitions

| Field # | Name | Type | Max Length | Description |
|---------|------|------|-----------|-------------|
| 2 | Primary Account Number | LLVAR | 19 | Card number (PAN) |
| 3 | Processing Code | NUMERIC | 6 | Transaction type (00=purchase, 20=refund) |
| 4 | Transaction Amount | NUMERIC | 12 | Amount in minor units (cents) |
| 7 | Transmission Date/Time | NUMERIC | 10 | MMDDhhmmss |
| 11 | System Trace Audit Number | NUMERIC | 6 | Unique transaction trace |
| 12 | Local Transaction Time | NUMERIC | 6 | hhmmss |
| 13 | Local Transaction Date | NUMERIC | 4 | MMDD |
| 14 | Expiration Date | NUMERIC | 4 | YYMM |
| 22 | POS Entry Mode | NUMERIC | 3 | How card data was read (051=chip) |
| 23 | Card Sequence Number | NUMERIC | 3 | For cards with multiple PANs |
| 25 | POS Condition Code | NUMERIC | 2 | Transaction condition (00=normal) |
| 32 | Acquiring Institution ID | LLVAR | 11 | Bank routing code |
| 35 | Track 2 Data | LLVAR | 37 | Magnetic stripe data |
| 37 | Retrieval Reference Number | ALPHA | 12 | Unique reference for reconciliation |
| 38 | Authorization ID Response | ALPHA | 6 | Approval code from issuer |
| 39 | Response Code | ALPHA | 2 | Result code (00=approved) |
| 41 | Card Acceptor Terminal ID | ALPHA | 8 | Terminal identifier |
| 42 | Card Acceptor ID | ALPHA | 15 | Merchant identifier |
| 43 | Card Acceptor Name/Location | ALPHA | 40 | Merchant name and address |
| 48 | Additional Data | LLLVAR | 999 | Private use (PayFlow reference ID) |
| 49 | Currency Code | NUMERIC | 3 | ISO 4217 (840=USD, 826=GBP) |
| 54 | Additional Amounts | LLLVAR | 120 | Balance information |
| 55 | ICC Data (EMV) | LLLVAR | 999 | Chip card data (TLV encoded) |
| 60 | Private Use | LLLVAR | 999 | Network-specific data |

---

## Response Codes

| Code | Meaning | Action | PayFlow Mapping |
|------|---------|--------|-----------------|
| `00` | Approved | Transaction successful | `PaymentStatus.AUTHORIZED` |
| `05` | Do Not Honor | Generic decline from issuer | `PaymentStatus.DECLINED` |
| `12` | Invalid Transaction | Request format error | `PaymentStatus.FAILED` |
| `14` | Invalid Card Number | PAN failed Luhn check or not found | `PaymentStatus.DECLINED` |
| `51` | Insufficient Funds | Not enough balance | `PaymentStatus.DECLINED` |
| `54` | Expired Card | Card past expiration date | `PaymentStatus.DECLINED` |
| `91` | Issuer Unavailable | Bank system down, retry later | `PaymentStatus.FAILED` (retry) |
| `96` | System Malfunction | General system error | `PaymentStatus.FAILED` (retry) |

---

## Complete Example: Authorization Request

Walk-through of a `$100.00` Visa authorization for card `4532015112830366`:

```
Step 1: MTI
  0100 → Authorization Request

Step 2: Bitmap (fields 2, 3, 4, 7, 11, 14, 22, 25, 41, 42, 43, 49)
  Binary: 1110 0010 0011 1000 0000 0000 0001 0000 0000 0000 0100 0001 1000 0000 0000 0000
  Hex:    E238001000418000

Step 3: Data Fields (in order of field number)
  Field 2  (PAN):         164532015112830366        → LLVAR: "16" + PAN
  Field 3  (Proc Code):   000000                    → Purchase
  Field 4  (Amount):      000000010000              → $100.00 (in cents)
  Field 7  (Date/Time):   0115143052                → Jan 15, 14:30:52
  Field 11 (Trace):       000001                    → First transaction
  Field 14 (Expiry):      2612                      → Dec 2026
  Field 22 (Entry Mode):  051                       → Chip read
  Field 25 (Condition):   00                        → Normal
  Field 41 (Terminal):    TERM0001                  → 8 chars
  Field 42 (Merchant):    MERCHANT000001           → 15 chars (space-padded)
  Field 43 (Name):        PAYFLOW TEST MERCHANT LONDON GB         → 40 chars
  Field 49 (Currency):    826                       → GBP
```

**Complete message (hex):**
```
0100E238001000418000164532015112830366000000000000010000
011514305200000126120510TERM0001MERCHANT000001  PAYFLOW TEST MERCHANT LONDON GB         826
```

---

## PayFlow Implementation Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `Iso8583Message` | `com.payflow.routing.iso8583` | Immutable message model holding MTI + fields map |
| `Iso8583MessageBuilder` | `com.payflow.routing.iso8583` | Fluent builder: `.mti("0100").field(2, pan).build()` |
| `Iso8583MessageParser` | `com.payflow.routing.iso8583` | Parses raw bytes back into `Iso8583Message` |
| `BitmapUtils` | `com.payflow.routing.iso8583` | Bit manipulation: set/check/encode/decode bitmaps |
| `Iso8583Constants` | `com.payflow.routing.iso8583` | MTI codes, field numbers, response codes as constants |
| `Iso8583Field` | `com.payflow.routing.iso8583` | Field definition: number, name, type, maxLength |
| `FieldType` | `com.payflow.routing.iso8583` | Enum: NUMERIC, ALPHA, LLVAR, LLLVAR |

**Builder usage in PayFlow:**
```java
Iso8583Message authRequest = new Iso8583MessageBuilder()
    .mti(Iso8583Constants.MTI_AUTH_REQUEST)           // "0100"
    .field(Iso8583Constants.FIELD_PAN, cardNumber)    // Field 2
    .field(Iso8583Constants.FIELD_PROCESSING_CODE, "000000")
    .field(Iso8583Constants.FIELD_AMOUNT, formatAmount(amount))
    .field(Iso8583Constants.FIELD_TRACE, generateTrace())
    .field(Iso8583Constants.FIELD_EXPIRY, expiry)
    .field(Iso8583Constants.FIELD_TERMINAL_ID, terminalId)
    .field(Iso8583Constants.FIELD_MERCHANT_ID, merchantId)
    .field(Iso8583Constants.FIELD_CURRENCY, currencyCode)
    .build();

byte[] rawBytes = authRequest.toBytes();  // Ready to send over TCP
```

**Parser usage in PayFlow:**
```java
byte[] responseBytes = nettyChannel.read();
Iso8583Message authResponse = Iso8583MessageParser.parse(responseBytes);

String responseCode = authResponse.getField(Iso8583Constants.FIELD_RESPONSE_CODE);
String authCode = authResponse.getField(Iso8583Constants.FIELD_AUTH_CODE);

if ("00".equals(responseCode)) {
    // Approved — update payment status
}
```

---

## Quick Reference Card

```
┌─────────────────────────────────────────────────────┐
│              ISO 8583 Quick Reference                │
├─────────────────────────────────────────────────────┤
│ Auth Request:  0100  │  Auth Response:  0110        │
│ Charge:        0200  │  Charge Resp:    0210        │
│ Reversal:      0400  │  Reversal Resp:  0410        │
├─────────────────────────────────────────────────────┤
│ Approved: 00  │  Declined: 05  │  No Funds: 51     │
│ Bad Card: 14  │  Expired:  54  │  Sys Error: 96    │
├─────────────────────────────────────────────────────┤
│ NUMERIC = fixed, zero-padded                        │
│ ALPHA   = fixed, space-padded                       │
│ LLVAR   = 2-digit length prefix + data              │
│ LLLVAR  = 3-digit length prefix + data              │
└─────────────────────────────────────────────────────┘
```

---

*This document serves as a quick reference for the PayFlow ISO 8583 implementation. For implementation details, see [Phase 4 Part 9a](./phase4-part09a-routing-iso8583.md).*
