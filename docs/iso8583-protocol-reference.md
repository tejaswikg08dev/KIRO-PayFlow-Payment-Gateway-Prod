# ISO 8583 Protocol Reference

## Overview

ISO 8583 is the international standard for financial transaction card-originated messages. PayFlow uses it for communication between the Routing Service and Bank Simulator over TCP.

## Message Structure

```
┌─────────────────────────────────────────────────────────────────┐
│                     ISO 8583 MESSAGE FORMAT                       │
├──────────────┬──────────────────┬───────────────────────────────┤
│     MTI      │     BITMAP       │         DATA FIELDS            │
│  (4 bytes)   │  (8 or 16 bytes) │       (variable length)        │
│              │                  │                               │
│  Message     │  Indicates which │  Actual field values          │
│  Type        │  fields are      │  (fixed or variable length)   │
│  Indicator   │  present         │                               │
└──────────────┴──────────────────┴───────────────────────────────┘
```

## Message Type Indicator (MTI)

| Position | Meaning | Values |
|----------|---------|--------|
| 1st digit | ISO version | 0 = 1987, 1 = 1993, 2 = 2003 |
| 2nd digit | Message class | 1 = Authorization, 2 = Financial, 4 = Reversal |
| 3rd digit | Message function | 0 = Request, 1 = Response, 2 = Advice |
| 4th digit | Transaction originator | 0 = Acquirer, 1 = Repeat |

### MTI Codes Used in PayFlow

| MTI | Description | Direction |
|-----|-------------|-----------|
| 0100 | Authorization Request | PayFlow → Bank |
| 0110 | Authorization Response | Bank → PayFlow |
| 0200 | Financial Transaction Request | PayFlow → Bank |
| 0210 | Financial Transaction Response | Bank → PayFlow |
| 0400 | Reversal Request | PayFlow → Bank |
| 0410 | Reversal Response | Bank → PayFlow |
| 0800 | Network Management Request | PayFlow → Bank |
| 0810 | Network Management Response | Bank → PayFlow |

## Bitmap Encoding

```
The bitmap indicates which data fields are present in the message.
Each bit position corresponds to a field number.

Primary Bitmap: 64 bits (fields 1-64)
Secondary Bitmap: 64 bits (fields 65-128, if bit 1 of primary is set)

Example Bitmap (hex): 72 3C 44 81 08 C0 80 00
Binary: 0111 0010 0011 1100 0100 0100 1000 0001
        0000 1000 1100 0000 1000 0000 0000 0000

Fields present: 2, 3, 4, 7, 11, 12, 13, 14, 22, 37, 38, 39, 41, 42, 49
```

### Bitmap Calculation

```java
public static byte[] bitmapToBytes(BitSet bitmap) {
    byte[] bytes = new byte[8];
    for (int i = 0; i < 64; i++) {
        if (bitmap.get(i)) {
            bytes[i / 8] |= (1 << (7 - (i % 8)));
        }
    }
    return bytes;
}
```

## Field Definitions

| Field | Name | Length | Format | Description |
|-------|------|--------|--------|-------------|
| 2 | PAN | up to 19 | LLVAR N | Primary Account Number |
| 3 | Processing Code | 6 | Fixed N | Transaction type (000000=purchase) |
| 4 | Amount | 12 | Fixed N | Transaction amount (minor units) |
| 7 | Transmission DateTime | 10 | Fixed N | MMDDHHmmss |
| 11 | STAN | 6 | Fixed N | System Trace Audit Number |
| 12 | Local Time | 6 | Fixed N | HHmmss |
| 13 | Local Date | 4 | Fixed N | MMDD |
| 14 | Expiration Date | 4 | Fixed N | YYMM |
| 22 | POS Entry Mode | 3 | Fixed N | 010=manual, 051=chip |
| 23 | Card Sequence Number | 3 | Fixed N | |
| 25 | POS Condition Code | 2 | Fixed N | 00=normal |
| 32 | Acquiring Institution ID | up to 11 | LLVAR N | |
| 35 | Track 2 Data | up to 37 | LLVAR Z | |
| 37 | Retrieval Reference Number | 12 | Fixed AN | |
| 38 | Authorization Code | 6 | Fixed AN | |
| 39 | Response Code | 2 | Fixed AN | |
| 41 | Terminal ID | 8 | Fixed AN | |
| 42 | Merchant ID | 15 | Fixed AN | |
| 43 | Card Acceptor Name/Location | 40 | Fixed AN | |
| 49 | Currency Code | 3 | Fixed N | 356=INR |
| 52 | PIN Data | 8 | Fixed B | |
| 54 | Additional Amounts | up to 120 | LLLVAR AN | |
| 55 | ICC Data | up to 255 | LLLVAR AN | EMV chip data |

