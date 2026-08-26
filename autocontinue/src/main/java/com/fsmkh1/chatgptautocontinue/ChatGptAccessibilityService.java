package com.fsmkh1.chatgptautocontinue;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ChatGptAccessibilityService extends AccessibilityService {
    private static final String CHATGPT_PACKAGE = "com.openai.chatgpt";
    private static final long TICK_MS = 15_000L;
    private static final long AFTER_OPEN_MS = 2_200L;
    private static final long AFTER_TEXT_MS = 650L;
    private static final long VERIFY_MS = 500L;
    private static final int VERIFY_ATTEMPTS = 8;

    private static final String OP_NONE = "none";
    private static final String OP_INSPECT = "inspect";
    private static final String OP_TEST_SEMANTIC = "test_semantic";
    private static final String OP_TEST_IME = "test_ime";
    private static final String OP_TEST_COORD = "test_coord";
    private static final String OP_RUN_NOW = "run_now";
    private static final String OP_AUTO = "auto";

    private static volatile ChatGptAccessibilityService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean actionInProgress = false;
    private boolean injectedOurMessage = false;
    private String currentOperation = OP_NONE;
    private int operationToken = 0;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            try {
                runAutoIfDue();
            } finally {
                handler.postDelayed(this, TICK_MS);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AutomationPrefs.ensureSchedule(this);
        AutomationPrefs.setStatus(this, "접근성 서비스 연결됨");
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // v2.1: 접근성 이벤트는 자동 실행 트리거로 사용하지 않는다.
        // ticker 단일 경로만 사용해 중복 실행을 막는다.
    }

    @Override public void onInterrupt() {
        AutomationPrefs.setStatus(this, "접근성 서비스 중단됨");
    }

    @Override public void onDestroy() {
        instance = null;
        operationToken++;
        actionInProgress = false;
        injectedOurMessage = false;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    public static boolean requestInspect() {
        ChatGptAccessibilityService s = instance;
        if (s == null) return false;
        s.handler.post(() -> s.beginOperation(OP_INSPECT));
        return true;
    }

    public static boolean requestTest(String method) {
        ChatGptAccessibilityService s = instance;
        if (s == null) return false;
        String op;
        if (AutomationPrefs.METHOD_SEMANTIC.equals(method)) op = OP_TEST_SEMANTIC;
        else if (AutomationPrefs.METHOD_IME.equals(method)) op = OP_TEST_IME;
        else if (AutomationPrefs.METHOD_COORD.equals(method)) op = OP_TEST_COORD;
        else return false;
        String finalOp = op;
        s.handler.post(() -> s.beginOperation(finalOp));
        return true;
    }

    public static boolean requestRunNow() {
        ChatGptAccessibilityService s = instance;
        if (s == null) return false;
        s.handler.post(() -> s.beginOperation(OP_RUN_NOW));
        return true;
    }

    private void runAutoIfDue() {
        if (actionInProgress) return;
        if (!AutomationPrefs.enabled(this) || !AutomationPrefs.setupPassed(this)) return;
        long due = AutomationPrefs.nextDue(this);
        if (due <= 0L || System.currentTimeMillis() < due) return;
        if (!isDeviceReady()) return;
        beginOperation(OP_AUTO);
    }

    private void beginOperation(String operation) {
        if (actionInProgress) {
            AutomationPrefs.setStatus(this, "다른 작업이 진행 중이라 요청을 무시함");
            return;
        }

        if (!isDeviceReady()) {
            if (OP_AUTO.equals(operation)) AutomationPrefs.defer(this, "화면이 꺼져 있거나 잠금 상태임", 2);
            else AutomationPrefs.recordFailure(this, "화면이 꺼져 있거나 잠금 상태임", false);
            return;
        }

        if ((OP_AUTO.equals(operation) || OP_RUN_NOW.equals(operation)) && !AutomationPrefs.setupPassed(this)) {
            AutomationPrefs.recordFailure(this, "검증된 전송 방식이 없음", OP_AUTO.equals(operation));
            return;
        }

        actionInProgress = true;
        injectedOurMessage = false;
        currentOperation = operation;
        int token = ++operationToken;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean visible = isChatGptRoot(root);
        if (root != null) root.recycle();

        if (visible) {
            handler.postDelayed(() -> runOperation(token), 250L);
            return;
        }

        if (!openChatGpt()) {
            fail(token, "ChatGPT 앱을 열 수 없음");
            return;
        }
        handler.postDelayed(() -> runOperation(token), AFTER_OPEN_MS);
    }

    private void runOperation(int token) {
        if (!isCurrent(token)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (!isChatGptRoot(root)) {
            if (root != null) root.recycle();
            fail(token, "ChatGPT 대화 화면을 확인하지 못함");
            return;
        }

        if (OP_INSPECT.equals(currentOperation)) {
            inspectScreen(root, token);
            return;
        }

        if (isGenerating(root)) {
            root.recycle();
            if (OP_AUTO.equals(currentOperation)) deferAuto(token, "ChatGPT가 아직 응답 생성 중임", 2);
            else fail(token, "ChatGPT가 아직 응답 생성 중임");
            return;
        }

        AccessibilityNodeInfo editor = findPromptEditor(root);
        if (editor == null) {
            root.recycle();
            fail(token, "대화 입력창을 찾지 못함 — 화면 검사를 다시 실행하세요");
            return;
        }

        CharSequence beforeText = editor.getText();
        String before = beforeText == null ? "" : beforeText.toString().trim();
        if (!before.isEmpty()) {
            editor.recycle();
            root.recycle();
            if (OP_AUTO.equals(currentOperation)) deferAuto(token, "입력창에 기존 문구가 있어 덮어쓰지 않음", 5);
            else fail(token, "입력창에 기존 문구가 있어 덮어쓰지 않음");
            return;
        }

        String message = AutomationPrefs.message(this);
        int beforeCount = countExactMessageNodes(root, message);
        root.recycle();

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message);
        boolean set = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        editor.recycle();
        if (!set) {
            fail(token, "입력창에 문구를 넣지 못함");
            return;
        }
        injectedOurMessage = true;
        handler.postDelayed(() -> performSend(token, message, beforeCount), AFTER_TEXT_MS);
    }

    private void inspectScreen(AccessibilityNodeInfo root, int token) {
        AccessibilityNodeInfo editor = findPromptEditor(root);
        boolean editorOk = editor != null;
        boolean semantic = false;
        boolean ime = false;
        boolean coord = false;

        if (editor != null) {
            Rect bounds = new Rect();
            editor.getBoundsInScreen(bounds);
            ime = supportsImeEnter(editor);
            coord = !bounds.isEmpty();
            AccessibilityNodeInfo send = findSemanticSendButton(root, bounds);
            semantic = send != null;
            if (send != null) send.recycle();
            editor.recycle();
        }
        root.recycle();

        String summary = "입력창 " + yn(editorOk)
                + " / 버튼 " + yn(semantic)
                + " / IME " + yn(ime)
                + " / 좌표 " + yn(coord);
        AutomationPrefs.saveInspection(this, editorOk, semantic, ime, coord, summary);
        finishOperation(token);
    }

    private void performSend(int token, String expectedMessage, int beforeCount) {
        if (!isCurrent(token)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (!isChatGptRoot(root)) {
            if (root != null) root.recycle();
            fail(token, "전송 직전 ChatGPT 화면이 바뀜");
            return;
        }
        if (isGenerating(root)) {
            root.recycle();
            fail(token, "전송 직전 응답 생성이 시작됨");
            return;
        }

        AccessibilityNodeInfo editor = findPromptEditor(root);
        if (editor == null) {
            root.recycle();
            fail(token, "전송 직전 입력창을 다시 찾지 못함");
            return;
        }
        CharSequence nowText = editor.getText();
        String now = nowText == null ? "" : nowText.toString().trim();
        if (!expectedMessage.equals(now)) {
            editor.recycle();
            root.recycle();
            fail(token, "입력 내용이 바뀌어 전송하지 않음");
            return;
        }

        Rect editorBounds = new Rect();
        editor.getBoundsInScreen(editorBounds);
        String method = methodForCurrentOperation();

        if (AutomationPrefs.METHOD_SEMANTIC.equals(method)) {
            AccessibilityNodeInfo send = findSemanticSendButton(root, editorBounds);
            editor.recycle();
            root.recycle();
            if (send == null) {
                fail(token, "검증된 보내기 버튼 방식이 현재 화면에서 보이지 않음");
                return;
            }
            boolean clicked = performClickUpTree(send);
            send.recycle();
            if (!clicked) {
                fail(token, "보내기 버튼 클릭 실패");
                return;
            }
            scheduleVerify(token, expectedMessage, beforeCount, method, 0);
            return;
        }

        if (AutomationPrefs.METHOD_IME.equals(method)) {
            boolean supported = supportsImeEnter(editor);
            boolean invoked = false;
            if (supported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                invoked = editor.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            }
            editor.recycle();
            root.recycle();
            if (!invoked) {
                fail(token, "IME 전송 액션을 실행하지 못함");
                return;
            }
            scheduleVerify(token, expectedMessage, beforeCount, method, 0);
            return;
        }

        if (AutomationPrefs.METHOD_COORD.equals(method)) {
            editor.recycle();
            Rect rootBounds = rootBounds(root);
            root.recycle();
            if (editorBounds.isEmpty() || !dispatchCoordinateSend(token, rootBounds, editorBounds, expectedMessage, beforeCount)) {
                fail(token, "좌표 전송 동작을 시작하지 못함");
            }
            return;
        }

        editor.recycle();
        root.recycle();
        fail(token, "검증된 전송 방식이 없음");
    }

    private String methodForCurrentOperation() {
        if (OP_TEST_SEMANTIC.equals(currentOperation)) return AutomationPrefs.METHOD_SEMANTIC;
        if (OP_TEST_IME.equals(currentOperation)) return AutomationPrefs.METHOD_IME;
        if (OP_TEST_COORD.equals(currentOperation)) return AutomationPrefs.METHOD_COORD;
        if (OP_AUTO.equals(currentOperation) || OP_RUN_NOW.equals(currentOperation)) return AutomationPrefs.verifiedMethod(this);
        return AutomationPrefs.METHOD_NONE;
    }

    private boolean dispatchCoordinateSend(int token, Rect rootBounds, Rect editorBounds, String expected, int beforeCount) {
        float x = editorBounds.right - dp(28);
        float y = editorBounds.centerY();
        x = Math.max(rootBounds.left + dp(12), Math.min(x, rootBounds.right - dp(12)));
        y = Math.max(rootBounds.top + dp(12), Math.min(y, rootBounds.bottom - dp(12)));

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 80))
                .build();
        return dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                scheduleVerify(token, expected, beforeCount, AutomationPrefs.METHOD_COORD, 0);
            }

            @Override public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                fail(token, "좌표 탭이 취소됨");
            }
        }, null);
    }

    private void scheduleVerify(int token, String expected, int beforeCount, String method, int attempt) {
        handler.postDelayed(() -> verifyOutcome(token, expected, beforeCount, method, attempt), VERIFY_MS);
    }

    private void verifyOutcome(int token, String expected, int beforeCount, String method, int attempt) {
        if (!isCurrent(token)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (!isChatGptRoot(root)) {
            if (root != null) root.recycle();
            if (attempt + 1 < VERIFY_ATTEMPTS) scheduleVerify(token, expected, beforeCount, method, attempt + 1);
            else fail(token, "전송 후 ChatGPT 화면을 확인하지 못함");
            return;
        }

        boolean generating = isGenerating(root);
        int afterCount = countExactMessageNodes(root, expected);
        boolean composerStillExact = composerContainsExact(root, expected);
        root.recycle();

        if (generating || (afterCount > beforeCount && !composerStillExact)) {
            injectedOurMessage = false;
            if (OP_AUTO.equals(currentOperation)) {
                AutomationPrefs.markAutoSuccess(this);
            } else if (OP_RUN_NOW.equals(currentOperation)) {
                AutomationPrefs.setStatus(this, "수동 즉시 실행 성공");
            } else {
                AutomationPrefs.verifyMethod(this, method);
            }
            finishOperation(token);
            return;
        }

        if (attempt + 1 < VERIFY_ATTEMPTS) scheduleVerify(token, expected, beforeCount, method, attempt + 1);
        else fail(token, "전송 동작은 했지만 실제 전송 증거를 확인하지 못함");
    }

    private boolean composerContainsExact(AccessibilityNodeInfo root, String expected) {
        AccessibilityNodeInfo editor = findPromptEditor(root);
        if (editor == null) return false;
        try {
            CharSequence text = editor.getText();
            return text != null && expected.equals(text.toString().trim());
        } finally {
            editor.recycle();
        }
    }

    private int countExactMessageNodes(AccessibilityNodeInfo root, String expected) {
        int count = 0;
        List<AccessibilityNodeInfo> nodes = flatten(root);
        try {
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser()) continue;
                CharSequence text = n.getText();
                if (text != null && expected.equals(text.toString().trim())) count++;
            }
        } finally {
            recycleList(nodes);
        }
        return count;
    }

    private AccessibilityNodeInfo findPromptEditor(AccessibilityNodeInfo root) {
        Rect screen = rootBounds(root);
        int softLower = screen.top + (int) (screen.height() * 0.45f);
        int hardLower = screen.top + (int) (screen.height() * 0.62f);
        List<AccessibilityNodeInfo> nodes = flatten(root);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        try {
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser() || !n.isEnabled()) continue;
                if (!(n.isEditable() || supportsSetText(n))) continue;

                Rect b = new Rect();
                n.getBoundsInScreen(b);
                if (b.isEmpty()) continue;

                String id = safe(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
                String hint = safe(n.getHintText()).toLowerCase(Locale.ROOT);
                String desc = safe(n.getContentDescription()).toLowerCase(Locale.ROOT);
                String clazz = safe(n.getClassName()).toLowerCase(Locale.ROOT);

                boolean semantic = containsAny(id, "prompt", "composer", "message", "input")
                        || containsAny(hint, "message", "메시지", "질문", "chatgpt", "ask")
                        || containsAny(desc, "message", "메시지", "prompt", "composer");
                boolean lowerEnough = b.centerY() >= softLower;
                boolean deepBottom = b.bottom >= hardLower;
                boolean wide = b.width() >= screen.width() * 0.32f;
                boolean editorClass = clazz.contains("edittext") || clazz.contains("textfield");

                if (!(semantic && lowerEnough) && !(deepBottom && wide && editorClass)) continue;

                int score = 0;
                if (semantic) score += 250;
                if (n.isEditable()) score += 120;
                if (supportsSetText(n)) score += 100;
                if (deepBottom) score += 80;
                if (wide) score += 50;
                if (n.isFocused()) score += 20;
                if (score > bestScore) {
                    if (best != null) best.recycle();
                    best = AccessibilityNodeInfo.obtain(n);
                    bestScore = score;
                }
            }
        } finally {
            recycleList(nodes);
        }
        return best;
    }

    private AccessibilityNodeInfo findSemanticSendButton(AccessibilityNodeInfo root, Rect editorBounds) {
        List<AccessibilityNodeInfo> nodes = flatten(root);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        try {
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser() || !n.isEnabled()) continue;
                String label = combinedText(n).toLowerCase(Locale.ROOT);
                String id = safe(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
                if (containsAny(label, "voice", "음성", "microphone", "마이크", "camera", "카메라", "attach", "첨부", "photo", "사진")) continue;

                boolean semantic = containsAny(label, "send prompt", "submit prompt", "보내기", "전송")
                        || label.equals("send") || label.equals("submit")
                        || containsAny(id, "send", "submit");
                if (!semantic) continue;

                Rect b = new Rect();
                n.getBoundsInScreen(b);
                int score = 200;
                if (n.isClickable()) score += 80;
                else if (hasClickableParent(n, 3)) score += 50;
                if (!editorBounds.isEmpty() && !b.isEmpty()) {
                    int dy = Math.abs(b.centerY() - editorBounds.centerY());
                    int maxDy = Math.max(dp(90), editorBounds.height() * 2);
                    if (dy <= maxDy && b.centerX() >= editorBounds.centerX()) score += 120;
                    else score -= 120;
                }
                if (score > bestScore) {
                    if (best != null) best.recycle();
                    best = AccessibilityNodeInfo.obtain(n);
                    bestScore = score;
                }
            }
        } finally {
            recycleList(nodes);
        }
        return best;
    }

    private boolean supportsSetText(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo.AccessibilityAction a : node.getActionList()) {
            if (a.getId() == AccessibilityNodeInfo.ACTION_SET_TEXT) return true;
        }
        return false;
    }

    private boolean supportsImeEnter(AccessibilityNodeInfo node) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        int target = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId();
        for (AccessibilityNodeInfo.AccessibilityAction a : node.getActionList()) {
            if (a.getId() == target) return true;
        }
        return false;
    }

    private boolean isGenerating(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = flatten(root);
        try {
            for (AccessibilityNodeInfo n : nodes) {
                String label = combinedText(n).toLowerCase(Locale.ROOT);
                String id = safe(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
                if (containsAny(label, "stop generating", "stop response", "생성 중지", "응답 중지", "생성 멈추기", "응답 멈추기")) return true;
                if (id.contains("stop") && n.isClickable()) return true;
            }
        } finally {
            recycleList(nodes);
        }
        return false;
    }

    private boolean hasClickableParent(AccessibilityNodeInfo start, int maxDepth) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(start);
        try {
            for (int i = 0; i < maxDepth && current != null; i++) {
                if (current.isClickable() && current.isEnabled()) return true;
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
            return false;
        } finally {
            if (current != null) current.recycle();
        }
    }

    private boolean performClickUpTree(AccessibilityNodeInfo start) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(start);
        try {
            for (int i = 0; i < 6 && current != null; i++) {
                if (current.isClickable() && current.isEnabled()
                        && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
            return false;
        } finally {
            if (current != null) current.recycle();
        }
    }

    private Rect rootBounds(AccessibilityNodeInfo root) {
        Rect r = new Rect();
        root.getBoundsInScreen(r);
        if (r.isEmpty()) r.set(0, 0, getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        return r;
    }

    private boolean openChatGpt() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(CHATGPT_PACKAGE);
            if (launch == null) return false;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(launch);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isDeviceReady() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        return pm != null && pm.isInteractive() && km != null && !km.isKeyguardLocked();
    }

    private boolean isChatGptRoot(AccessibilityNodeInfo root) {
        return root != null && CHATGPT_PACKAGE.contentEquals(root.getPackageName());
    }

    private boolean isCurrent(int token) {
        return actionInProgress && token == operationToken;
    }

    private void deferAuto(int token, String reason, int minutes) {
        if (!isCurrent(token)) return;
        AutomationPrefs.defer(this, reason, minutes);
        finishOperation(token);
    }

    private void fail(int token, String reason) {
        if (!isCurrent(token)) return;
        if (injectedOurMessage) clearInjectedMessageIfStillPresent();
        boolean auto = OP_AUTO.equals(currentOperation);
        AutomationPrefs.recordFailure(this, reason, auto);
        finishOperation(token);
    }

    private void clearInjectedMessageIfStillPresent() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (!isChatGptRoot(root)) {
                if (root != null) root.recycle();
                return;
            }
            AccessibilityNodeInfo editor = findPromptEditor(root);
            root.recycle();
            if (editor == null) return;
            CharSequence current = editor.getText();
            String value = current == null ? "" : current.toString().trim();
            if (AutomationPrefs.message(this).equals(value)) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
                editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            }
            editor.recycle();
        } catch (Throwable ignored) {}
        injectedOurMessage = false;
    }

    private void finishOperation(int token) {
        if (!isCurrent(token)) return;
        actionInProgress = false;
        currentOperation = OP_NONE;
        injectedOurMessage = false;
        operationToken++;
        // v2.1: 여기서 GLOBAL_ACTION_BACK, controller foreground 전환 등 화면 이동을 절대 하지 않는다.
    }

    private List<AccessibilityNodeInfo> flatten(AccessibilityNodeInfo root) {
        ArrayList<AccessibilityNodeInfo> out = new ArrayList<>();
        ArrayList<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        int cursor = 0;
        while (cursor < queue.size() && out.size() < 900) {
            AccessibilityNodeInfo n = queue.get(cursor++);
            out.add(n);
            int count = n.getChildCount();
            for (int i = 0; i < count && queue.size() < 1200; i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return out;
    }

    private void recycleList(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) {
            try { n.recycle(); } catch (Throwable ignored) {}
        }
    }

    private String combinedText(AccessibilityNodeInfo n) {
        return (safe(n.getText()) + " " + safe(n.getContentDescription()) + " " + safe(n.getHintText())).trim();
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null) return false;
        for (String n : needles) if (text.contains(n)) return true;
        return false;
    }

    private String yn(boolean value) { return value ? "O" : "X"; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String safe(CharSequence s) { return s == null ? "" : s.toString(); }
    private static String safe(String s) { return s == null ? "" : s; }
}
