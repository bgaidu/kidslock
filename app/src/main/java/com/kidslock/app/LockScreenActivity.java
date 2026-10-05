package com.kidslock.app;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 锁屏界面：展示五年级水平汉字，需要连续答对指定题数才能解锁。
 * - 按 HOME 键无法绕过（通过 HOME launcher alias 拦截）
 * - 按 BACK 键无效
 * - 答错重置计数
 * - 家长可通过输入 PIN 码强制解锁
 */
public class LockScreenActivity extends AppCompatActivity {

    private PrefManager pref;
    private CharacterBank bank;

    // 题目状态
    private CharacterBank.CharEntry currentChar;
    private List<String> currentOptions;
    private String correctAnswer;
    private int correctCount = 0;
    private int requiredCount;

    // Views - 认字模式
    private View layoutMain;
    private TextView tvCharacter;
    private TextView tvProgress;
    private TextView tvFeedback;
    private Button[] optionButtons;

    // Views - PIN模式
    private View layoutPin;
    private TextView tvPinDisplay;
    private StringBuilder pinInput = new StringBuilder();

    private final Handler handler = new Handler(Looper.getMainLooper());

    // 供 LockService 判断锁屏界面是否真实处于前台（同进程静态引用，
    // 替代 Android 5.1+ 已拿不到其他应用任务的 getRunningTasks）
    private static LockScreenActivity foregroundInstance;
    private boolean resumed = false;
    // 实例创建时刻，用于给"启动中"状态设定时间上限
    private final long startedAtElapsed = SystemClock.elapsedRealtime();
    // 启动窗口期上限：正常冷启动远小于此值；必须大于 LockService 的
    // LOCK_SCREEN_START_DELAY_MS(2s) + watchdog 间隔(800ms)，否则仍会出现重复拉起
    private static final long STARTING_TIMEOUT_MS = 5000;

    static boolean isOnTop() {
        LockScreenActivity a = foregroundInstance;
        return a != null && !a.isFinishing() && a.resumed;
    }

    /**
     * 供 LockService 判断锁屏 Activity 是否正在启动过程中。
     * 必须有时间上限：实例存在但迟迟未 resumed（被系统弹窗、通知栏遮挡）时
     * 不能永远视为"启动中"，否则 watchdog 会一直空等，彻底放弃防绕过。
     */
    static boolean isStarting() {
        LockScreenActivity a = foregroundInstance;
        return a != null && !a.isFinishing() && !a.resumed
                && SystemClock.elapsedRealtime() - a.startedAtElapsed < STARTING_TIMEOUT_MS;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 尽早登记静态引用：真正的启动窗口期（onCreate→onResume）里
        // foregroundInstance 必须已指向本实例，isStarting() 才能生效
        foregroundInstance = this;
        setContentView(R.layout.activity_lock_screen);

        pref = new PrefManager(this);
        bank = new CharacterBank();
        requiredCount = pref.getUnlockCount();

        // 窗口属性：全屏，保持显示
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        );
        // 沉浸式
        hideSystemUI();

