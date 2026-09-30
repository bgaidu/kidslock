package com.kidslock.app;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;

/**
 * SharedPreferences 封装：管理所有持久化设置和状态。
 * 状态在重启后仍然保留，保证锁屏不会被绕过。
 *
 * 计时设计（亮屏计时，防改系统时间 + 防杀服务冻结 + 防强停重启）：
 * 运行期间由 LockService 基于 SystemClock.elapsedRealtime() 递减"剩余毫秒数"，
 * 且仅在屏幕亮起时递减（熄屏即暂停）；每 5 秒连同 uptime/elapsed/墙钟时间戳持久化。
 * 服务被杀后重启时用 uptime 差值对账——uptime 在深度睡眠（熄屏）时不走，
 * 所以"服务死亡但屏幕亮着"的时间照样扣除，熄屏期间几乎不扣；
 * 跨重启时用使用情况统计补扣"强行停止后继续使用"的前台时长（需授权）。
 * uptime/elapsed 均不受改系统时间影响。
 */
public class PrefManager {

    private static final String PREF_NAME = "kids_lock_prefs";
    private final SharedPreferences prefs;
    private final Context appContext;

    // Keys
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_WATCH_LIMIT_MIN = "watch_limit_min";
    private static final String KEY_UNLOCK_COUNT = "unlock_count";
    private static final String KEY_IS_LOCKED = "is_locked";
    private static final String KEY_TIMER_ACTIVE = "timer_active";
    private static final String KEY_TIMER_END_TIME = "timer_end_time";        // 上游旧版墙钟，仅迁移用
    private static final String KEY_TIMER_REMAINING = "timer_remaining_ms";
    private static final String KEY_TIMER_STAMP_WALL = "timer_stamp_wall";    // 墙钟戳，跨重启对账基准
    private static final String KEY_TIMER_STAMP_UPTIME = "timer_stamp_uptime";
    private static final String KEY_TIMER_STAMP_ELAPSED = "timer_stamp_elapsed";
    private static final String KEY_PARENT_PIN = "parent_pin";
    private static final String KEY_PIN_SALT = "pin_salt";
    private static final String KEY_PIN_FAIL_COUNT = "pin_fail_count";
    private static final String KEY_PIN_LOCK_UNTIL = "pin_lock_until";

    // Defaults
    private static final boolean DEFAULT_AUTO_START = true;
    private static final int DEFAULT_WATCH_LIMIT_MIN = 30;
    private static final int DEFAULT_UNLOCK_COUNT = 3;
    private static final String DEFAULT_PARENT_PIN = "1234";
    private static final int MAX_PIN_FAILS = 5;
    private static final long PIN_LOCKOUT_MS = 30_000;

    public PrefManager(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        migrateLegacyTimer();
    }

    /** 上游旧版用墙钟"到期时间"计时，一次性迁移为"剩余毫秒 + 开机时钟戳"模型 */
    private void migrateLegacyTimer() {
        if (!prefs.contains(KEY_TIMER_END_TIME)) return;
        long end = prefs.getLong(KEY_TIMER_END_TIME, 0);
        long remaining = Math.max(0, end - System.currentTimeMillis());
        prefs.edit()
                .putLong(KEY_TIMER_REMAINING, remaining)
                .putLong(KEY_TIMER_STAMP_UPTIME, SystemClock.uptimeMillis())
                .putLong(KEY_TIMER_STAMP_ELAPSED, SystemClock.elapsedRealtime())
                .putLong(KEY_TIMER_STAMP_WALL, System.currentTimeMillis())
                .remove(KEY_TIMER_END_TIME)
                .apply();
    }

    // --- Auto Start ---
    public boolean isAutoStart() {
        return prefs.getBoolean(KEY_AUTO_START, DEFAULT_AUTO_START);
    }

    public void setAutoStart(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply();
    }

    // --- Watch Limit ---
    public int getWatchLimitMinutes() {
        return prefs.getInt(KEY_WATCH_LIMIT_MIN, DEFAULT_WATCH_LIMIT_MIN);
    }

    public void setWatchLimitMinutes(int minutes) {
        prefs.edit().putInt(KEY_WATCH_LIMIT_MIN, minutes).apply();
    }

    // --- Unlock Question Count ---
    public int getUnlockCount() {
        return prefs.getInt(KEY_UNLOCK_COUNT, DEFAULT_UNLOCK_COUNT);
    }

    public void setUnlockCount(int count) {
        prefs.edit().putInt(KEY_UNLOCK_COUNT, count).apply();
    }

    // --- Lock State ---
    public boolean isLocked() {
        return prefs.getBoolean(KEY_IS_LOCKED, false);
    }

    public void setLocked(boolean locked) {
        prefs.edit().putBoolean(KEY_IS_LOCKED, locked).apply();
    }

    // --- Timer ---
    public boolean isTimerActive() {
        return prefs.getBoolean(KEY_TIMER_ACTIVE, false);
    }

