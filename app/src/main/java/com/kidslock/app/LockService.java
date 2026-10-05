package com.kidslock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.core.app.NotificationCompat;

/**
 * 前台服务，三种工作：
 * 1. 计时模式：每秒检查观看时长，到时间触发锁屏。
 * 2. 锁屏守护模式：检测锁屏状态，必要时拉起 LockScreenActivity。
 * 3. 悬浮窗覆盖层：锁屏时覆盖桌面，拦截孩子操作，引导回到锁屏界面。
 *
 * 悬浮窗是解决 Android 10+ 后台启动 Activity 受限的核心方案：
 * 孩子在桌面看到的不是普通桌面，而是一个半透明覆盖层，
 * 点击/遥控确定后会打开锁屏 Activity（带用户交互，系统不拦截）。
 */
public class LockService extends Service {

    private static final String TAG = "LockService";
    private static final String CHANNEL_ID = "kids_lock_timer";
    private static final int NOTIFICATION_ID = 1001;
    private static final String ACTION_UNLOCK = "com.kidslock.app.ACTION_UNLOCK";

    // 计时状态（内存权威值，运行期间基于 elapsedRealtime，不受改系统时间影响）
    private long remainingMillis;
    private long lastTickElapsed;
    private int persistCountdown;
    private static final int PERSIST_EVERY_TICKS = 5;   // 每 5 秒持久化一次

    private PrefManager pref;
    private Handler handler;
    private Runnable tickRunnable;
    private Runnable watchdogRunnable;

    private WindowManager windowManager;
    private View overlayView;

