package com.fsmkh1.chatgptautocontinue;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AutomationPrefs {
    public static final String PREFS = "automation";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_INTERVAL = "interval_minutes";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_CONVERSATION_GUARD = "conversation_guard";
    public static final String KEY_NEXT_DUE = "next_due";
    public static final String KEY_LAST_SENT = "last_sent";
    public static final String KEY_LAST_STATUS = "last_status";
    public static final String KEY_LOG = "log";

    public static final int DEFAULT_INTERVAL_MINUTES = 15;
    public static final String DEFAULT_MESSAGE = "수동 시작해";

    private AutomationPrefs() {}

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return get(c).getBoolean(KEY_ENABLED, false);
    }

    public static int intervalMinutes(Context c) {
        int value = get(c).getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MINUTES);
        return Math.max(1, Math.min(240, value));
    }

    public static String message(Context c) {
        String value = get(c).getString(KEY_MESSAGE, DEFAULT_MESSAGE);
        if (value == null || value.trim().isEmpty()) return DEFAULT_MESSAGE;
        return value.trim();
    }

    public static String conversationGuard(Context c) {
        String value = get(c).getString(KEY_CONVERSATION_GUARD, "");
        return value == null ? "" : value.trim();
    }

    public static long nextDue(Context c) {
        return get(c).getLong(KEY_NEXT_DUE, 0L);
    }

    public static void setNextDue(Context c, long when) {
        get(c).edit().putLong(KEY_NEXT_DUE, when).apply();
    }

    public static void scheduleFromNow(Context c) {
        setNextDue(c, System.currentTimeMillis() + intervalMinutes(c) * 60_000L);
    }

    public static void setStatus(Context c, String status) {
        get(c).edit().putString(KEY_LAST_STATUS, status).apply();
        appendLog(c, status);
    }

    public static void markSent(Context c) {
        long now = System.currentTimeMillis();
        get(c).edit()
                .putLong(KEY_LAST_SENT, now)
                .putLong(KEY_NEXT_DUE, now + intervalMinutes(c) * 60_000L)
                .putString(KEY_LAST_STATUS, "전송 확인 완료")
                .apply();
        appendLog(c, "전송 확인 완료");
    }

    public static void retrySoon(Context c, String reason) {
        long when = System.currentTimeMillis() + 60_000L;
        get(c).edit()
                .putLong(KEY_NEXT_DUE, when)
                .putString(KEY_LAST_STATUS, reason + " — 1분 후 재시도")
                .apply();
        appendLog(c, reason + " — 1분 후 재시도");
    }

    public static void appendLog(Context c, String text) {
        SharedPreferences p = get(c);
        String old = p.getString(KEY_LOG, "");
        if (old == null) old = "";
        String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = ts + "  " + text;
        String joined = line + (old.isEmpty() ? "" : "\n" + old);
        String[] lines = joined.split("\n");
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < Math.min(40, lines.length); i++) {
            if (i > 0) kept.append('\n');
            kept.append(lines[i]);
        }
        p.edit().putString(KEY_LOG, kept.toString()).apply();
    }

    public static String formatTime(long time) {
        if (time <= 0) return "-";
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(time));
    }
}
