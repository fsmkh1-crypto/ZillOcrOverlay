package com.fsmkh1.chatgptautocontinue;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AutomationPrefs {
    public static final String PREFS = "automation_v2";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_INTERVAL = "interval_minutes";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_NEXT_DUE = "next_due";
    public static final String KEY_LAST_SENT = "last_sent";
    public static final String KEY_LAST_STATUS = "last_status";
    public static final String KEY_LOG = "log";
    public static final String KEY_SETUP_PASSED = "setup_passed";
    public static final String KEY_VERIFIED_METHOD = "verified_method";
    public static final String KEY_FAIL_COUNT = "fail_count";
    public static final String KEY_INSPECT_EDITOR = "inspect_editor";
    public static final String KEY_INSPECT_SEMANTIC = "inspect_semantic";
    public static final String KEY_INSPECT_IME = "inspect_ime";
    public static final String KEY_INSPECT_COORD = "inspect_coord";
    public static final String KEY_INSPECT_SUMMARY = "inspect_summary";

    public static final int DEFAULT_INTERVAL_MINUTES = 15;
    public static final String DEFAULT_MESSAGE = "수동 시작해";
    public static final String METHOD_NONE = "none";
    public static final String METHOD_SEMANTIC = "semantic";
    public static final String METHOD_IME = "ime";
    public static final String METHOD_COORD = "coordinate";

    private AutomationPrefs() {}

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return get(c).getBoolean(KEY_ENABLED, false);
    }

    public static boolean setupPassed(Context c) {
        return get(c).getBoolean(KEY_SETUP_PASSED, false);
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

    public static String verifiedMethod(Context c) {
        String value = get(c).getString(KEY_VERIFIED_METHOD, METHOD_NONE);
        return value == null ? METHOD_NONE : value;
    }

    public static long nextDue(Context c) {
        return get(c).getLong(KEY_NEXT_DUE, 0L);
    }

    public static void saveBasic(Context c, int interval, String message) {
        get(c).edit()
                .putInt(KEY_INTERVAL, Math.max(1, Math.min(240, interval)))
                .putString(KEY_MESSAGE, message == null || message.trim().isEmpty() ? DEFAULT_MESSAGE : message.trim())
                .apply();
    }

    public static void setEnabled(Context c, boolean enabled) {
        SharedPreferences.Editor e = get(c).edit().putBoolean(KEY_ENABLED, enabled);
        if (enabled) {
            e.putLong(KEY_NEXT_DUE, System.currentTimeMillis() + intervalMinutes(c) * 60_000L);
            e.putString(KEY_LAST_STATUS, "자동 진행 켜짐");
        } else {
            e.putLong(KEY_NEXT_DUE, 0L);
            e.putString(KEY_LAST_STATUS, "자동 진행 꺼짐");
        }
        e.apply();
        appendLog(c, enabled ? "자동 진행 켜짐" : "자동 진행 꺼짐");
    }

    public static void ensureSchedule(Context c) {
        if (enabled(c) && nextDue(c) <= 0L) {
            get(c).edit().putLong(KEY_NEXT_DUE,
                    System.currentTimeMillis() + intervalMinutes(c) * 60_000L).apply();
        }
    }

    public static void setStatus(Context c, String status) {
        get(c).edit().putString(KEY_LAST_STATUS, status).apply();
        appendLog(c, status);
    }

    public static void defer(Context c, String reason, int minutes) {
        long when = System.currentTimeMillis() + Math.max(1, minutes) * 60_000L;
        get(c).edit()
                .putLong(KEY_NEXT_DUE, when)
                .putString(KEY_LAST_STATUS, "보류: " + reason)
                .apply();
        appendLog(c, "보류: " + reason + " — " + Math.max(1, minutes) + "분 뒤 다시 확인");
    }

    public static void saveInspection(Context c, boolean editor, boolean semantic, boolean ime, boolean coord, String summary) {
        get(c).edit()
                .putBoolean(KEY_INSPECT_EDITOR, editor)
                .putBoolean(KEY_INSPECT_SEMANTIC, semantic)
                .putBoolean(KEY_INSPECT_IME, ime)
                .putBoolean(KEY_INSPECT_COORD, coord)
                .putString(KEY_INSPECT_SUMMARY, summary)
                .putString(KEY_LAST_STATUS, "화면 검사 완료")
                .apply();
        appendLog(c, "화면 검사 완료: " + summary);
    }

    public static void verifyMethod(Context c, String method) {
        get(c).edit()
                .putBoolean(KEY_SETUP_PASSED, true)
                .putString(KEY_VERIFIED_METHOD, method)
                .putInt(KEY_FAIL_COUNT, 0)
                .putBoolean(KEY_ENABLED, false)
                .putLong(KEY_NEXT_DUE, 0L)
                .putString(KEY_LAST_STATUS, "전송 시험 성공 — 방식 확정: " + methodLabel(method))
                .apply();
        appendLog(c, "전송 시험 성공 — " + methodLabel(method));
    }

    public static void markAutoSuccess(Context c) {
        long now = System.currentTimeMillis();
        get(c).edit()
                .putLong(KEY_LAST_SENT, now)
                .putLong(KEY_NEXT_DUE, now + intervalMinutes(c) * 60_000L)
                .putInt(KEY_FAIL_COUNT, 0)
                .putString(KEY_LAST_STATUS, "자동 전송 확인 완료")
                .apply();
        appendLog(c, "자동 전송 확인 완료");
    }

    public static void recordFailure(Context c, String reason, boolean auto) {
        SharedPreferences p = get(c);
        int count = auto ? p.getInt(KEY_FAIL_COUNT, 0) + 1 : p.getInt(KEY_FAIL_COUNT, 0);
        SharedPreferences.Editor e = p.edit();
        if (auto) {
            e.putInt(KEY_FAIL_COUNT, count);
            if (count >= 2) {
                e.putBoolean(KEY_ENABLED, false)
                        .putLong(KEY_NEXT_DUE, 0L)
                        .putString(KEY_LAST_STATUS, "자동 중지: " + reason);
                appendLog(c, "자동 중지(연속 2회 구조 실패): " + reason);
            } else {
                e.putLong(KEY_NEXT_DUE, System.currentTimeMillis() + intervalMinutes(c) * 60_000L)
                        .putString(KEY_LAST_STATUS, "자동 전송 실패: " + reason);
                appendLog(c, "자동 전송 실패: " + reason + " — 다음 정규 주기까지 대기");
            }
        } else {
            e.putString(KEY_LAST_STATUS, "시험 실패: " + reason);
            appendLog(c, "시험 실패: " + reason);
        }
        e.apply();
    }

    public static void resetVerification(Context c) {
        get(c).edit()
                .putBoolean(KEY_ENABLED, false)
                .putBoolean(KEY_SETUP_PASSED, false)
                .putString(KEY_VERIFIED_METHOD, METHOD_NONE)
                .putInt(KEY_FAIL_COUNT, 0)
                .putLong(KEY_NEXT_DUE, 0L)
                .putBoolean(KEY_INSPECT_EDITOR, false)
                .putBoolean(KEY_INSPECT_SEMANTIC, false)
                .putBoolean(KEY_INSPECT_IME, false)
                .putBoolean(KEY_INSPECT_COORD, false)
                .putString(KEY_INSPECT_SUMMARY, "아직 검사하지 않음")
                .putString(KEY_LAST_STATUS, "검증 상태 초기화됨")
                .apply();
        appendLog(c, "검증 상태 초기화됨");
    }

    public static String methodLabel(String method) {
        if (METHOD_SEMANTIC.equals(method)) return "접근성 보내기 버튼";
        if (METHOD_IME.equals(method)) return "키보드/IME 전송";
        if (METHOD_COORD.equals(method)) return "좌표 탭";
        return "미확정";
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
        for (int i = 0; i < Math.min(30, lines.length); i++) {
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
