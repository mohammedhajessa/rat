package com.h4ckinto.android.utils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Chat server stores message times in UTC (chat-server messageService.nowISO → toISOString wall time).
 * Parse as UTC, format with {@link TimeZone#getDefault()} for display so list + bubbles match the clock.
 */
public final class ChatTimeUtils {

    private ChatTimeUtils() {
    }

    /**
     * Parse server timestamp: UTC "yyyy-MM-dd HH:mm:ss", ISO with T, optional .sss and Z.
     */
    public static Date parseServerUtc(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return null;
        }
        String s = timestamp.trim();
        if (s.endsWith("Z")) {
            s = s.substring(0, s.length() - 1);
        }
        int dot = s.indexOf('.');
        if (dot > 0) {
            s = s.substring(0, dot);
        }
        s = s.replace('T', ' ');
        if (s.length() > 19) {
            s = s.substring(0, 19);
        }
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            f.setLenient(false);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(s);
        } catch (Exception e) {
            try {
                String day = s.length() >= 10 ? s.substring(0, 10) : s;
                SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                dayFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
                return dayFmt.parse(day);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /** 12h time in device local zone. */
    public static String formatLocalTime(Date date) {
        if (date == null) {
            return "";
        }
        SimpleDateFormat out = new SimpleDateFormat("hh:mm a", Locale.US);
        out.setTimeZone(TimeZone.getDefault());
        return out.format(date);
    }

    public static String formatLocalTime(String serverTimestamp) {
        Date d = parseServerUtc(serverTimestamp);
        return d != null ? formatLocalTime(d) : (serverTimestamp != null ? serverTimestamp : "");
    }

    /** Same format the chat server uses for DB + socket (UTC wall clock). */
    public static String nowServerUtcString() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    public static String formatLocalTimeNow() {
        return formatLocalTime(new Date());
    }
}
