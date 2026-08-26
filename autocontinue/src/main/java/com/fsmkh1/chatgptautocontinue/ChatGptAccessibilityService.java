package com.fsmkh1.chatgptautocontinue;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
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
    private static final long TICK_MS = 20_000L;
    private static final long AFTER_OPEN_DELAY_MS = 2_500L;
    private static final long AFTER_TEXT_DELAY_MS = 650L;

    private static volatile ChatGptAccessibilityService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean actionInProgress = false;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tryRun(false);
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AutomationPrefs.setStatus(this, "접근성 서비스 연결됨");
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!AutomationPrefs.enabled(this) || actionInProgress) return;
        long due = AutomationPrefs.nextDue(this);
        if (due > 0 && System.currentTimeMillis() >= due) {
            handler.removeCallbacks(runOnCurrentScreen);
            handler.postDelayed(runOnCurrentScreen, 350L);
        }
    }

    @Override public void onInterrupt() {
        AutomationPrefs.setStatus(this, "접근성 서비스 중단됨");
    }

    @Override public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private final Runnable runOnCurrentScreen = () -> tryRun(false);

    public static boolean requestRunNow() {
        ChatGptAccessibilityService s = instance;
        if (s == null) return false;
        s.handler.post(() -> s.tryRun(true));
        return true;
    }

    private void tryRun(boolean force) {
        if (actionInProgress) return;
        if (!AutomationPrefs.enabled(this) && !force) return;
        long now = System.currentTimeMillis();
        long due = AutomationPrefs.nextDue(this);
        if (!force && due > now) return;

        if (!isDeviceReady()) {
            AutomationPrefs.retrySoon(this, "화면이 꺼져 있거나 잠금 상태임");
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean chatGptVisible = root != null && CHATGPT_PACKAGE.contentEquals(root.getPackageName());
        if (root != null) root.recycle();
        if (!chatGptVisible) {
            if (!openChatGpt()) {
                AutomationPrefs.retrySoon(this, "ChatGPT 앱을 열 수 없음");
                return;
            }
            actionInProgress = true;
            handler.postDelayed(() -> {
                actionInProgress = false;
                performOnChatGpt();
            }, AFTER_OPEN_DELAY_MS);
            return;
        }
        performOnChatGpt();
    }

    private void performOnChatGpt() {
        if (actionInProgress) return;
        actionInProgress = true;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !CHATGPT_PACKAGE.contentEquals(root.getPackageName())) {
                if (root != null) root.recycle();
                AutomationPrefs.retrySoon(this, "ChatGPT 화면을 확인하지 못함");
                actionInProgress = false;
                return;
            }

            if (isGenerating(root)) {
                root.recycle();
                AutomationPrefs.retrySoon(this, "ChatGPT가 아직 응답 생성 중임");
                actionInProgress = false;
                return;
            }

            AccessibilityNodeInfo editor = findPromptEditor(root);
            root.recycle();
            if (editor == null) {
                AutomationPrefs.retrySoon(this, "대화 입력창을 찾지 못함");
                actionInProgress = false;
                return;
            }

            String message = AutomationPrefs.message(this);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message);
            boolean set = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            editor.recycle();

            if (!set) {
                AutomationPrefs.retrySoon(this, "입력창에 문구를 넣지 못함");
                actionInProgress = false;
                return;
            }
            handler.postDelayed(() -> clickSend(message), AFTER_TEXT_DELAY_MS);
        } catch (Throwable t) {
            AutomationPrefs.retrySoon(this, "자동화 오류: " + t.getClass().getSimpleName());
            actionInProgress = false;
        }
    }

    private void clickSend(String expectedMessage) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !CHATGPT_PACKAGE.contentEquals(root.getPackageName())) {
                if (root != null) root.recycle();
                AutomationPrefs.retrySoon(this, "전송 직전 ChatGPT 화면이 바뀜");
                return;
            }
            if (isGenerating(root)) {
                root.recycle();
                AutomationPrefs.retrySoon(this, "전송 직전 응답 생성이 시작됨");
                return;
            }

            AccessibilityNodeInfo editor = findPromptEditor(root);
            if (editor == null) {
                root.recycle();
                AutomationPrefs.retrySoon(this, "전송 직전 입력창을 찾지 못함");
                return;
            }
            CharSequence current = editor.getText();
            boolean matches = current != null && expectedMessage.contentEquals(current.toString().trim());
            editor.recycle();
            if (!matches) {
                root.recycle();
                AutomationPrefs.retrySoon(this, "입력 내용이 바뀌어 안전상 전송하지 않음");
                return;
            }

            AccessibilityNodeInfo send = findSendButton(root);
            root.recycle();
            if (send == null) {
                AutomationPrefs.retrySoon(this, "보내기 버튼을 찾지 못함");
                return;
            }
            boolean clicked = performClickUpTree(send);
            send.recycle();
            if (clicked) AutomationPrefs.markSent(this);
            else AutomationPrefs.retrySoon(this, "보내기 버튼 클릭 실패");
        } catch (Throwable t) {
            AutomationPrefs.retrySoon(this, "전송 오류: " + t.getClass().getSimpleName());
        } finally {
            actionInProgress = false;
        }
    }

    private boolean openChatGpt() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(CHATGPT_PACKAGE);
            if (launch == null) return false;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(launch);
            AutomationPrefs.setStatus(this, "ChatGPT 앱 열기 시도");
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

    private boolean isGenerating(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = flatten(root);
        try {
            for (AccessibilityNodeInfo n : nodes) {
                String text = combinedText(n).toLowerCase(Locale.ROOT);
                if (text.contains("stop generating") || text.contains("stop response") ||
                        text.contains("생성 중지") || text.contains("응답 중지") ||
                        text.contains("생성 멈추기") || text.contains("응답 멈추기")) return true;
                String id = safe(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
                if (id.contains("stop") && n.isClickable()) return true;
            }
            return false;
        } finally {
            recycleList(nodes);
        }
    }

    private AccessibilityNodeInfo findPromptEditor(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = flatten(root);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        try {
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser() || !n.isEnabled()) continue;
                int score = 0;
                String clazz = safe(n.getClassName()).toLowerCase(Locale.ROOT);
                String id = safe(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
                String hint = safe(n.getHintText()).toLowerCase(Locale.ROOT);
                String desc = safe(n.getContentDescription()).toLowerCase(Locale.ROOT);
                if (n.isEditable()) score += 100;
                if (clazz.contains("edittext")) score += 60;
                if (supportsSetText(n)) score += 40;
                if (id.contains("prompt") || id.contains("composer") || id.contains("input") || id.contains("message")) score += 25;
                if (hint.contains("message") || hint.contains("메시지") || hint.contains("질문") || hint.contains("chatgpt")) score += 20;
                if (desc.contains("message") || desc.contains("메시지")) score += 10;
                if (score > bestScore && score >= 100) {
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

    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = flatten(root);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        try {
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser() || !n.isEnabled()) continue;
                String label = combinedText(n).toLowerCase(Locale.ROOT);
                String id = safe(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
                int score = 0;
                if (label.contains("send prompt")) score += 120;
                if (label.equals("send") || label.equals("보내기") || label.equals("전송")) score += 110;
                if (label.contains("보내기") || label.contains("전송")) score += 90;
                if (id.contains("send") || id.contains("submit")) score += 100;
                if (n.isClickable()) score += 25;
                if (score > bestScore && score >= 100) {
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

    private boolean performClickUpTree(AccessibilityNodeInfo start) {
        AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain(start);
        try {
            for (int i = 0; i < 5 && n != null; i++) {
                if (n.isClickable() && n.isEnabled() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                AccessibilityNodeInfo parent = n.getParent();
                n.recycle();
                n = parent;
            }
            return false;
        } finally {
            if (n != null) n.recycle();
        }
    }

    private List<AccessibilityNodeInfo> flatten(AccessibilityNodeInfo root) {
        ArrayList<AccessibilityNodeInfo> out = new ArrayList<>();
        ArrayList<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        int cursor = 0;
        while (cursor < queue.size() && out.size() < 700) {
            AccessibilityNodeInfo n = queue.get(cursor++);
            out.add(n);
            int count = n.getChildCount();
            for (int i = 0; i < count && queue.size() < 1000; i++) {
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

    private static String safe(CharSequence s) { return s == null ? "" : s.toString(); }
    private static String safe(String s) { return s == null ? "" : s; }
}
