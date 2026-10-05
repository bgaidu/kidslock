package com.kidslock.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * 设置主界面。
 * 家长可以在这里配置：开机自启、观看时长、解锁题数、家长PIN码。
 * 还可以开始/停止计时、立即锁屏、查看剩余时间。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_POST_NOTIFICATIONS = 1001;

    private PrefManager pref;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvStatus;
    private TextView tvRemaining;
    private Button btnAutoStart;
    private TextView tvWatchTime;
    private TextView tvUnlockCount;

    // 家长 PIN 门禁：设置页每次进入（含从后台返回）都要求重新验证，
    // 否则孩子从桌面打开应用就能改参数、停计时
    private View layoutSettings;
    private View layoutGate;
    private TextView tvGatePinDisplay;
    private final StringBuilder gatePinInput = new StringBuilder();
    private boolean gateVerified = false;

    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pref = new PrefManager(this);

        // 如果当前处于锁屏状态，禁止进入设置页（防止从桌面图标绕过锁屏解锁）
        if (pref.isLocked()) {
            Intent lockIntent = new Intent(this, LockScreenActivity.class);
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(lockIntent);
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        requestNotificationPermissionIfNeeded();

        initViews();
        setupListeners();
        updateStatus();
        applyGate();
    }

    /**
     * Android 13+ 通知需要运行时权限，不授予则前台服务的倒计时通知不可见。
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_POST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        pref = new PrefManager(this);
        // 如果处于锁屏状态，强制跳转到锁屏界面（防止从后台/最近任务绕过）
        if (pref.isLocked()) {
            Intent lockIntent = new Intent(this, LockScreenActivity.class);
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(lockIntent);
            finish();
            return;
        }
        // 计时进行中但服务不在运行（实测 MIUI 熄屏会杀后台进程）：拉起服务恢复计时。
        // 服务启动后 deductOfflineIfAny 会扣除服务死亡期间屏幕亮着的时间，
        // 剩余时间归零则立即触发锁屏。此处是前台 Activity，启动服务不受后台限制。
        if (pref.isTimerActive()) {
            startLockService();
        }
        // 先移除旧的，避免快速切回时重复 post 多个 Runnable
        handler.removeCallbacks(updateRunnable);
        handler.post(updateRunnable);
        // 从后台/其他界面返回时重新验证 PIN（onPause 已清除验证标记）
        applyGate();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(updateRunnable);
        // 离开界面即失效，下次进入需重新输入 PIN
        gateVerified = false;
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvRemaining = findViewById(R.id.tvRemaining);
        btnAutoStart = findViewById(R.id.btnAutoStart);
        tvWatchTime = findViewById(R.id.tvWatchTime);
        tvUnlockCount = findViewById(R.id.tvUnlockCount);

        // 家长 PIN 门禁（与锁屏界面共用 view_pin_pad 面板）
        layoutSettings = findViewById(R.id.settingsScroll);
        layoutGate = findViewById(R.id.layoutGate);
        tvGatePinDisplay = findViewById(R.id.tvPinDisplay);

        int[] gatePinButtonIds = {
                R.id.btnPin0, R.id.btnPin1, R.id.btnPin2, R.id.btnPin3,
                R.id.btnPin4, R.id.btnPin5, R.id.btnPin6, R.id.btnPin7,
                R.id.btnPin8, R.id.btnPin9
        };
        for (int i = 0; i <= 9; i++) {
            final int digit = i;
            findViewById(gatePinButtonIds[i]).setOnClickListener(v -> onGatePinDigit(digit));
        }
        findViewById(R.id.btnPinClear).setOnClickListener(v -> {
            gatePinInput.setLength(0);
            updateGatePinDisplay();
        });
        findViewById(R.id.btnPinBack).setOnClickListener(v -> {
            // 门禁界面没有可返回的上一屏，等同清除重输
            gatePinInput.setLength(0);
            updateGatePinDisplay();
        });
    }

    // ==================== 家长 PIN 门禁 ====================

    /** 按验证状态切换"门禁 / 设置页"的可见性 */
    private void applyGate() {
        gatePinInput.setLength(0);
        updateGatePinDisplay();
        if (gateVerified) {
            layoutGate.setVisibility(View.GONE);
            layoutSettings.setVisibility(View.VISIBLE);
        } else {
            layoutGate.setVisibility(View.VISIBLE);
            layoutSettings.setVisibility(View.GONE);
        }
    }

    private void onGatePinDigit(int digit) {
        if (gatePinInput.length() < 4) {
            gatePinInput.append(digit);
            updateGatePinDisplay();
            if (gatePinInput.length() == 4) {
                handler.postDelayed(this::checkGatePin, 200);
            }
        }
    }

    private void checkGatePin() {
        if (pref.isPinLocked()) {
            long remainingSec = pref.getPinLockRemainingMillis() / 1000;
            toast("尝试次数过多，请 " + remainingSec + " 秒后再试");
            gatePinInput.setLength(0);
            updateGatePinDisplay();
            return;
        }
        if (pref.verifyPin(gatePinInput.toString())) {
            pref.resetPinFailures();
            gateVerified = true;
            applyGate();
        } else {
            pref.recordPinFailure();
            toast("PIN码错误");
            gatePinInput.setLength(0);
            updateGatePinDisplay();
        }
    }

    private void updateGatePinDisplay() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i < gatePinInput.length()) {
                sb.append("●");
            } else {
                sb.append("○");
            }
            if (i < 3) sb.append("  ");
        }
        tvGatePinDisplay.setText(sb.toString());
    }

    private void setupListeners() {
        // 开机自启
        btnAutoStart.setOnClickListener(v -> {
            boolean newVal = !pref.isAutoStart();
            pref.setAutoStart(newVal);
            updateStatus();
            toast(newVal ? "已开启开机自启" : "已关闭开机自启");
        });

        // 观看时长预设
        int[] timeButtons = {R.id.btnTime15, R.id.btnTime30, R.id.btnTime45,
                R.id.btnTime60, R.id.btnTime90, R.id.btnTime120};
        int[] timeValues = {15, 30, 45, 60, 90, 120};
        for (int i = 0; i < timeButtons.length; i++) {
            final int minutes = timeValues[i];
            findViewById(timeButtons[i]).setOnClickListener(v -> {
                pref.setWatchLimitMinutes(minutes);
                updateStatus();
                toast("观看时长设为 " + minutes + " 分钟");
            });
        }

        // 解锁题数预设
        int[] countButtons = {R.id.btnUnlock3, R.id.btnUnlock5, R.id.btnUnlock10};
        int[] countValues = {3, 5, 10};
        for (int i = 0; i < countButtons.length; i++) {
            final int count = countValues[i];
            findViewById(countButtons[i]).setOnClickListener(v -> {
                pref.setUnlockCount(count);
                updateStatus();
                toast("解锁题数设为 " + count + " 题");
            });
        }

        // 设置PIN码
        findViewById(R.id.btnSetPin).setOnClickListener(v -> showPinDialog());

        // 开始计时
        findViewById(R.id.btnStartTimer).setOnClickListener(v -> {
            int minutes = pref.getWatchLimitMinutes();
            pref.startTimer(minutes);
            startLockService();
            toast("计时开始：" + minutes + " 分钟后锁屏");
            updateStatus();
        });

        // 停止计时
        findViewById(R.id.btnStopTimer).setOnClickListener(v -> {
            // 若仍处于锁定状态（正常 UI 流程进不到这里），顺带解除锁定，
            // 让"停止计时"始终是家长可用的逃生通道
            if (pref.isLocked()) {
                pref.setLocked(false);
                pref.setHomeAliasEnabled(this, false);
            }
            pref.stopTimer();
            stopService(new Intent(this, LockService.class));
            toast("计时已停止");
            updateStatus();
        });

        // 立即锁屏
        findViewById(R.id.btnLockNow).setOnClickListener(v -> {
            pref.setLocked(true);
            pref.setHomeAliasEnabled(this, true);
            pref.stopTimer();
            startLockService();
            Intent lockIntent = new Intent(this, LockScreenActivity.class);
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(lockIntent);
        });
    }

    private void showPinDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("设置家长PIN码（4位数字）");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
        builder.setView(input);

        builder.setPositiveButton("确定", (dialog, which) -> {
            String pin = input.getText().toString().trim();
            if (!pin.matches("\\d{4}")) {
                toast("PIN码必须是4位数字");
                return;
            }
            // setParentPin 在 SHA 异常时会抛 IllegalStateException——不吞掉，
            // 但也不能让弹窗回调崩溃整个设置页。
            try {
                if (pref.setParentPin(pin)) {
                    pref.resetPinFailures();
                    toast("PIN码已设置");
                } else {
                    toast("PIN码设置失败，请重试");
                }
            } catch (RuntimeException e) {
                toast("PIN码设置失败：" + e.getMessage());
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void startLockService() {
        Intent serviceIntent = new Intent(this, LockService.class);
        serviceIntent.putExtra("action", "start_timer");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void updateStatus() {
        // 开机自启
        btnAutoStart.setText(pref.isAutoStart() ? "开机自启：已开启" : "开机自启：已关闭");

        // 观看时长
        tvWatchTime.setText("观看时长：" + pref.getWatchLimitMinutes() + " 分钟");

        // 解锁题数
        tvUnlockCount.setText("解锁题数：" + pref.getUnlockCount() + " 道连续答对");

        // 状态
        if (pref.isLocked()) {
            tvStatus.setText("当前状态：已锁屏");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.wrong_red));
            tvRemaining.setText("请答题解锁");
        } else if (pref.isTimerActive()) {
            long remaining = pref.getRemainingMillis();
            if (remaining <= 0) {
                tvStatus.setText("当前状态：时间到");
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.wrong_red));
            } else {
                tvStatus.setText("当前状态：计时中");
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.correct_green));
                long totalSec = remaining / 1000;
                long min = totalSec / 60;
                long sec = totalSec % 60;
                tvRemaining.setText("剩余时间：" + String.format("%02d:%02d", min, sec));
            }
        } else {
            tvStatus.setText("当前状态：未计时");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            tvRemaining.setText("");
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