    // 亮屏状态：只在亮屏期间递减剩余时间（熄屏即暂停）
    private PowerManager powerManager;
    private boolean screenOn = true;
    private BroadcastReceiver screenReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        pref = new PrefManager(this);
        handler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        screenOn = powerManager != null && powerManager.isInteractive();
        registerScreenListener();
        createNotificationChannel();
        Log.i(TAG, "LockService created");
    }

    /** 动态注册熄屏/亮屏广播，跟踪屏幕状态 */
    private void registerScreenListener() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    screenOn = false;
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    screenOn = true;
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        try {
            registerReceiver(screenReceiver, filter);
        } catch (Exception e) {
            Log.e(TAG, "registerScreenListener failed", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification(pref.getRemainingMillis()));

        // 解锁指令：原来写在已废弃的 onStart() 里（Android 2.0 起不再回调），永远不执行
        if (intent != null && ACTION_UNLOCK.equals(intent.getAction())) {
            unlockAndStop();
            return START_STICKY;  // 修复：返回 START_STICKY，让服务保持运行
        }

        // 已锁屏 → 进入锁屏守护模式
        if (pref.isLocked()) {
            Log.i(TAG, "Device locked, starting lock watchdog");
            startWatchdog();
            showOverlayIfPermitted();
            return START_STICKY;
        }

        // 计时器未激活 → 停止服务
        if (!pref.isTimerActive()) {
            Log.i(TAG, "Timer not active, stopping service");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // 如果已经超时，直接锁屏
        if (pref.isTimerExpired()) {
            Log.i(TAG, "Timer expired, locking now");
            triggerLock();
            return START_STICKY;
        }

        // 开始计时循环
        startTicking();
        return START_STICKY;
    }

    // ==================== 计时模式 ====================

    private void startTicking() {
        // 先移除旧的计时循环：onStartCommand 每次收到 start 指令都会走到这里
        // （如家长连点"开始计时"），不清理会叠加多个循环导致时间成倍速扣减，
        // 且旧 Runnable 的引用已被覆盖、永远无法 removeCallbacks
        if (tickRunnable != null) {
            handler.removeCallbacks(tickRunnable);
            tickRunnable = null;
        }
        // 服务重启/开机时对账：只扣"服务死了但屏幕亮着"的时间（详见 PrefManager.deductOfflineIfAny）
        pref.deductOfflineIfAny();
        remainingMillis = pref.getRemainingMillis();
        lastTickElapsed = SystemClock.elapsedRealtime();
        persistCountdown = 0;
        tickRunnable = new Runnable() {
            @Override
            public void run() {
                if (pref.isLocked()) {
                    // 已被锁屏接管，切换为守护模式
                    startWatchdog();
                    showOverlayIfPermitted();
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                long dt = now - lastTickElapsed;
                lastTickElapsed = now;
                // 亮屏计时：熄屏期间暂停递减
                if (dt > 0 && screenOn) remainingMillis -= dt;
                if (remainingMillis <= 0) {
                    triggerLock();
                    return;
                }
                if (--persistCountdown <= 0) {
                    pref.persistTimer(remainingMillis);
                    persistCountdown = PERSIST_EVERY_TICKS;
                }
                updateNotification();
                // 每秒检查
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(tickRunnable);
    }

    private void triggerLock() {
        Log.i(TAG, "Triggering lock screen!");
        pref.setLocked(true);
        pref.setHomeAliasEnabled(this, true);
        pref.stopTimer();

        // 优先显示悬浮窗（立即拦截操作）
        showOverlayIfPermitted();
        
        // 同时尝试拉起锁屏 Activity（某些设备允许）
        startLockScreenActivity();

        // 转为锁屏守护模式
        startWatchdog();
    }

    // ==================== 锁屏守护模式 ====================

    private void startWatchdog() {
        if (tickRunnable != null) {
            handler.removeCallbacks(tickRunnable);
            tickRunnable = null;
        }
        if (watchdogRunnable != null) {
            handler.removeCallbacks(watchdogRunnable);
        }
        watchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (!pref.isLocked()) {
                    // 已解锁，守护任务结束
                    Log.i(TAG, "Unlocked, watchdog stopping");
                    hideOverlay();
                    stopForeground(true);
                    stopSelf();
                    return;
                }

                // 如果锁屏 Activity 已经在前台，不需要操作
                if (isLockScreenOnTop()) {
                    handler.postDelayed(this, 800);
                    return;
                }

                // 如果锁屏 Activity 正在启动中，等待它完成
                long timeSinceStart = SystemClock.elapsedRealtime() - lastLockScreenStartTime;
                if (lastLockScreenStartTime > 0 && timeSinceStart < LOCK_SCREEN_START_DELAY_MS) {
                    Log.d(TAG, "Lock screen activity starting, waiting...");
                    handler.postDelayed(this, 800);
                    return;
                }

                // 尝试拉起锁屏 Activity
                Log.i(TAG, "Lock screen not on top, trying to pull");
                startLockScreenActivity();

                // 悬浮窗应该一直显示，防止被桌面覆盖
                showOverlayIfPermitted();

                handler.postDelayed(this, 800);
            }
        };
        handler.post(watchdogRunnable);
    }

    private boolean isLockScreenOnTop() {
        // getRunningTasks 在 Android 5.1+ 只返回自己的任务，无法判断真实前台；
        // 改用同进程静态引用跟踪（见 LockScreenActivity.isOnTop）
        if (LockScreenActivity.isOnTop()) return true;
        // 如果 Activity 正在启动但还没 resumed，也认为它在前台（避免 watchdog 重复启动）
        return LockScreenActivity.isStarting();
    }

    // ==================== 悬浮窗覆盖层 ====================

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    // 记录上次启动锁屏 Activity 的时间，避免 watchdog 立即显示悬浮窗遮挡
    private long lastLockScreenStartTime = 0;
    private static final long LOCK_SCREEN_START_DELAY_MS = 2000;

    /**
     * 显示全屏悬浮窗覆盖层。锁屏状态下只要权限已开就一直显示，
     * 拦截孩子的所有触摸/按键操作，引导回到锁屏界面。
     */
    private void showOverlayIfPermitted() {
        if (!canDrawOverlays()) {
            Log.d(TAG, "Overlay permission not granted, skip overlay");
            return;
        }
        // 如果锁屏 Activity 刚启动，延迟显示悬浮窗，避免遮挡
        long timeSinceStart = SystemClock.elapsedRealtime() - lastLockScreenStartTime;
        if (lastLockScreenStartTime > 0 && timeSinceStart < LOCK_SCREEN_START_DELAY_MS) {
            Log.d(TAG, "Lock screen activity just started, delay overlay");
            return;
        }
        if (overlayView != null) {
            // 已显示则不再重复添加
            return;
        }

        try {
            overlayView = new LockOverlayView(this);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    // 悬浮窗可聚焦，拦截 TV 遥控器焦点
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            windowManager.addView(overlayView, params);
            Log.i(TAG, "Overlay shown");
        } catch (Exception e) {
            Log.e(TAG, "Failed to show overlay", e);
        }
    }

    public void hideOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove overlay", e);
            }
            overlayView = null;
        }
    }

    /**
     * 从悬浮窗点击/按键等用户交互场景启动锁屏 Activity。
     * 先移除悬浮窗，确保锁屏 Activity 能正常显示在前台。
     */
    private void startLockScreenActivity() {
        // 记录启动时间，让 watchdog 延迟显示悬浮窗
        lastLockScreenStartTime = SystemClock.elapsedRealtime();
        
        // 先移除悬浮窗，避免遮挡锁屏 Activity
        hideOverlay();
        
        // 延迟启动 Activity，确保悬浮窗完全移除
        handler.postDelayed(() -> {
            Intent lockIntent = new Intent(this, LockScreenActivity.class);
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try {
                startActivity(lockIntent);
            } catch (Exception e) {
                Log.e(TAG, "Start LockScreenActivity failed", e);
            }
        }, 300);
    }

    /**
     * 解锁时由 LockScreenActivity 调用，彻底移除悬浮窗和停止服务。
     */
    public static void requestUnlock(Context context) {
        Intent intent = new Intent(context, LockService.class);
        intent.setAction(ACTION_UNLOCK);
        context.startService(intent);
    }

    private void unlockAndStop() {
        pref.setLocked(false);
        pref.setHomeAliasEnabled(this, false);
        pref.resetPinFailures();
        hideOverlay();
        // 必须先停掉守护循环：否则它下一轮检测到"已解锁"会 stopSelf，
        // 把下面刚重启的计时循环一并杀掉，解锁后计时就停摆了
        if (watchdogRunnable != null) {
            handler.removeCallbacks(watchdogRunnable);
            watchdogRunnable = null;
        }
        // 计时已在 LockScreenActivity.unlock() 里通过 startTimer 重置，
        // 这里只负责恢复计时模式（不停止服务）
        startTicking();
    }

    // ==================== 通知 ====================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "儿童锁屏计时",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("观看时间计时通知");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(long remaining) {
        String contentText;
        if (pref.isLocked()) {
            contentText = "设备已锁定，答对汉字即可解锁";
        } else if (!screenOn) {
            contentText = "熄屏暂停中 · 剩余观看时间：" + formatTime(remaining);
        } else {
            contentText = "剩余观看时间：" + formatTime(remaining);
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("儿童锁屏运行中")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(remainingMillis));
        }
    }

    private String formatTime(long millis) {
        long totalSeconds = millis / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    @Override
    public void onDestroy() {
        if (tickRunnable != null) {
            handler.removeCallbacks(tickRunnable);
        }
        if (watchdogRunnable != null) {
            handler.removeCallbacks(watchdogRunnable);
        }
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Exception e) {
                // 未注册成功时忽略
            }
            screenReceiver = null;
        }
        hideOverlay();
        Log.i(TAG, "LockService destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================== 悬浮窗内部视图 ====================

    /**
     * 全屏悬浮窗视图：大字提示 + 解锁按钮。
     * 点击按钮或按遥控器确定键都会打开 LockScreenActivity。
     */
    public class LockOverlayView extends FrameLayout {

        public LockOverlayView(Context context) {
            super(context);
            LayoutInflater.from(context).inflate(R.layout.overlay_lock, this, true);
            setFocusable(true);
            setFocusableInTouchMode(true);
            requestFocus();

            Button btnUnlock = findViewById(R.id.btnOverlayUnlock);
            if (btnUnlock != null) {
                btnUnlock.setFocusable(true);
                btnUnlock.setFocusableInTouchMode(true);
                btnUnlock.requestFocus();
                btnUnlock.setOnClickListener(v -> startLockScreenActivity());
            }

            // 整个覆盖层点击也打开锁屏（对触屏更友好）
            setOnClickListener(v -> startLockScreenActivity());
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                int keyCode = event.getKeyCode();
                if (keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_SPACE) {
                    startLockScreenActivity();
                    return true;
                }
            }
            return super.dispatchKeyEvent(event);
        }
    }
}