        initViews();
        loadNextQuestion();
    }

    private void initViews() {
        // 认字主界面
        layoutMain = findViewById(R.id.layoutMain);
        tvCharacter = findViewById(R.id.tvCharacter);
        tvProgress = findViewById(R.id.tvProgress);
        tvFeedback = findViewById(R.id.tvFeedback);

        optionButtons = new Button[]{
                findViewById(R.id.btnOption1),
                findViewById(R.id.btnOption2),
                findViewById(R.id.btnOption3),
                findViewById(R.id.btnOption4)
        };
        for (int i = 0; i < optionButtons.length; i++) {
            final int idx = i;
            optionButtons[i].setOnClickListener(v -> onOptionSelected(idx));
        }

        // 家长解锁按钮
        findViewById(R.id.btnParentUnlock).setOnClickListener(v -> showPinPad());

        // PIN界面
        layoutPin = findViewById(R.id.layoutPin);
        tvPinDisplay = findViewById(R.id.tvPinDisplay);

        int[] pinButtonIds = {
                R.id.btnPin0, R.id.btnPin1, R.id.btnPin2, R.id.btnPin3,
                R.id.btnPin4, R.id.btnPin5, R.id.btnPin6, R.id.btnPin7,
                R.id.btnPin8, R.id.btnPin9
        };
        for (int i = 0; i <= 9; i++) {
            final int digit = i;
            findViewById(pinButtonIds[i]).setOnClickListener(v -> onPinDigit(digit));
        }
        findViewById(R.id.btnPinClear).setOnClickListener(v -> {
            pinInput.setLength(0);
            updatePinDisplay();
        });
        findViewById(R.id.btnPinBack).setOnClickListener(v -> {
            // 返回认字界面
            layoutPin.setVisibility(View.GONE);
            layoutMain.setVisibility(View.VISIBLE);
            pinInput.setLength(0);
            updatePinDisplay();
        });
    }

    // ==================== 认字题目 ====================

    private void loadNextQuestion() {
        currentChar = bank.getRandomCharacter();
        correctAnswer = currentChar.pinyin;
        currentOptions = bank.getOptions(correctAnswer);

        tvCharacter.setText(currentChar.character);

        for (int i = 0; i < optionButtons.length && i < currentOptions.size(); i++) {
            optionButtons[i].setText(currentOptions.get(i));
            optionButtons[i].setBackgroundResource(R.drawable.btn_option_bg);
            optionButtons[i].setEnabled(true);
        }

        updateProgress();
        tvFeedback.setVisibility(View.GONE);
    }

    private void onOptionSelected(int index) {
        // 立即禁用所有选项，防止反馈延迟期间重复点击导致状态错乱
        for (Button b : optionButtons) {
            b.setEnabled(false);
        }

        String selected = currentOptions.get(index);
        if (selected.equals(correctAnswer)) {
            // 答对了
            correctCount++;
            optionButtons[index].setBackgroundResource(R.drawable.btn_option_correct);
            tvFeedback.setText("答对了！");
            tvFeedback.setTextColor(ContextCompat.getColor(this, R.color.correct_green));
            tvFeedback.setVisibility(View.VISIBLE);

            if (correctCount >= requiredCount) {
                // 全部答对，解锁
                handler.postDelayed(this::unlock, 600);
            } else {
                handler.postDelayed(this::loadNextQuestion, 1000);
            }
        } else {
            // 答错了
            correctCount = 0;
            optionButtons[index].setBackgroundResource(R.drawable.btn_option_wrong);
            // 高亮正确答案
            for (int i = 0; i < currentOptions.size(); i++) {
                if (currentOptions.get(i).equals(correctAnswer)) {
                    optionButtons[i].setBackgroundResource(R.drawable.btn_option_correct);
                    break;
                }
            }
            tvFeedback.setText("答错了，重新开始！");
            tvFeedback.setTextColor(ContextCompat.getColor(this, R.color.wrong_red));
            tvFeedback.setVisibility(View.VISIBLE);
            handler.postDelayed(this::loadNextQuestion, 1500);
        }
    }

    private void updateProgress() {
        tvProgress.setText(String.format("已答对 %d/%d 道题即可解锁", correctCount, requiredCount));
    }

    // ==================== 解锁 ====================

    private void unlock() {
        pref.setLocked(false);
        pref.setHomeAliasEnabled(this, false);
        // 解锁后自动重新开始计时（使用当前设置的观看时长）
        int minutes = pref.getWatchLimitMinutes();
        pref.startTimer(minutes);
        pref.resetPinFailures();
        // 先关闭 Activity，再启动服务，避免竞态条件
        finishAffinity();
        // 通知服务移除悬浮窗，服务会重新进入计时模式
        LockService.requestUnlock(this);
    }

    // ==================== 家长PIN ====================

    private void showPinPad() {
        layoutMain.setVisibility(View.GONE);
        layoutPin.setVisibility(View.VISIBLE);
        pinInput.setLength(0);
        updatePinDisplay();
    }

    private void onPinDigit(int digit) {
        if (pinInput.length() < 4) {
            pinInput.append(digit);
            updatePinDisplay();
            if (pinInput.length() == 4) {
                handler.postDelayed(this::checkPin, 200);
            }
        }
    }

    private void updatePinDisplay() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i < pinInput.length()) {
                sb.append("●");
            } else {
                sb.append("○");
            }
            if (i < 3) sb.append("  ");
        }
        tvPinDisplay.setText(sb.toString());
    }

    private void checkPin() {
        if (pref.isPinLocked()) {
            long remainingSec = pref.getPinLockRemainingMillis() / 1000;
            Toast.makeText(this, "尝试次数过多，请 " + remainingSec + " 秒后再试", Toast.LENGTH_SHORT).show();
            pinInput.setLength(0);
            updatePinDisplay();
            return;
        }
        if (pref.verifyPin(pinInput.toString())) {
            pref.resetPinFailures();
            unlock();
        } else {
            pref.recordPinFailure();
            Toast.makeText(this, "PIN码错误", Toast.LENGTH_SHORT).show();
            pinInput.setLength(0);
            updatePinDisplay();
        }
    }

    // ==================== 防绕过 ====================

    @Override
    public void onBackPressed() {
        // 屏蔽返回键，不允许退出锁屏
        // 如果在PIN界面，返回认字界面
        if (layoutPin.getVisibility() == View.VISIBLE) {
            layoutPin.setVisibility(View.GONE);
            layoutMain.setVisibility(View.VISIBLE);
            pinInput.setLength(0);
            updatePinDisplay();
        }
        // 否则什么都不做
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        // 移除 reLockRunnable，避免与 watchdog 冲突导致循环
        // watchdog 会负责重新拉起锁屏
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        foregroundInstance = this;
        hideSystemUI();
        
        // 监听系统 UI 可见性变化，导航栏弹出时立即隐藏
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                // 导航栏显示了，立即隐藏
                hideSystemUI();
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 清掉答题反馈/解锁用的延迟回调，避免在已销毁的实例上继续执行
        handler.removeCallbacksAndMessages(null);
        if (foregroundInstance == this) foregroundInstance = null;
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decorView.setSystemUiVisibility(uiOptions);
        
        // 额外保障：设置 Window 标志防止导航栏弹出
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        );
    }
}
