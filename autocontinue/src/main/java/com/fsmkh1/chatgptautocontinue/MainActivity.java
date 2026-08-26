package com.fsmkh1.chatgptautocontinue;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private Switch enabledSwitch;
    private EditText intervalEdit;
    private EditText messageEdit;
    private TextView serviceStatus;
    private TextView runStatus;
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        loadValues();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private View buildUi() {
        int pad = dp(20);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("ChatGPT 작업 지속기", 24, true);
        root.addView(title);
        TextView sub = text("15분마다 ChatGPT에 ‘수동 시작해’를 보내는 개인용 자동화", 14, false);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("자동 진행 ON / OFF");
        enabledSwitch.setTextSize(18);
        enabledSwitch.setPadding(0, dp(8), 0, dp(12));
        root.addView(enabledSwitch, matchWrap());

        root.addView(label("보낼 문구"));
        messageEdit = new EditText(this);
        messageEdit.setSingleLine(true);
        messageEdit.setTextSize(18);
        messageEdit.setHint(AutomationPrefs.DEFAULT_MESSAGE);
        root.addView(messageEdit, matchWrap());

        root.addView(label("간격(분)"));
        intervalEdit = new EditText(this);
        intervalEdit.setSingleLine(true);
        intervalEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalEdit.setTextSize(18);
        root.addView(intervalEdit, matchWrap());

        Button save = button("설정 저장");
        save.setOnClickListener(v -> saveValues());
        root.addView(save, matchWrapWithTop(14));

        Button accessibility = button("1. 접근성 권한 열기");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, matchWrapWithTop(10));

        Button battery = button("2. 배터리 최적화 설정 열기");
        battery.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        root.addView(battery, matchWrapWithTop(10));

        Button chatgpt = button("ChatGPT 열기");
        chatgpt.setOnClickListener(v -> openChatGpt());
        root.addView(chatgpt, matchWrapWithTop(10));

        Button runNow = button("지금 한 번 실행");
        runNow.setOnClickListener(v -> {
            saveValues();
            boolean ok = ChatGptAccessibilityService.requestRunNow();
            Toast.makeText(this, ok ? "실행 요청함" : "접근성 서비스를 먼저 켜주세요", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
        root.addView(runNow, matchWrapWithTop(10));

        serviceStatus = text("", 15, true);
        serviceStatus.setPadding(0, dp(20), 0, dp(6));
        root.addView(serviceStatus);
        runStatus = text("", 14, false);
        runStatus.setPadding(0, 0, 0, dp(14));
        root.addView(runStatus);

        TextView notice = text(
                "안전장치\n• ChatGPT 앱에서만 동작\n• 응답 생성 중이면 전송하지 않고 1분 후 재시도\n• 화면 잠금 상태면 대기\n• 입력창 내용이 자동 문구와 다르면 전송 취소\n• ChatGPT UI가 바뀌어 입력창/보내기 버튼을 못 찾으면 전송하지 않음\n\n주의: 현재 열려 있거나 마지막으로 열렸던 ChatGPT 대화를 대상으로 합니다. 처음에는 원하는 대화방을 열어 둔 상태에서 테스트하세요.",
                14, false);
        notice.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(notice, matchWrap());

        TextView logTitle = text("최근 로그", 17, true);
        logTitle.setPadding(0, dp(18), 0, dp(8));
        root.addView(logTitle);
        logView = text("", 13, false);
        logView.setTextIsSelectable(true);
        root.addView(logView, matchWrap());

        return scroll;
    }

    private void loadValues() {
        SharedPreferences p = AutomationPrefs.get(this);
        enabledSwitch.setChecked(p.getBoolean(AutomationPrefs.KEY_ENABLED, false));
        intervalEdit.setText(String.valueOf(p.getInt(AutomationPrefs.KEY_INTERVAL, AutomationPrefs.DEFAULT_INTERVAL_MINUTES)));
        messageEdit.setText(p.getString(AutomationPrefs.KEY_MESSAGE, AutomationPrefs.DEFAULT_MESSAGE));
        refreshStatus();
    }

    private void saveValues() {
        int interval = AutomationPrefs.DEFAULT_INTERVAL_MINUTES;
        try { interval = Integer.parseInt(intervalEdit.getText().toString().trim()); } catch (Exception ignored) {}
        interval = Math.max(1, Math.min(240, interval));
        String msg = messageEdit.getText().toString().trim();
        if (msg.isEmpty()) msg = AutomationPrefs.DEFAULT_MESSAGE;

        AutomationPrefs.get(this).edit()
                .putBoolean(AutomationPrefs.KEY_ENABLED, enabledSwitch.isChecked())
                .putInt(AutomationPrefs.KEY_INTERVAL, interval)
                .putString(AutomationPrefs.KEY_MESSAGE, msg)
                .apply();
        if (enabledSwitch.isChecked()) {
            AutomationPrefs.scheduleFromNow(this);
            AutomationPrefs.setStatus(this, "자동 진행 켜짐");
        } else {
            AutomationPrefs.setStatus(this, "자동 진행 꺼짐");
        }
        intervalEdit.setText(String.valueOf(interval));
        messageEdit.setText(msg);
        Toast.makeText(this, "저장했습니다", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean serviceOn = isAccessibilityServiceEnabled(this, ChatGptAccessibilityService.class);
        serviceStatus.setText(serviceOn ? "접근성 서비스: 켜짐" : "접근성 서비스: 꺼짐 — 위 버튼에서 켜주세요");
        SharedPreferences p = AutomationPrefs.get(this);
        String last = p.getString(AutomationPrefs.KEY_LAST_STATUS, "아직 실행 기록 없음");
        long sent = p.getLong(AutomationPrefs.KEY_LAST_SENT, 0L);
        long due = p.getLong(AutomationPrefs.KEY_NEXT_DUE, 0L);
        runStatus.setText("최근 상태: " + last + "\n마지막 전송: " + AutomationPrefs.formatTime(sent) + "\n다음 예정: " + AutomationPrefs.formatTime(due));
        logView.setText(p.getString(AutomationPrefs.KEY_LOG, ""));
    }

    private void openChatGpt() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
        if (launch != null) startActivity(launch);
        else Toast.makeText(this, "ChatGPT 앱을 찾지 못했습니다", Toast.LENGTH_SHORT).show();
    }

    private static boolean isAccessibilityServiceEnabled(Context context, Class<?> serviceClass) {
        String expected = new ComponentName(context, serviceClass).flattenToString();
        String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String[] parts = enabled.split(":");
        for (String part : parts) if (expected.equalsIgnoreCase(part)) return true;
        return false;
    }

    private TextView label(String s) {
        TextView t = text(s, 14, true);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(50));
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapWithTop(int valueDp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(valueDp);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