    public long getRemainingMillis() {
        return prefs.getLong(KEY_TIMER_REMAINING, 0);
    }

    public void startTimer(int minutes) {
        prefs.edit()
                .putBoolean(KEY_TIMER_ACTIVE, true)
                .putLong(KEY_TIMER_REMAINING, minutes * 60_000L)
                .putLong(KEY_TIMER_STAMP_UPTIME, SystemClock.uptimeMillis())
                .putLong(KEY_TIMER_STAMP_ELAPSED, SystemClock.elapsedRealtime())
                .putLong(KEY_TIMER_STAMP_WALL, System.currentTimeMillis())
                .putBoolean(KEY_IS_LOCKED, false)
                .apply();
    }

    /**
     * 停止计时，清零剩余时间与三枚时钟戳。
     * 时间戳清零后 {@link #deductOfflineIfAny()} 不会再执行扣减，
     * 这正是期望行为：下一次计时由 {@link #startTimer(int)} 重新设置全部时间戳。
     */
    public void stopTimer() {
        prefs.edit()
                .putBoolean(KEY_TIMER_ACTIVE, false)
                .putLong(KEY_TIMER_REMAINING, 0)
                .putLong(KEY_TIMER_STAMP_UPTIME, 0)
                .putLong(KEY_TIMER_STAMP_ELAPSED, 0)
                .putLong(KEY_TIMER_STAMP_WALL, 0)
                .remove(KEY_TIMER_END_TIME)
                .apply();
    }

    public boolean isTimerExpired() {
        return isTimerActive() && getRemainingMillis() <= 0;
    }

    /** 运行期间定期持久化：剩余毫秒与三枚时间戳必须同批写入，保证对账不自相矛盾 */
    public void persistTimer(long remainingMillis) {
        prefs.edit()
                .putLong(KEY_TIMER_REMAINING, remainingMillis)
                .putLong(KEY_TIMER_STAMP_UPTIME, SystemClock.uptimeMillis())
                .putLong(KEY_TIMER_STAMP_ELAPSED, SystemClock.elapsedRealtime())
                .putLong(KEY_TIMER_STAMP_WALL, System.currentTimeMillis())
                .apply();
    }

    /**
     * 服务重启时对账（亮屏计量的离线部分）：
     * - elapsedRealtime 变小 → 经历过重启。正常关机期间屏幕必灭，无需扣；
     *   但若期间发生过"强行停止后继续使用"，用系统使用情况统计补扣真实前台时间
     *   （需家长授予"有权查看使用情况"权限；未授予则拿不到数据，按不补扣处理）。
     * - 同一轮开机内 → 扣 uptime 差值。uptime 深度睡眠（熄屏挂起）时不走，
     *   所以"服务被杀但屏幕亮着"的时间照样扣除，熄屏期间服务死亡几乎不扣。
     * 时间戳与剩余值上次是一起写入的，从剩余值里直接减流逝量即为对账。
     */
    public void deductOfflineIfAny() {
        if (!isTimerActive()) return;
        long lastElapsed = prefs.getLong(KEY_TIMER_STAMP_ELAPSED, 0);
        if (lastElapsed <= 0 || SystemClock.elapsedRealtime() < lastElapsed) {
            // 重启过：按使用情况统计补扣强制停止期间的前台使用，再刷新全部时间戳
            long wallStamp = prefs.getLong(KEY_TIMER_STAMP_WALL, 0);
            long remaining = getRemainingMillis();
            long unbilled = (wallStamp > 0 && remaining > 0)
                    ? queryForegroundMillisSince(wallStamp) : 0;
            if (unbilled > 0) remaining = Math.max(0, remaining - unbilled);
            persistTimer(remaining);
            return;
        }
        long awake = SystemClock.uptimeMillis() - prefs.getLong(KEY_TIMER_STAMP_UPTIME, 0);
        if (awake <= 0) return;
        long remaining = getRemainingMillis() - awake;
        persistTimer(Math.max(0, remaining));
    }