## Variable Length Fields

```
LLVAR:  2-byte length prefix + data  (max 99)
LLLVAR: 3-byte length prefix + data  (max 999)

Example: Field 2 (PAN) as LLVAR
Length: "16" + Data: "4111111111111111"
Wire format: "164111111111111111"
```

## Example Messages

### Authorization Request (0100)

```
MTI: 0100
Field  2: 4111111111111111         (PAN)
Field  3: 000000                    (Processing Code - Purchase)
Field  4: 000000050000              (Amount: ₹500.00)
Field  7: 0115103045                (Jan 15, 10:30:45)
Field 11: 123456                    (STAN)
Field 12: 103045                    (Time: 10:30:45)
Field 13: 0115                      (Date: Jan 15)
Field 14: 2512                      (Expiry: Dec 2025)
Field 22: 010                       (E-commerce)
Field 37: 240115123456              (RRN)
Field 41: PAYFLOW1                  (Terminal ID)
Field 42: MERCH000000001            (Merchant ID)
Field 49: 356                       (INR)

Wire bytes (hex):
0100 7238 4481 08C0 8000 1641 1111 1111 1111
0000 0000 0005 0000 0115 1030 4512 3456 ...
```

### Authorization Response (0110)

```
MTI: 0110
Field  2: 4111111111111111         (Echo PAN)
Field  3: 000000                    (Echo Processing Code)
Field  4: 000000050000              (Echo Amount)
Field 11: 123456                    (Echo STAN)
Field 37: 240115123456              (Echo RRN)
Field 38: A12345                    (Authorization Code) ← NEW
Field 39: 00                        (Response Code: Approved) ← NEW
Field 41: PAYFLOW1                  (Echo Terminal)
Field 42: MERCH000000001            (Echo Merchant)
```

## Response Codes Reference

| Code | Meaning | Action |
|------|---------|--------|
| 00 | Approved | ✅ Complete transaction |
| 01 | Refer to card issuer | ❌ Decline, contact bank |
| 03 | Invalid merchant | ❌ Configuration error |
| 05 | Do not honor | ❌ Generic decline |
| 12 | Invalid transaction | ❌ Decline |
| 13 | Invalid amount | ❌ Decline |
| 14 | Invalid card number | ❌ Card validation failed |
| 30 | Format error | ❌ Message encoding issue |
| 41 | Lost card — pick up | ❌ Flag for fraud |
| 43 | Stolen card — pick up | ❌ Flag for fraud |
| 51 | Insufficient funds | ❌ Decline, suggest lower amount |
| 54 | Expired card | ❌ Card expired |
| 55 | Incorrect PIN | ❌ Auth failure |
| 57 | Transaction not permitted | ❌ Card restrictions |
| 61 | Exceeds withdrawal limit | ❌ Daily limit reached |
| 91 | Issuer not available | ⚠️ Retry after delay |
| 96 | System malfunction | ⚠️ Retry after delay |

## Processing Codes

| Code | Transaction Type |
|------|-----------------|
| 000000 | Purchase |
| 010000 | Cash withdrawal |
| 200000 | Refund/Credit |
| 300000 | Balance inquiry |

## TCP Framing

```
┌──────────────┬───────────────────────────────┐
│ Length Header │      ISO 8583 Message          │
│  (2 bytes)   │      (variable)                │
│  Big-endian  │                                │
└──────────────┴───────────────────────────────┘

Example: Message of 145 bytes
Wire: [0x00][0x91][...145 bytes of ISO 8583...]
```
