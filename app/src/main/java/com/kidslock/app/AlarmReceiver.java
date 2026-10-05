package com.kidslock.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 计时到期闹钟。服务可能被系统杀死（实测 MIUI TV 熄屏待机会杀进程、
 * 且拒绝前台服务、拦开机广播），进程死后唯一还能在"到点时刻"唤醒我们的
 * 就是 AlarmManager——闹钟由系统调度，进程死亡不影响触发。
 *
 * 触发后的职责仅仅是"确保服务在运行"：服务 startTicking 时
 * deductOfflineIfAny 会扣除死亡期间屏幕亮着的时间，剩余归零则立即锁屏；
 * 未归零（熄屏期间死亡）则正常续算并重新调度下一个到期闹钟。
 */
public class AlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_TIMER_CHECK = "com.kidslock.app.ACTION_TIMER_CHECK";
    private static final String TAG = "AlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_TIMER_CHECK.equals(intent.getAction())) return;
        PrefManager pref = new PrefManager(context);
        if (!pref.isTimerActive()) {
            Log.i(TAG, "Timer not active, ignore expiry alarm");
            return;
        }
        Log.i(TAG, "Expiry alarm fired, restarting guard service");
        try {
            Intent service = new Intent(context, LockService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception e) {
            // 新系统（12+）从后台起前台服务可能被拒；此时只能等下次打开应用自愈
            Log.e(TAG, "start service from alarm failed", e);
        }
    }
}
