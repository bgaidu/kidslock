package com.kidslock.app;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 守护用无障碍服务：解决"开机自启广播被拦、无自启动白名单"设备上的复活通道。
 *
 * 系统会在每次开机后自动绑定所有已启用的无障碍服务（不受自启动拦截），
 * 进程被杀时也会自动重新绑定——这是免 ROOT 环境下唯一可靠的系统级复活机制
 * （"电视启动助手"类工具同原理）。
 *
 * 职责：
 * 1. 绑定时（含开机）检查持久化状态并恢复：锁屏状态拉起锁屏界面、计时状态拉起服务；
 * 2. 监听窗口切换：锁定状态下桌面/其他应用切到前台时把锁屏拉回（额外防绕过）。
 * 不读取任何窗口内容（canRetrieveWindowContent=false），只感知"窗口切换"这一事件。
 */
public class RecoveryAccessibilityService extends AccessibilityService {

    private static final String TAG = "RecoveryA11y";
    /** 恢复尝试防抖间隔：窗口切换事件很密集，避免高频 startActivity */
    private static final long RECOVERY_DEBOUNCE_MS = 3000;
    private long lastRecoveryAttempt;

    /**
     * 判断守护服务是否已在系统无障碍设置中启用（需用户手动开启一次，
     * Android 不允许应用自我启用无障碍服务——这是系统安全红线）。
     */
    public static boolean isServiceEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName cn = new ComponentName(context, RecoveryAccessibilityService.class);
        for (String item : enabled.split(":")) {
            String s = item.trim();
            if (s.equalsIgnoreCase(cn.flattenToString())
                    || s.equalsIgnoreCase(cn.flattenToShortString())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Service connected (boot or rebinding), running recovery check");
        recoverIfNeeded();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        recoverIfNeeded();
    }

    private void recoverIfNeeded() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastRecoveryAttempt < RECOVERY_DEBOUNCE_MS) return;
        lastRecoveryAttempt = now;
        try {
            PrefManager pref = new PrefManager(this);
            if (pref.isLocked()) {
                if (!LockScreenActivity.isOnTop() && !LockScreenActivity.isStarting()) {
                    Log.i(TAG, "Locked but lock screen not on top, pulling it up");
                    startActivity(new Intent(this, LockScreenActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                }
                // 锁屏守护服务也一并确保在运行（-watchdog/悬浮窗依赖它）
                startServiceSafely();
            } else if (pref.isTimerActive()) {
                // 计时中但服务可能已死（无障碍绑定通常发生在进程刚复活时）
                startServiceSafely();
            }
        } catch (Exception e) {
            Log.e(TAG, "Recovery failed", e);
        }
    }

    private void startServiceSafely() {
        try {
            startService(new Intent(this, LockService.class));
        } catch (Exception e) {
            Log.e(TAG, "start service failed", e);
        }
    }

    @Override
    public void onInterrupt() {
        // 无操作：本服务不使用无障碍反馈
    }
}
