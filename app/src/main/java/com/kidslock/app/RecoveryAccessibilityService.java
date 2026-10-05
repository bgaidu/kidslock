package com.kidslock.app;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import androidx.core.content.ContextCompat;

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
    /** 周期自检间隔：MIUI TV 会冻结后台应用的高精度闹钟（实测），Handler 自检不受影响 */
    private static final long PERIODIC_CHECK_MS = 30000;
    private long lastRecoveryAttempt;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable periodicRecovery = new Runnable() {
        @Override
        public void run() {
            recoverIfNeeded();
            handler.postDelayed(this, PERIODIC_CHECK_MS);
        }
    };

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

    /**
     * 自行启用守护服务。前提是应用持有 WRITE_SECURE_SETTINGS——正常安装拿不到，
     * 需 adb 授权一次（pm grant，授权后持久有效），此后应用可永远自助：
     * 被用户/系统关闭后自动写回，开机后无需任何人进设置页。
     * 注意保留列表里其他应用的无障碍服务（如电视的语音控制）。
     */
    public static boolean trySelfEnable(Context context) {
        if (isServiceEnabled(context)) return true;
        if (ContextCompat.checkSelfPermission(context, "android.permission.WRITE_SECURE_SETTINGS")
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        try {
            ComponentName cn = new ComponentName(context, RecoveryAccessibilityService.class);
            String flat = cn.flattenToShortString();
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled) || "null".equals(enabled.trim())) {
                enabled = flat;
            } else if (!containsService(enabled, cn)) {
                enabled = enabled.trim() + ":" + flat;
            }
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled);
            Settings.Secure.putInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "Self-enabled accessibility service (WRITE_SECURE_SETTINGS held)");
            return isServiceEnabled(context);
        } catch (Exception e) {
            Log.e(TAG, "Self-enable failed", e);
            return false;
        }
    }

    private static boolean containsService(String enabled, ComponentName cn) {
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
        // 周期自检：窗口事件只在"变化"时才有，静默状态（如服务被停、倒计时过期）
        // 不会产生事件——30 秒一轮保证最终被发现。进程被杀后系统重新绑定，
        // onServiceConnected 会立即再跑一轮，等效于开机自启。
        handler.removeCallbacks(periodicRecovery);
        handler.postDelayed(periodicRecovery, PERIODIC_CHECK_MS);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacks(periodicRecovery);
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        suppressForeignWindow(event);
        recoverIfNeeded();
    }

    /**
     * 锁定状态下出现外来窗口（平板自由小窗/通知栏/其他应用）时：
     * 全局 HOME 收起小窗（MagicOS 小窗会被最小化），并让锁屏悬浮窗
     * 强制顶上盖住——自由窗口层永远高于全屏应用层，锁屏界面自身压不住，
     * 只能靠 TYPE_APPLICATION_OVERLAY 的系统悬浮窗。 kidslock 自己的
     * 窗口事件忽略，避免循环。
     */
    private static long lastSuppress;
    private static final long SUPPRESS_DEBOUNCE_MS = 800;

    private void suppressForeignWindow(AccessibilityEvent event) {
        try {
            PrefManager pref = new PrefManager(this);
            if (!pref.isLocked()) return;
            CharSequence pkg = event.getPackageName();
            if (pkg == null || "com.kidslock.app".contentEquals(pkg)) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastSuppress < SUPPRESS_DEBOUNCE_MS) return;
            lastSuppress = now;
            Log.i(TAG, "Foreign window while locked: " + pkg + ", collapsing & covering");
            performGlobalAction(GLOBAL_ACTION_HOME);
            LockService.holdOverlay(this);
        } catch (Exception e) {
            Log.e(TAG, "suppressForeignWindow failed", e);
        }
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
                // 锁屏守护服务也一并确保在运行（watchdog/悬浮窗依赖它）
                startServiceSafely();
            } else if (pref.isTimerActive()) {
                // 服务死亡期间屏幕亮着的时间照算（对账逻辑在 PrefManager）
                pref.deductOfflineIfAny();
                if (pref.getRemainingMillis() <= 0) {
                    // 时间已耗尽而锁屏未触发（服务被系统停掉、闹钟被推迟）：
                    // 直接拉起锁屏界面——其 onCreate 会以前台身份启动服务，
                    // 服务 onStartCommand 对账后触发完整锁定流程
                    Log.i(TAG, "Timer expired while service down, pulling lock screen");
                    startActivity(new Intent(this, LockScreenActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                } else {
                    startServiceSafely();
                }
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
