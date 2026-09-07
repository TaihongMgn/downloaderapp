package cc.haoran.dydl;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** App 原生设置页: 悬浮球开关 / 剪贴板监控状态 / 存储与版本信息 */
public class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private Switch ballSwitch;
    private Switch clipSwitch;
    private LinearLayout clipSub;
    private TextView a11yState;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("dydl", MODE_PRIVATE);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从授权页回来: 若悬浮球待开启且已授权 → 自动开启
        if (prefs.getBoolean("ball_pending", false)) {
            if (Settings.canDrawOverlays(this)) {
                setBall(true);
                Toast.makeText(this, "悬浮球已开启", Toast.LENGTH_SHORT).show();
            } else {
                prefs.edit().putBoolean("ball_pending", false).apply();
            }
        }
        refresh();
    }

    private android.view.View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0f1115"));
        root.setPadding(0, dp(8), 0, 0);

        // 标题栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.parseColor("#171a21"));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(Color.parseColor("#9aa3b2"));
        back.setTextSize(22);
        back.setPadding(0, 0, dp(14), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = new TextView(this);
        title.setText("App 设置");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        root.addView(section("后台监控"));

        // 自启动引导 (国产 ROM 重启后会回收无障碍/后台服务)
        LinearLayout rowAuto = clickableRow();
        rowAuto.addView(col("自启动权限", "重启后监控失效？请允许本 App 自启动/后台运行"));
        TextView autoArrow = new TextView(this);
        autoArrow.setText("›");
        autoArrow.setTextColor(Color.parseColor("#9aa3b2"));
        autoArrow.setTextSize(18);
        rowAuto.addView(autoArrow, new LinearLayout.LayoutParams(-2, -2));
        rowAuto.setOnClickListener(v -> openAutoStart());
        root.addView(rowAuto, rowParams());

        // 悬浮球 (真开关)
        LinearLayout row1 = clickableRow();
        row1.addView(col("悬浮球", "桌面小球：单击读剪贴板入队，长按打开 App"));
        ballSwitch = new Switch(this);
        ballSwitch.setClickable(false);
        row1.addView(ballSwitch, new LinearLayout.LayoutParams(-2, -2));
        row1.setOnClickListener(v -> toggleBall());
        root.addView(row1, rowParams());

        // 剪贴板监控 (状态 + 跳系统无障碍; 开关本身只能由用户在系统设置操作)
        LinearLayout row2 = clickableRow();
        row2.addView(col("剪贴板自动监控", "检测到抖音链接自动入队 · 点击前往系统无障碍设置"));
        a11yState = new TextView(this);
        a11yState.setTextColor(Color.parseColor("#9aa3b2"));
        a11yState.setTextSize(15);
        row2.addView(a11yState, new LinearLayout.LayoutParams(-2, -2));
        row2.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) {}
        });
        root.addView(row2, rowParams());

        // 剪贴板自动解析开关 (需无障碍开启; 关闭=仅暂停自动入队, 不影响悬浮球)
        LinearLayout row3 = clickableRow();
        clipSub = col("剪贴板自动解析", "复制抖音链接后自动解析并下载");
        row3.addView(clipSub);
        clipSwitch = new Switch(this);
        clipSwitch.setClickable(false);
        row3.addView(clipSwitch, new LinearLayout.LayoutParams(-2, -2));
        row3.setOnClickListener(v -> {
            if (!ClipboardWatcherService.isEnabled(this)) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception ignored) {}
                return;
            }
            boolean want = !prefs.getBoolean("clip_monitor_enabled", true);
            prefs.edit().putBoolean("clip_monitor_enabled", want).apply();
            refresh();
        });
        root.addView(row3, rowParams());

        root.addView(section("下载"));
        root.addView(infoRow("保存位置", "DCIM/Hrdl（音频 Music/Hrdl）"));
                try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            root.addView(infoRow("版本", "v" + ver));
        } catch (Exception ignored) {}

        TextView foot = new TextView(this);
        foot.setText("下载进度与结果在通知栏查看");
        foot.setTextColor(Color.parseColor("#555d6d"));
        foot.setTextSize(12);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(24), 0, 0);
        root.addView(foot, new LinearLayout.LayoutParams(-1, -2));

        return root;
    }

    private TextView section(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.parseColor("#9aa3b2"));
        v.setTextSize(13);
        v.setPadding(dp(18), dp(18), dp(18), dp(6));
        return v;
    }

    private LinearLayout clickableRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setBackgroundColor(Color.parseColor("#171a21"));
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(18), dp(14), dp(18), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(2);
        return r;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        return lp;
    }

    private LinearLayout col(String title, String sub) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15.5f);
        TextView d = new TextView(this);
        d.setText(sub);
        d.setTextColor(Color.parseColor("#9aa3b2"));
        d.setTextSize(12.5f);
        d.setPadding(0, dp(2), 0, 0);
        col.addView(t, new LinearLayout.LayoutParams(-2, -2));
        col.addView(d, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.rightMargin = dp(10);
        col.setLayoutParams(lp);
        return col;
    }

    private LinearLayout infoRow(String k, String v) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(18), dp(10), dp(18), dp(10));
        TextView k1 = new TextView(this);
        k1.setText(k);
        k1.setTextColor(Color.parseColor("#e8eaf0"));
        k1.setTextSize(14.5f);
        r.addView(k1, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v1 = new TextView(this);
        v1.setText(v);
        v1.setTextColor(Color.parseColor("#9aa3b2"));
        v1.setTextSize(14);
        r.addView(v1, new LinearLayout.LayoutParams(-2, -2));
        return r;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------- 逻辑 ----------------

    private void toggleBall() {
        boolean want = !prefs.getBoolean("ball_enabled", false);
        if (want && !Settings.canDrawOverlays(this)) {
            // 跳授权页, 授权回来自动开启
            prefs.edit().putBoolean("ball_pending", true).apply();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); } catch (Exception ignored) {}
            }
            return;
        }
        setBall(want);
    }

    private void setBall(boolean on) {
        prefs.edit().putBoolean("ball_enabled", on).putBoolean("ball_pending", false).apply();
        if (on) startService(new Intent(this, FloatBallService.class));
        else stopService(new Intent(this, FloatBallService.class));
        refresh();
    }

    private void refresh() {
        boolean on = prefs.getBoolean("ball_enabled", false) && Settings.canDrawOverlays(this)
                && FloatBallService.running;
        ballSwitch.setChecked(on);
        boolean a11y = ClipboardWatcherService.isEnabled(this);
        a11yState.setText(a11y ? "✓ 已开启" : "未开启");
        boolean clipOn = a11y && prefs.getBoolean("clip_monitor_enabled", true);
        clipSwitch.setChecked(clipOn);
        if (a11y) { for (int i = 0; i < clipSub.getChildCount(); i++) ((TextView) clipSub.getChildAt(i)).setTextColor(Color.WHITE); }
    }

    /** 打开 ROM 自启动管理页 (各家不同, 逐个尝试常见入口) */
    private void openAutoStart() {
        String[][] attempts = {
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"},
        };
        for (String[] a : attempts) {
            try {
                Intent i = new Intent();
                i.setClassName(a[0], a[1]);
                startActivity(i);
                return;
            } catch (Exception ignored) {}
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {}
        Toast.makeText(this, "请在系统设置中允许本应用自启动/后台运行", Toast.LENGTH_LONG).show();
    }
}
