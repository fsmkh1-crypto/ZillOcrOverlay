package com.fsmkh1.chatgptautocontinue;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
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
    private TextView inspectStatus;
    private TextView verificationStatus;
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

        root.addView(text("ChatGPT 작업 지속기 v2.0", 24, true));
        TextView sub = text("먼저 현재 ChatGPT 화면을 검사하고, 실제 성공한 전송 방식 하나만 저장해서 반복합니다.", 14, false);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);

        root.addView(section("1. 기본 권한"));
        Button accessibility = button("접근성 권한 열기");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, matchWrapWithTop(8));

        Button battery = button("배터리 최적화 설정 열기");
        battery.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        root.addView(battery, matchWrapWithTop(8));

        Button autostart = button("포코/샤오미 자동 시작 설정");
        autostart.setOnClickListener(v -> openAutostartSettings());
        root.addView(autostart, matchWrapWithTop(8));

        serviceStatus = text("", 15, true);
        serviceStatus.setPadding(0, dp(10), 0, dp(4));
        root.addView(serviceStatus);

        root.addView(section("2. 보낼 내용"));
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

        Button saveBasic = button("내용 저장");
        saveBasic.setOnClickListener(v -> saveBasicValues());
        root.addView(saveBasic, matchWrapWithTop(10));

        root.addView(section("3. ChatGPT 화면 검사"));
        TextView inspectHelp = text("이 버튼을 누르면 ChatGPT를 열어 입력창과 사용 가능한 전송 방식을 확인한 뒤 자동으로 이 화면으로 돌아옵니다. 메시지는 보내지 않습니다.", 13, false);
        root.addView(inspectHelp);
        Button inspect = button("화면 검사 시작");
        inspect.setOnClickListener(v -> {
            saveBasicValues(false);
            boolean ok = ChatGptAccessibilityService.requestInspect();
            Toast.makeText(this, ok ? "ChatGPT 화면을 검사합니다" : "접근성 서비스를 먼저 켜주세요", Toast.LENGTH_SHORT).show();
        });
        root.addView(inspect, matchWrapWithTop(8));
        inspectStatus = text("", 14, true);
        inspectStatus.setPadding(0, dp(8), 0, 0);
        root.addView(inspectStatus);

        root.addView(section("4. 전송 방식 시험"));
        TextView testHelp = text("아래 시험은 실제로 현재/마지막 ChatGPT 대화에 문구를 1회 보냅니다. 화면 검사에서 O로 나온 방식부터 시험하세요. 성공한 방식 하나가 자동화 방식으로 고정됩니다.", 13, false);
        root.addView(testHelp);

        Button testSemantic = button("A. 보내기 버튼 방식 시험");
        testSemantic.setOnClickListener(v -> startTest(AutomationPrefs.METHOD_SEMANTIC));
        root.addView(testSemantic, matchWrapWithTop(8));

        Button testIme = button("B. 키보드/IME 방식 시험");
        testIme.setOnClickListener(v -> startTest(AutomationPrefs.METHOD_IME));
        root.addView(testIme, matchWrapWithTop(8));

        Button testCoord = button("C. 좌표 방식 시험 (마지막 수단)");
        testCoord.setOnClickListener(v -> startTest(AutomationPrefs.METHOD_COORD));
        root.addView(testCoord, matchWrapWithTop(8));

        verificationStatus = text("", 14, true);
        verificationStatus.setPadding(0, dp(10), 0, 0);
        root.addView(verificationStatus);

        Button reset = button("검사/전송방식 초기화");
        reset.setOnClickListener(v -> {
            AutomationPrefs.resetVerification(this);
            enabledSwitch.setChecked(false);
            refreshStatus();
            Toast.makeText(this, "검증 상태를 초기화했습니다", Toast.LENGTH_SHORT).show();
        });
        root.addView(reset, matchWrapWithTop(8));

        root.addView(section("5. 자동 진행"));
        enabledSwitch = new Switch(this);
        enabledSwitch.setText("15분마다 자동 진행 ON / OFF");
        enabledSwitch.setTextSize(18);
        root.addView(enabledSwitch, matchWrap());

        Button applyAuto = button("자동 진행 설정 적용");
        applyAuto.setOnClickListener(v -> applyAutomationSetting());
        root.addView(applyAuto, matchWrapWithTop(8));

        Button runNow = button("검증된 방식으로 지금 한 번 실행");
        runNow.setOnClickListener(v -> {
            saveBasicValues(false);
            if (!AutomationPrefs.setupPassed(this)) {
                Toast.makeText(this, "먼저 전송 방식 시험을 성공시켜주세요", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean ok = ChatGptAccessibilityService.requestRunNow();
            Toast.makeText(this, ok ? "검증된 방식으로 실행합니다" : "접근성 서비스를 먼저 켜주세요", Toast.LENGTH_SHORT).show();
        });
        root.addView(runNow, matchWrapWithTop(8));

        runStatus = text("", 14, false);
        runStatus.setPadding(0, dp(12), 0, dp(12));
        root.addView(runStatus);

        TextView notice = text(
                "v2 핵심 변경\n• 접근성 이벤트가 자동화를 재호출하지 않음 — 중복 실행 제거\n• 실패해도 1분마다 무한 재시도하지 않음\n• 입력창에 기존 글이 있으면 절대 덮어쓰지 않음\n• 자동화는 시험에 성공한 전송 방식 하나만 사용\n• 임의로 다른 방식으로 폴백하지 않음\n• 실제 응답 생성 또는 새 사용자 메시지 증가를 확인해야 성공 처리\n• 자동 전송이 연속 2회 실패하면 자동으로 OFF\n• 자동 실행 때문에 ChatGPT를 열었으면 끝난 뒤 이전 화면으로 복귀 시도\n• 로그에는 ChatGPT 대화 내용 자체를 저장하지 않음",
                13, false);
        notice.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(notice, matchWrap());

        TextView logTitle = section("최근 로그");
        root.addView(logTitle);
        logView = text("", 13, false);
        logView.setTextIsSelectable(true);
        root.addView(logView, matchWrap());

        return scroll;
    }

    private void startTest(String method) {
        saveBasicValues(false);
        boolean ok = ChatGptAccessibilityService.requestTest(method);
        Toast.makeText(this, ok ? "실제 전송 시험을 시작합니다" : "접근성 서비스를 먼저 켜주세요", Toast.LENGTH_SHORT).show();
    }

    private void applyAutomationSetting() {
        saveBasicValues(false);
        boolean want = enabledSwitch.isChecked();
        if (want && !AutomationPrefs.setupPassed(this)) {
            enabledSwitch.setChecked(false);
            Toast.makeText(this, "전송 방식 시험을 먼저 통과해야 자동 진행을 켤 수 있습니다", Toast.LENGTH_LONG).show();
            return;
        }
        AutomationPrefs.setEnabled(this, want);
        refreshStatus();
    }

    private void loadValues() {
        SharedPreferences p = AutomationPrefs.get(this);
        intervalEdit.setText(String.valueOf(p.getInt(AutomationPrefs.KEY_INTERVAL, AutomationPrefs.DEFAULT_INTERVAL_MINUTES)));
        messageEdit.setText(p.getString(AutomationPrefs.KEY_MESSAGE, AutomationPrefs.DEFAULT_MESSAGE));
        enabledSwitch.setChecked(p.getBoolean(AutomationPrefs.KEY_ENABLED, false));
        refreshStatus();
    }

    private void saveBasicValues() {
        saveBasicValues(true);
    }

    private void saveBasicValues(boolean toast) {
        int interval = AutomationPrefs.DEFAULT_INTERVAL_MINUTES;
        try { interval = Integer.parseInt(intervalEdit.getText().toString().trim()); } catch (Exception ignored) {}
        interval = Math.max(1, Math.min(240, interval));
        String msg = messageEdit.getText().toString().trim();
        if (msg.isEmpty()) msg = AutomationPrefs.DEFAULT_MESSAGE;
        AutomationPrefs.saveBasic(this, interval, msg);
        intervalEdit.setText(String.valueOf(interval));
        messageEdit.setText(msg);
        if (toast) Toast.makeText(this, "저장했습니다", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean serviceOn = isAccessibilityServiceEnabled(this, ChatGptAccessibilityService.class);
        serviceStatus.setText(serviceOn ? "접근성 서비스: 켜짐" : "접근성 서비스: 꺼짐 — 먼저 켜주세요");

        SharedPreferences p = AutomationPrefs.get(this);
        String inspect = p.getString(AutomationPrefs.KEY_INSPECT_SUMMARY, "아직 검사하지 않음");
        inspectStatus.setText("검사 결과: " + inspect);

        boolean passed = p.getBoolean(AutomationPrefs.KEY_SETUP_PASSED, false);
        String method = p.getString(AutomationPrefs.KEY_VERIFIED_METHOD, AutomationPrefs.METHOD_NONE);
        verificationStatus.setText(passed
                ? "검증 완료: " + AutomationPrefs.methodLabel(method)
                : "검증 미완료: 자동 진행을 켤 수 없음");

        enabledSwitch.setChecked(p.getBoolean(AutomationPrefs.KEY_ENABLED, false));
        String last = p.getString(AutomationPrefs.KEY_LAST_STATUS, "아직 실행 기록 없음");
        long sent = p.getLong(AutomationPrefs.KEY_LAST_SENT, 0L);
        long due = p.getLong(AutomationPrefs.KEY_NEXT_DUE, 0L);
        int failures = p.getInt(AutomationPrefs.KEY_FAIL_COUNT, 0);
        runStatus.setText("최근 상태: " + last
                + "\n마지막 성공: " + AutomationPrefs.formatTime(sent)
                + "\n다음 예정: " + AutomationPrefs.formatTime(due)
                + "\n연속 실패: " + failures + "/2");
        logView.setText(p.getString(AutomationPrefs.KEY_LOG, ""));
    }

    private void openAutostartSettings() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            startActivity(intent);
        } catch (Throwable ignored) {
            try {
                Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(details);
            } catch (Throwable t) {
                Toast.makeText(this, "자동 시작 설정 화면을 열 수 없습니다", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static boolean isAccessibilityServiceEnabled(Context context, Class<?> serviceClass) {
        String expected = new ComponentName(context, serviceClass).flattenToString();
        String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String[] parts = enabled.split(":");
        for (String part : parts) if (expected.equalsIgnoreCase(part)) return true;
        return false;
    }

    private TextView section(String s) {
        TextView t = text(s, 18, true);
        t.setPadding(0, dp(20), 0, dp(6));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 14, true);
        t.setPadding(0, dp(10), 0, dp(4));
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
