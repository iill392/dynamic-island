package com.lingdong.island;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * Optional "enhanced mode": the island window is hosted by an accessibility service and uses
 * {@link WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY}. Accessibility windows are trusted
 * by several ROMs (MIUI / OriginOS / ColorOS …) where regular application-overlay windows are
 * pushed below the status bar, which is exactly how the reference dynamicSpot app does it.
 */
public class IslandAccessibilityService extends AccessibilityService {

    private static final String TAG = "IslandA11y";
    private static volatile IslandAccessibilityService instance;

    public static IslandAccessibilityService get() { return instance; }
    public static boolean isConnected() { return instance != null; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "accessibility service connected");
        IslandService.onAccessibilityChanged();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // The island does not inspect screen content; the service only provides trusted overlay windowing.
        // v8.14 adds an on-demand forced-reply helper (see forceReply()).
    }

    /**
     * v8.14: best-effort forced reply for messaging apps that expose no RemoteInput action.
     * Finds the target app's window, its editable input node, writes the text, then clicks a
     * send/发送 button if one is discoverable. Runs on the accessibility service main thread.
     */
    public boolean forceReply(String targetPkg, String text) {
        if (targetPkg == null || targetPkg.isEmpty() || text == null) return false;
        AccessibilityNodeInfo appRoot = null;
        AccessibilityNodeInfo edit = null;
        // Prefer a window of the target app that actually contains an editable field (chat input),
        // instead of blindly taking the first window of that package.
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;
                    CharSequence pkg = root.getPackageName();
                    if (pkg != null && targetPkg.contentEquals(pkg)) {
                        AccessibilityNodeInfo foundEdit = findEditable(root);
                        if (foundEdit != null) {
                            appRoot = root;
                            edit = foundEdit;
                            break;
                        }
                    }
                    root.recycle();
                }
            }
        } catch (Throwable ignored) {}
        if (appRoot == null) {
            appRoot = findAppRoot(targetPkg);
            if (appRoot != null) edit = findEditable(appRoot);
        }
        if (appRoot == null || edit == null) {
            if (appRoot != null) appRoot.recycle();
            return false;
        }
        boolean focused = edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        if (!focused) edit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean set = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!set) {
            // Some custom EditText implementations reject ACTION_SET_TEXT; try the clipboard paste
            // path instead.
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("reply", text));
                set = edit.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            } catch (Throwable ignored) {}
        }
        if (set) {
            // The send button is often disabled until the EditText's text watcher has run, and the
            // node tree updates asynchronously. Wait briefly, then re-fetch the app window before
            // looking for the send control (QQ uses id/send_btn; WeChat exposes a "发送" button).
            android.os.SystemClock.sleep(140L);
            AccessibilityNodeInfo freshRoot = findAppRoot(targetPkg);
            AccessibilityNodeInfo send = findSendButton(
                    freshRoot != null ? freshRoot : appRoot, targetPkg);
            if (send != null) {
                if (!send.isEnabled()) android.os.SystemClock.sleep(100L);
                send.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                send.recycle();
            }
            if (freshRoot != null && freshRoot != appRoot) freshRoot.recycle();
            edit.recycle();
            appRoot.recycle();
            return true;
        }
        edit.recycle();
        appRoot.recycle();
        return false;
    }

    private AccessibilityNodeInfo findAppRoot(String targetPkg) {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;
                    CharSequence pkg = root.getPackageName();
                    if (pkg != null && targetPkg.contentEquals(pkg)) return root;
                    root.recycle();
                }
            }
        } catch (Throwable ignored) {}
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                CharSequence pkg = root.getPackageName();
                if (pkg != null && targetPkg.contentEquals(pkg)) return root;
                root.recycle();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isEditable() && root.isEnabled() && root.isVisibleToUser()) {
            return AccessibilityNodeInfo.obtain(root);
        }
        int n = root.getChildCount();
        for (int i = n - 1; i >= 0; i--) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findEditable(child);
            child.recycle();
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo root, String pkg) {
        if (root == null) return null;
        try {
            java.util.List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(
                    pkg + ":id/send_btn");
            if (byId != null) {
                for (AccessibilityNodeInfo n : byId) {
                    if (n != null && n.isClickable()) return AccessibilityNodeInfo.obtain(n);
                }
            }
        } catch (Throwable ignored) {}
        CharSequence text = root.getText();
        CharSequence desc = root.getContentDescription();
        String a = text == null ? "" : text.toString().toLowerCase();
        String b = desc == null ? "" : desc.toString().toLowerCase();
        boolean match = a.equals("发送") || a.equals("send") || a.contains("发送消息")
                || b.equals("发送") || b.equals("send") || b.contains("发送");
        if (match && root.isClickable()) {
            return AccessibilityNodeInfo.obtain(root);
        }
        int n = root.getChildCount();
        for (int i = n - 1; i >= 0; i--) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findSendButton(child, pkg);
            child.recycle();
            if (found != null) return found;
        }
        return null;
    }

    @Override public void onInterrupt() {
    }

    @Override public boolean onUnbind(Intent intent) {
        instance = null;
        Log.i(TAG, "accessibility service unbound");
        IslandService.onAccessibilityChanged();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        // Some ROMs destroy the service without calling onUnbind(); never leave a dead singleton
        // behind or the service would keep trying to add windows through it.
        instance = null;
        IslandService.onAccessibilityChanged();
        super.onDestroy();
    }

    // ---- window hosting helpers used by IslandService (called on the main thread) -------------
    private WindowManager wm() {
        return (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    public void addView(View view, WindowManager.LayoutParams lp) {
        wm().addView(view, lp);
    }

    public void updateView(View view, WindowManager.LayoutParams lp) {
        wm().updateViewLayout(view, lp);
    }

    public void removeView(View view) {
        wm().removeViewImmediate(view);
    }
}
