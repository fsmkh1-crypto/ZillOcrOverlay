package com.fsmkh1.chatgptautocontinue;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
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
    private static final long AFTER_TEXT_DELAY_MS = 900L;

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
            if (editor == null) {
                logUiDiagnostic(root, "입력창 탐지 실패");
                root.recycle();
                AutomationPrefs.retrySoon(this, "대화 입력창을 찾지 못함");
                actionInProgress = false;
                return;
            }

            Rect editorBounds = new Rect();
            editor.getBoundsInScreen(editorBounds);
            root.recycle();

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
            Rect rememberedBounds = new Rect(editorBounds);
            handler.postDelayed(() -> clickSend(message, rememberedBounds), AFTER_TEXT_DELAY_MS);
        } catch (Throwable t) {
            AutomationPrefs.retrySoon(this, "자동화 오류: " + t.getClass().getSimpleName());
            actionInProgress = false;
        }
    }

    private void clickSend(String expectedMessage, Rect rememberedEditorBounds) {
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

            Rect rootBounds = new Rect();
            root.getBoundsInScreen(rootBounds);
            Rect composerBounds = new Rect(rememberedEditorBounds);
            boolean matches = false;

            AccessibilityNodeInfo editor = findPromptEditor(root);
            if (editor != null) {
                Rect fresh = new Rect();
                editor.getBoundsInScreen(fresh);
                if (!fresh.isEmpty()) composerBounds.set(fresh);
                CharSequence current = editor.getText();
                matches = current != null && expectedMessage.contentEquals(current.toString().trim());
                editor.recycle();
            }

            if (!matches) {
                AccessibilityNodeInfo messageNode = findMessageNodeNear(root, expectedMessage, composerBounds);
                if (messageNode != null) {
                    Rect fresh = new Rect();
                    messageNode.getBoundsInScreen(fresh);
                    if (!fresh.isEmpty()) composerBounds.set(fresh);
                    matches = true;
                    messageNode.recycle();
                }
            }

            if (!matches) {
                logUiDiagnostic(root, "전송 직전 입력내용 확인 실패");
                root.recycle();
                AutomationPrefs.retrySoon(this, "전송 직전 입력 내용을 확인하지 못함");
                return;
            }

            AccessibilityNodeInfo send = findSendButton(root, composerBounds, rootBounds);
            if (send != null) {
                boolean clicked = performClickUpTree(send);
                send.recycle();
                root.recycle();
                if (clicked) {
                    AutomationPrefs.markSent(this);
                } else {
                    AutomationPrefs.retrySoon(this, "보내기 버튼 클릭 실패");
                }
                return;
            }

            logUiDiagnostic(root, "보내기 버튼 라벨 없음 - 좌표 폴백");
            root.recycle();
            if (!composerBounds.isEmpty() && dispatchSendGesture(rootBounds, composerBounds, expectedMessage)) {
                AutomationPrefs.appendLog(this, "접근성 버튼이 없어 우측 전송 영역 탭을 시도함");
            } else {
                AutomationPrefs.retrySoon(this, "보내기 버튼/전송 영역을 찾지 못함");
            }
        } catch (Throwable t) {
            AutomationPrefs.retrySoon(this, "전송 오류: " + t.getClass().getSimpleName());
        } finally {
            actionInProgress = false;
        }
    }

    private boolean dispatchSendGesture(Rect rootBounds, Rect composerBounds, String expectedMessage) {
        if (rootBounds.isEmpty()) {
            rootBounds.set(0, 0, getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        }
        float x = rootBounds.right - dp(34);
        float y = composerBounds.centerY();
        if (y <= rootBounds.top || y >= rootBounds.bottom) y = rootBounds.bottom - dp(46);
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
                handler.postDelayed(() -> verifyGestureSend(expectedMessage), 900L);
            }

            @Override public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                AutomationPrefs.retrySoon(ChatGptAccessibilityService.this, "전송 영역 탭이 취소됨");
            }
        }, null);
    }

    private void verifyGestureSend(String expectedMessage) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !CHATGPT_PACKAGE.contentEquals(root.getPackageName())) {
            if (root != null) root.recycle();
            AutomationPrefs.markSent(this);
            return;
        }
        AccessibilityNodeInfo editor = findPromptEditor(root);
        root.recycle();
        if (editor == null) {
            AutomationPrefs.markSent(this);
            return;
        }
        CharSequence current = editor.getText();
        boolean stillThere = current != null && expectedMessage.contentEquals(current.toString().trim());
        editor.recycle();
        if (stillThere) AutomationPrefs.retrySoon(this, "전송 영역을 눌렀지만 문구가 그대로 남아 있음");
        else AutomationPrefs.markSent(this);
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
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        if (rootBounds.isEmpty()) {
            rootBounds.set(0, 0, getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        }
        int lowerLine = rootBounds.top + (int) (rootBounds.height() * 0.52f);
        try {
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser() || !n.isEnabled()) continue;
                int score = 0;
                String clazz = safe(n.getClassName()).toLowerCase(Locale.ROOT);
                String id = safe(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
                String hint = safe(n.getHintText()).toLowerCase(Locale.ROOT);
                String desc = safe(n.getContentDescription()).toLowerCase(Locale.ROOT);
                String text = safe(n.getText());
                Rect b = new Rect();
                n.getBoundsInScreen(b);

                if (n.isEditable()) score += 180;
                if (supportsSetText(n)) score += 150;
                if (clazz.contains("edittext") || clazz.contains("textfield")) score += 110;
                if (n.isFocused()) score += 45;
                if (n.isFocusable()) score += 20;
                if (id.contains("prompt") || id.contains("composer") || id.contains("input") || id.contains("message")) score += 45;
                if (hint.contains("message") || hint.contains("메시지") || hint.contains("질문") || hint.contains("chatgpt") || hint.contains("ask")) score += 45;
                if (desc.contains("message") || desc.contains("메시지") || desc.contains("prompt")) score += 30;
                if (!b.isEmpty() && b.centerY() >= lowerLine) score += 55;
                if (!b.isEmpty() && b.width() >= rootBounds.width() * 0.35f) score += 25;
                if (text.length() > 1200) score -= 100;

                if (score > bestScore && score >= 145) {
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

    private AccessibilityNodeInfo findMessageNodeNear(AccessibilityNodeInfo root, String expected, Rect rememberedBounds) {
        List<AccessibilityNodeInfo> nodes = flatten(root);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        try {
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser() || !n.isEnabled()) continue;
                String text = safe(n.getText()).trim();
                if (!expected.equals(text)) continue;
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                int score = 100;
                if (n.isEditable()) score += 120;
                if (supportsSetText(n)) score += 100;
                if (!rememberedBounds.isEmpty()) {
                    int dy = Math.abs(b.centerY() - rememberedBounds.centerY());
                    if (dy <= dp(28)) score += 100;
                    else if (dy <= dp(72)) score += 50;
                    else score -= 100;
                }
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

    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo root, Rect composerBounds, Rect rootBounds) {
        List<AccessibilityNodeInfo> nodes = flatten(root);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        try {
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser() || !n.isEnabled()) continue;
                String label = combinedText(n).toLowerCase(Locale.ROOT);
                String id = safe(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                int score = 0;

                if (label.contains("send prompt") || label.contains("submit prompt")) score += 260;
                if (label.equals("send") || label.equals("보내기") || label.equals("전송")) score += 240;
                if (label.contains("보내기") || label.contains("전송") || label.contains("submit")) score += 190;
                if (id.contains("send") || id.contains("submit")) score += 220;
                if (n.isClickable()) score += 70;
                else if (hasClickableParent(n, 3)) score += 45;

                boolean nearComposer = false;
                if (!composerBounds.isEmpty() && !b.isEmpty()) {
                    int maxDy = Math.max(dp(72), composerBounds.height() * 2);
                    int dy = Math.abs(b.centerY() - composerBounds.centerY());
                    if (dy <= maxDy && b.centerX() >= composerBounds.centerX()) {
                        nearComposer = true;
                        score += 110;
                        if (!rootBounds.isEmpty() && b.centerX() >= rootBounds.left + rootBounds.width() * 0.72f) score += 70;
                        score += Math.min(60, Math.max(0, (b.centerX() - composerBounds.centerX()) / Math.max(1, dp(3))));
                    }
                }

                if (label.contains("voice") || label.contains("음성") || label.contains("microphone") ||
                        label.contains("마이크") || label.contains("camera") || label.contains("카메라") ||
                        label.contains("attach") || label.contains("첨부") || label.contains("photo") || label.contains("사진")) {
                    score -= 180;
                }

                if ((label.contains("send") || label.contains("보내기") || label.contains("전송") || id.contains("send") || id.contains("submit") || nearComposer)
                        && score > bestScore && score >= 150) {
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
        AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain(start);
        try {
            for (int i = 0; i < 6 && n != null; i++) {
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

    private void logUiDiagnostic(AccessibilityNodeInfo root, String reason) {
        try {
            List<AccessibilityNodeInfo> nodes = flatten(root);
            Rect rootBounds = new Rect();
            root.getBoundsInScreen(rootBounds);
            int lowerLine = rootBounds.top + (int) (rootBounds.height() * 0.55f);
            StringBuilder sb = new StringBuilder("UI진단[").append(reason).append("]: ");
            int added = 0;
            try {
                for (AccessibilityNodeInfo n : nodes) {
                    if (!n.isVisibleToUser()) continue;
                    Rect b = new Rect();
                    n.getBoundsInScreen(b);
                    if (!rootBounds.isEmpty() && b.centerY() < lowerLine) continue;
                    String text = combinedText(n).replace('\n', ' ').trim();
                    boolean interesting = n.isEditable() || n.isClickable() || supportsSetText(n) || !text.isEmpty();
                    if (!interesting) continue;
                    if (text.length() > 28) text = text.substring(0, 28) + "…";
                    if (added++ > 0) sb.append(" | ");
                    sb.append(safe(n.getClassName())).append(" e=").append(n.isEditable() ? 1 : 0)
                            .append(" c=").append(n.isClickable() ? 1 : 0)
                            .append(" t=").append(text)
                            .append(" @").append(b.left).append(',').append(b.top).append('-').append(b.right).append(',').append(b.bottom);
                    if (added >= 5) break;
                }
            } finally {
                recycleList(nodes);
            }
            AutomationPrefs.appendLog(this, sb.toString());
        } catch (Throwable ignored) {}
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safe(CharSequence s) { return s == null ? "" : s.toString(); }
    private static String safe(String s) { return s == null ? "" : s; }
}
