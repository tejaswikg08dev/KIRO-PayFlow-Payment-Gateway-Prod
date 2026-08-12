package com.payflow.common.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Date/time utility methods used across services.
 */
public final class DateUtils {

    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_INSTANT;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private DateUtils() {
    }

    public static String formatInstant(Instant instant) {
        return ISO_FORMAT.format(instant);
    }

    public static LocalDate todayIST() {
        return LocalDate.now(IST);
    }

    public static Instant startOfDayIST(LocalDate date) {
        return date.atStartOfDay(IST).toInstant();
    }

    public static Instant endOfDayIST(LocalDate date) {
        return date.plusDays(1).atStartOfDay(IST).toInstant().minusMillis(1);
    }

    public static boolean isExpired(Instant expiresAt) {
        return Instant.now().isAfter(expiresAt);
    }
}
