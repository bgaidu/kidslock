package com.kidslock.app;

import android.app.AlarmManager;
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
        runningInstance = this;
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
        // MIUI TV 会拒绝 startForeground（日志 "not allow become forceground"），
        // 被拒时传入的通知不会上架，通知栏残留上一个进程的过期内容；
        // 用普通 notify 再发一次（发通知不受前台服务限制），保证状态可见
        updateNotification();

        // 解锁指令：原来写在已废弃的 onStart() 里（Android 2.0 起不再回调），永远不执行
        if (intent != null && ACTION_UNLOCK.equals(intent.getAction())) {
            unlockAndStop();
            return START_STICKY;  // 修复：返回 START_STICKY，让服务保持运行
        }

        // 无障碍守护检测到外来窗口（小窗等）浮在锁屏上：强制显示悬浮窗盖住
        if (intent != null && ACTION_HOLD_OVERLAY.equals(intent.getAction())) {
            handleHoldOverlay();
            return START_STICKY;
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
        // 挂系统级到期闹钟：进程被杀后 AlarmManager 仍会在"剩余时间走完"的时刻
        // 唤醒 AlarmReceiver 恢复守护并触发锁屏，堵住"服务死了没人锁屏"的洞。
        // 闹钟基线用 ELAPSED_REALTIME_WAKEUP（随设备休眠暂停），与熄屏暂停计时的语义一致；
        // 若闹钟因熄屏提前触发，AlarmReceiver 拉起服务后这里会按新的剩余时间重新调度。
        scheduleExpiryAlarm(this, remainingMillis);
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
        // 通知立即切换为"设备已锁定"（stopTimer 后剩余为 0，不能留旧倒计时）
        updateNotification();

        // 优先显示悬浮窗（立即拦截操作）
        showOverlayIfPermitted();

        // 同时尝试拉起锁屏 Activity（某些设备允许）
        startLockScreenActivity();

        // 转为锁屏守护模式
        startWatchdog();
    }

    // ==================== 锁屏守护模式 ====================

    /** 锁屏悬浮窗的强制保持期：检测到外来窗口（如平板小窗）时，悬浮窗至少保持到该时刻 */
    private static volatile long overlayHoldUntil;
    /** 悬浮窗保持动作 */
    public static final String ACTION_HOLD_OVERLAY = "com.kidslock.app.ACTION_HOLD_OVERLAY";
    /** 运行中的服务实例，供无障碍守护同进程直调（避免后台 startService 受限） */
    private static volatile LockService runningInstance;

    /**
     * 强制显示悬浮窗并保持一段时间。用于无障碍服务检测到"外来自由窗口
     * （小窗）浮在锁屏之上"时：自由窗口层永远高于全屏应用层，锁屏界面
     * 压不住它，唯一的办法是用系统悬浮窗（TYPE_APPLICATION_OVERLAY）盖住。
     */
    static void holdOverlay(Context context) {
        overlayHoldUntil = SystemClock.elapsedRealtime() + 5000;
        LockService s = runningInstance;
        if (s != null) {
            // 锁屏状态下守护服务必然在运行，同进程直接调
            s.showOverlayIfPermitted();
            return;
        }
        Intent i = new Intent(context, LockService.class).setAction(ACTION_HOLD_OVERLAY);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Exception e) {
            Log.e(TAG, "holdOverlay start service failed", e);
        }
    }

    private void handleHoldOverlay() {
        showOverlayIfPermitted();
    }


    /** 请求码，与通知的 PendingIntent 区分开 */
    private static final int ALARM_REQUEST_CODE = 2001;

    /**
     * 调度"剩余时间走完"的系统闹钟（进程死亡后仍会触发）。
     * 优先精确闹钟；新系统没有 SCHEDULE_EXACT_ALARM 权限时退化为非精确
     * （可能晚几分钟，但绝不会不触发）。
     */
    static void scheduleExpiryAlarm(Context context, long remainingMillis) {
        if (remainingMillis <= 0) return;
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, AlarmReceiver.class)
                .setAction(AlarmReceiver.ACTION_TIMER_CHECK);
        PendingIntent pi = PendingIntent.getBroadcast(
                context, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long triggerAt = SystemClock.elapsedRealtime() + remainingMillis;
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            Log.i(TAG, "Expiry alarm scheduled in " + (remainingMillis / 1000) + "s");
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            Log.w(TAG, "Exact alarm denied, using inexact", e);
        }
    }

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

                if (LockScreenActivity.isOnTop()) {
                    // 锁屏界面确认在前台：正常收起悬浮窗以免遮挡答题界面；
                    // 但若刚检测到外来窗口（平板小窗浮在锁屏之上），保持盖住
                    if (SystemClock.elapsedRealtime() >= overlayHoldUntil) {
                        hideOverlay();
                    }
                    handler.postDelayed(this, 800);
                    return;
                }

                // 锁屏界面未确认在前台：无论它是在启动中，还是被系统拦截
                // （实测小米 TV 会拦截排队后台拉起，数秒后才放行），都必须先把
                // 悬浮窗顶上去拦截输入，桌面不能裸露。悬浮窗自身可点击/按键拉起锁屏。
                showOverlayIfPermitted();

                // 仅在没有待完成的启动尝试时才再次拉起，避免叠加重复的 startActivity
                if (!LockScreenActivity.isStarting()) {
                    Log.i(TAG, "Lock screen not on top, trying to pull");
                    startLockScreenActivity();
                }

                handler.postDelayed(this, 800);
            }
        };
        handler.post(watchdogRunnable);
    }

    // ==================== 悬浮窗覆盖层 ====================

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    /**
     * 显示全屏悬浮窗覆盖层。锁屏状态下只要锁屏界面未确认在前台就一直显示，
     * 拦截孩子的所有触摸/按键操作，引导回到锁屏界面。
     */
    private void showOverlayIfPermitted() {
        if (!canDrawOverlays()) {
            Log.d(TAG, "Overlay permission not granted, skip overlay");
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
     * 拉起锁屏 Activity。悬浮窗的移除由 watchdog 在确认锁屏界面真正到前台后
     * 处理；这里不先移除悬浮窗——在系统拦截后台 Activity 启动的设备上
     * （如小米 TV），启动可能被延迟数秒，期间必须靠悬浮窗拦截输入。
     */
    private void startLockScreenActivity() {
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
        if (runningInstance == this) runningInstance = null;
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
