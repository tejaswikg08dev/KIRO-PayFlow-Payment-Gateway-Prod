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

    /**
     * Creates an empty 64-bit bitmap (all zeros).
     */
    public static byte[] createEmptyBitmap() {
        return new byte[8];
    }

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