    /**
     * 查询自 wallStamp 以来所有应用的前台使用总时长（毫秒）。
     * 需要用户在系统设置里授予"有权查看使用情况"（PACKAGE_USAGE_STATS）；
     * 未授予或系统不支持时返回 0，按不补扣处理。
     */
    private long queryForegroundMillisSince(long wallStamp) {
        long now = System.currentTimeMillis();
        long total = 0;
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) appContext.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return 0;
            UsageEvents events = usm.queryEvents(wallStamp, now);
            if (events == null) return 0;
            HashMap<String, Long> resumedAt = new HashMap<String, Long>();
            UsageEvents.Event ev = new UsageEvents.Event();
            while (events.hasNextEvent() && events.getNextEvent(ev)) {
                String pkg = ev.getPackageName();
                if (pkg == null) continue;
                if (ev.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    resumedAt.put(pkg, ev.getTimeStamp());
                } else if (ev.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    Long t0 = resumedAt.remove(pkg);
                    if (t0 != null) total += Math.max(0, ev.getTimeStamp() - Math.max(wallStamp, t0));
                }
            }
            // 查询结束时仍在前台的应用，计到 now
            for (Long t0 : resumedAt.values()) {
                total += Math.max(0, now - Math.max(wallStamp, t0));
            }
        } catch (Exception e) {
            return 0;
        }
        return total;
    }

    // --- Parent PIN（随机盐 + SHA-256，不存明文） ---

    /**
     * 校验 PIN，兼容三种存储格式：
     * 空值 → 默认 1234；4 位数字 → 旧版明文（校验成功时升级）；
     * 64 位十六进制 → 已迁移的哈希；其他 → 视为损坏（例如异常时误写入的空串）。
     */
    public boolean verifyPin(String pin) {
        if (pin == null || pin.isEmpty()) return false;
        String stored = prefs.getString(KEY_PARENT_PIN, null);
        if (stored == null) return pin.equals(DEFAULT_PARENT_PIN);
        if (stored.length() == 4 && stored.matches("\\d{4}")) {
            // 旧版明文：校验成功时升级为哈希，避免下次再走明文分支
            if (stored.equals(pin)) {
                // 校验成功即升级存储格式。升级失败不视为验证失败，
                // 仅记录失败计数（下次仍可用明文校验），避免 SHA 异常被误计为密码错误。
                try {
                    setParentPin(pin);
                    resetPinFailures();
                } catch (RuntimeException e) {
                    recordPinFailure();
                }
                return true;
            }
            return false;
        }
        if (stored.length() == 64) {
            return stored.equals(sha256Hex(getOrCreateSalt() + "|" + pin));
        }
        // 存储已损坏：拒绝匹配任何输入。
        // 家长可在 MainActivity 通过"重新设置PIN"覆盖此值来恢复，避免被永久锁死。
        return false;
    }

    /**
     * 写入 PIN 的哈希。SHA-256 失败时抛出而非返回空串，
     * 避免把错误值写成"看起来已设置"的状态导致 PIN 永久失效。
     */
    public boolean setParentPin(String pin) {
        if (pin == null || pin.isEmpty()) return false;
        String hash = sha256Hex(getOrCreateSalt() + "|" + pin);
        if (hash.length() != 64) {
            throw new IllegalStateException("sha256Hex 返回了非 64 位摘要");
        }
        prefs.edit()
                .putString(KEY_PARENT_PIN, hash)
                .apply();
        return true;
    }

    /**
     * 是否已有可用的 PIN（已设置且存储格式未损坏）。
     * 损坏时 MainActivity 允许家长重新设置来恢复，避免被空串锁死。
     */
    public boolean isPinUsable() {
        String stored = prefs.getString(KEY_PARENT_PIN, null);
        if (stored == null) return true;                          // 未设置，默认 1234 可用
        if (stored.length() == 4 && stored.matches("\\d{4}")) return true;  // 旧版明文可用
        return stored.length() == 64;                             // 仅哈希可用
    }

    private String getOrCreateSalt() {
        String salt = prefs.getString(KEY_PIN_SALT, null);
        if (salt == null) {
            salt = new BigInteger(64, new SecureRandom()).toString(16);
            prefs.edit().putString(KEY_PIN_SALT, salt).apply();
        }
        return salt;
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 Android 标准算法，不可用时抛出而非返回空串——
            // 空串会被当成"已设置的 PIN"写入，导致永久无法解锁。
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // --- PIN 防暴力尝试 ---
    public boolean isPinLocked() {
        return System.currentTimeMillis() < prefs.getLong(KEY_PIN_LOCK_UNTIL, 0);
    }

    public long getPinLockRemainingMillis() {
        long until = prefs.getLong(KEY_PIN_LOCK_UNTIL, 0);
        return Math.max(0, until - System.currentTimeMillis());
    }

    public void recordPinFailure() {
        int fails = prefs.getInt(KEY_PIN_FAIL_COUNT, 0) + 1;
        if (fails >= MAX_PIN_FAILS) {
            prefs.edit()
                    .putInt(KEY_PIN_FAIL_COUNT, 0)
                    .putLong(KEY_PIN_LOCK_UNTIL, System.currentTimeMillis() + PIN_LOCKOUT_MS)
                    .apply();
        } else {
            prefs.edit().putInt(KEY_PIN_FAIL_COUNT, fails).apply();
        }
    }

    public void resetPinFailures() {
        prefs.edit().putInt(KEY_PIN_FAIL_COUNT, 0).putLong(KEY_PIN_LOCK_UNTIL, 0).apply();
    }

    /**
     * 启用/禁用 HOME launcher alias。
     * 锁屏时启用，让按 HOME 键回到锁屏界面。
     * 解锁时禁用，让按 HOME 键回到正常电视桌面。
     */
    public void setHomeAliasEnabled(Context context, boolean enabled) {
        ComponentName alias = new ComponentName(context, "com.kidslock.app.LockScreenHome");
        PackageManager pm = context.getPackageManager();
        int state = enabled
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP);
    }
}
