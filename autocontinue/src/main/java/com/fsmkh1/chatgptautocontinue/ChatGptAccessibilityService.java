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
    private static final long VERIFY_DELAY_MS = 650L;
    private static final int VERIFY_ATTEMPTS = 5;

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
                fail("ChatGPT 화면을 확인하지 못함");
                return;
            }

            if (isGenerating(root)) {
                root.recycle();
                fail("ChatGPT가 아직 응답 생성 중임");
                return;
            }

            String guard = AutomationPrefs.conversationGuard(this);
            if (!guard.isEmpty() && !headerContainsGuard(root, guard)) {
                root.recycle();
                fail("대화방 키워드가 화면 상단에서 확인되지 않음");
                return;
            }

            AccessibilityNodeInfo editor = findPromptEditor(root);
            if (editor == null) {
                logUiDiagnostic(root, "입력창 탐지 실패");
                root.recycle();
                fail("대화 입력창을 찾지 못함");
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
                fail("입력창에 문구를 넣지 못함");
                return;
            }
            Rect rememberedBounds = new Rect(editorBounds);
            handler.postDelayed(() -> clickSend(message, rememberedBounds), AFTER_TEXT_DELAY_MS);
        } catch (Throwable t) {
            fail("자동화 오류: " + t.getClass().getSimpleName());
        }
    }

    private void clickSend(String expectedMessage, Rect rememberedEditorBounds) {
        boolean asyncVerificationStarted = false;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !CHATGPT_PACKAGE.contentEquals(root.getPackageName())) {
                if (root != null) root.recycle();
                fail("전송 직전 ChatGPT 화면이 바뀜");
                return;
            }
            if (isGenerating(root)) {
                root.recycle();
                fail("전송 직전 응답 생성이 시작됨");
                return;
            }

            String guard = AutomationPrefs.conversationGuard(this);
            if (!guard.isEmpty() && !headerContainsGuard(root, guard)) {
                root.recycle();
                fail("전송 직전 대화방 키워드 확인 실패");
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
                fail("전송 직전 입력 내용을 확인하지 못함");
                return;
            }

            AccessibilityNodeInfo send = findSendButton(root, composerBounds, rootBounds);
            if (send != null) {
                boolean clicked = performClickUpTree(send);
                send.recycle();
                root.recycle();
                if (!clicked) {
                    fail("보내기 버튼 클릭 실패");
                    return;
                }
                asyncVerificationStarted = true;
                Rect verifyBounds = new Rect(composerBounds);
                handler.postDelayed(() -> verifySendOutcome(expectedMessage, verifyBounds, 0, "버튼"), VERIFY_DELAY_MS);
                return;
            }

            logUiDiagnostic(root, "라벨된 보내기 버튼 없음 - 좌표 폴백");
            root.recycle();
            if (!composerBounds.isEmpty() && dispatchSendGesture(rootBounds, composerBounds, expectedMessage)) {
                asyncVerificationStarted = true;
                AutomationPrefs.appendLog(this, "좌표 폴백 탭 시도 — 성공 여부 검증 대기");
            } else {
                fail("보내기 버튼/전송 영역을 찾지 못함");
            }
        } catch (Throwable t) {
            fail("전송 오류: " + t.getClass().getSimpleName());
        } finally {
            if (!asyncVerificationStarted && actionInProgress) actionInProgress = false;
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
        Rect verifyBounds = new Rect(composerBounds);
        return dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                handler.postDelayed(() -> verifySendOutcome(expectedMessage, verifyBounds, 0, "좌표"), VERIFY_DELAY_MS);
            }

            @Override public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                fail("전송 영역 탭이 취소됨");
            }
        }, null);
    }

    private void verifySendOutcome(String expectedMessage, Rect oldComposerBounds, int attempt, String method) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !CHATGPT_PACKAGE.contentEquals(root.getPackageName())) {
                if (root != null) root.recycle();
                if (attempt + 1 < VERIFY_ATTEMPTS) {
                    handler.postDelayed(() -> verifySendOutcome(expectedMessage, oldComposerBounds, attempt + 1, method), VERIFY_DELAY_MS);
                } else {
                    fail("전송 후 ChatGPT 화면을 확인하지 못해 성공 처리하지 않음");
                }
                return;
            }

            boolean generating = isGenerating(root);
            boolean bubble = hasSentUserMessage(root, expectedMessage, oldComposerBounds);
            boolean composerCleared = isComposerCleared(root, expectedMessage);

            if (generating || (bubble && composerCleared)) {
                root.recycle();
                AutomationPrefs.appendLog(this, method + " 전송 검증 성공: " + (generating ? "응답 생성 감지" : "사용자 메시지 버블 확인"));
                AutomationPrefs.markSent(this);
                actionInProgress = false;
                return;
            }

            root.recycle();
            if (attempt + 1 < VERIFY_ATTEMPTS) {
                handler.postDelayed(() -> verifySendOutcome(expectedMessage, oldComposerBounds, attempt + 1, method), VERIFY_DELAY_MS);
            } else {
                fail("전송 동작 후 실제 전송 증거를 확인하지 못함");
            }
        } catch (Throwable t) {
            fail("전송 검증 오류: " + t.getClass().getSimpleName());
        }
    }

    private boolean isComposerCleared(AccessibilityNodeInfo root, String expectedMessage) {
        AccessibilityNodeInfo editor = findPromptEditor(root);
        if (editor == null) return false;
        try {
            CharSequence current = editor.getText();
            String value = current == null ? "" : current.toString().trim();
            return !expectedMessage.equals(value);
        } finally {
            editor.recycle();
        }
    }

    private boolean hasSentUserMessage(AccessibilityNodeInfo root, String expectedMessage, Rect oldComposerBounds) {
        List<AccessibilityNodeInfo> nodes = flatten(root);
        try {
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser() || n.isEditable() || supportsSetText(n)) continue;
                String text = safe(n.getText()).trim();
                if (!expectedMessage.equals(text)) continue;
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                if (b.isEmpty()) continue;
                if (oldComposerBounds.isEmpty()) return true;
                if (b.bottom <= oldComposerBounds.top + dp(16)) return true;
            }
            return false;
        } finally {
            recycleList(nodes);
        }
    }

    private boolean headerContainsGuard(AccessibilityNodeInfo root, String guard) {
        if (guard == null || guard.trim().isEmpty()) return true;
        String needle = guard.trim().toLowerCase(Locale.ROOT);
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int headerBottom = rootBounds.isEmpty()
                ? (int) (getResources().getDisplayMetrics().heightPixels * 0.35f)
                : rootBounds.top + (int) (rootBounds.height() * 0.35f);
        List<AccessibilityNodeInfo> nodes = flatten(root);
        try {
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser()) continue;
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                if (!b.isEmpty() && b.centerY() > headerBottom) continue;
                String text = combinedText(n).toLowerCase(Locale.ROOT);
                if (text.contains(needle)) return true;
            }
            return false;
        } finally {
            recycleList(nodes);
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
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        if (rootBounds.isEmpty()) {
            rootBounds.set(0, 0, getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        }
        int lowerLine = rootBounds.top + (int) (rootBounds.height() * 0.52f);
        try {
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser() || !n.isEnabled()) continue;
                String clazz = safe(n.getClassName()).toLowerCase(Locale.ROOT);
                String id = safe(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
                String hint = safe(n.getHintText()).toLowerCase(Locale.ROOT);
                String desc = safe(n.getContentDescription()).toLowerCase(Locale.ROOT);
                String text = safe(n.getText());
                Rect b = new Rect();
                n.getBoundsInScreen(b);

                boolean textCapable = n.isEditable() || supportsSetText(n);
                boolean lowerScreen = !b.isEmpty() && b.centerY() >= lowerLine;
                boolean wideEnough = !b.isEmpty() && b.width() >= rootBounds.width() * 0.30f;
                boolean semantic = id.contains("prompt") || id.contains("composer") || id.contains("input") || id.contains("message") ||
                        hint.contains("message") || hint.contains("메시지") || hint.contains("질문") || hint.contains("chatgpt") || hint.contains("ask") ||
                        desc.contains("message") || desc.contains("메시지") || desc.contains("prompt") ||
                        clazz.contains("edittext") || clazz.contains("textfield");

                if (!textCapable || !lowerScreen || !(semantic || wideEnough)) continue;

                int score = 0;
                if (n.isEditable()) score += 180;
                if (supportsSetText(n)) score += 150;
                if (clazz.contains("edittext") || clazz.contains("textfield")) score += 110;
                if (n.isFocused()) score += 45;
                if (n.isFocusable()) score += 20;
                if (semantic) score += 80;
                if (wideEnough) score += 45;
                if (text.length() > 1200) score -= 150;

                if (score > bestScore && score >= 230) {
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
        if (rememberedBounds.isEmpty()) return null;
        List<AccessibilityNodeInfo> nodes = flatten(root);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        int maxDy = Math.max(dp(96), rememberedBounds.height() * 2);
        try {
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser() || !n.isEnabled()) continue;
                String text = safe(n.getText()).trim();
                if (!expected.equals(text)) continue;
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                if (b.isEmpty()) continue;
                int dy = Math.abs(b.centerY() - rememberedBounds.centerY());
                if (dy > maxDy) continue;
                int score = 100;
                if (n.isEditable()) score += 120;
                if (supportsSetText(n)) score += 100;
                score += Math.max(0, 100 - dy / Math.max(1, dp(2)));
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

                boolean excluded = label.contains("voice") || label.contains("음성") || label.contains("microphone") ||
                        label.contains("마이크") || label.contains("camera") || label.contains("카메라") ||
                        label.contains("attach") || label.contains("첨부") || label.contains("photo") || label.contains("사진");
                if (excluded) continue;

                boolean semanticSend = label.contains("send prompt") || label.contains("submit prompt") ||
                        label.equals("send") || label.equals("보내기") || label.equals("전송") ||
                        label.contains("보내기") || label.contains("전송") || label.contains("submit") ||
                        id.contains("send") || id.contains("submit");
                if (!semanticSend) continue;

                int score = 250;
                if (id.contains("send") || id.contains("submit")) score += 120;
                if (n.isClickable()) score += 70;
                else if (hasClickableParent(n, 3)) score += 45;

                if (!composerBounds.isEmpty() && !b.isEmpty()) {
                    int maxDy = Math.max(dp(72), composerBounds.height() * 2);
                    int dy = Math.abs(b.centerY() - composerBounds.centerY());
                    if (dy <= maxDy && b.centerX() >= composerBounds.centerX()) score += 120;
                    else score -= 80;
                    if (!rootBounds.isEmpty() && b.centerX() >= rootBounds.left + rootBounds.width() * 0.72f) score += 60;
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
                    boolean interesting = n.isEditable() || n.isClickable() || supportsSetText(n);
                    if (!interesting) continue;
                    if (added++ > 0) sb.append(" | ");
                    sb.append(simpleClass(n))
                            .append(" e=").append(n.isEditable() ? 1 : 0)
                            .append(" c=").append(n.isClickable() ? 1 : 0)
                            .append(" s=").append(supportsSetText(n) ? 1 : 0)
                            .append(" role=").append(nodeRole(n))
                            .append(" @").append(b.left).append(',').append(b.top).append('-').append(b.right).append(',').append(b.bottom);
                    if (added >= 5) break;
                }
            } finally {
                recycleList(nodes);
            }
            AutomationPrefs.appendLog(this, sb.toString());
        } catch (Throwable ignored) {}
    }

    private String nodeRole(AccessibilityNodeInfo n) {
        String text = combinedText(n).toLowerCase(Locale.ROOT);
        String id = safe(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
        if (text.contains("send") || text.contains("보내기") || text.contains("전송") || id.contains("send") || id.contains("submit")) return "send";
        if (text.contains("voice") || text.contains("microphone") || text.contains("음성") || text.contains("마이크")) return "voice";
        if (text.contains("attach") || text.contains("첨부") || text.contains("photo") || text.contains("사진")) return "attach";
        if (n.isEditable() || supportsSetText(n)) return "editor";
        return "other";
    }

    private String simpleClass(AccessibilityNodeInfo n) {
        String clazz = safe(n.getClassName());
        int dot = clazz.lastIndexOf('.');
        return dot >= 0 ? clazz.substring(dot + 1) : clazz;
    }

    private void fail(String reason) {
        AutomationPrefs.retrySoon(this, reason);
        actionInProgress = false;
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
