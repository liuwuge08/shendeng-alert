package com.pinodewatch.alert;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private Button btnToggle;
    private TextView tvStatus;
    private TextView tvBindCode;
    private Button btnRefreshCode;
    private boolean serviceRunning = false;
    private AlertService alertService = null;

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            alertService = ((AlertService.AlertBinder) binder).getService();
            serviceRunning = true;
            updateUI();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            alertService = null;
            serviceRunning = false;
            updateUI();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnToggle = findViewById(R.id.btn_toggle);
        tvStatus = findViewById(R.id.tv_status);
        tvBindCode = findViewById(R.id.tv_bind_code);
        btnRefreshCode = findViewById(R.id.btn_refresh_code);

        btnToggle.setOnClickListener(v -> toggleService());
        btnRefreshCode.setOnClickListener(v -> refreshBindCode());

        if (AlertService.isRunning) {
            Intent intent = new Intent(this, AlertService.class);
            bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } else {
            updateUI();
        }

        updateBindCode();
    }

    private void updateBindCode() {
        SharedPreferences sp = getSharedPreferences("alert", MODE_PRIVATE);
        String bindCode = sp.getString("bind_code", "");

        if (sp.getBoolean("bound", false)) {
            tvBindCode.setText("已绑定 ✓");
        } else if (!bindCode.isEmpty()) {
            tvBindCode.setText("绑定码: " + bindCode + "\n打开神灯网页输入绑定码");
        } else {
            tvBindCode.setText("正在获取绑定码...");
            new Thread(() -> registerApp()).start();
        }
    }

    private void refreshBindCode() {
        SharedPreferences sp = getSharedPreferences("alert", MODE_PRIVATE);
        sp.edit().remove("bind_code").remove("app_id").remove("bound").apply();
        tvBindCode.setText("正在获取绑定码...");
        new Thread(() -> registerApp()).start();
    }

    private void registerApp() {
        try {
            String url = AlertApp.SERVER_BASE + "/api/alert/app/register";
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int code = conn.getResponseCode();
            java.io.InputStream is;
            if (code >= 200 && code < 300) {
                is = conn.getInputStream();
            } else {
                is = conn.getErrorStream();
            }
            java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
            String response = s.hasNext() ? s.next() : "";
            s.close();

            JSONObject json = new JSONObject(response);
            if (json.optBoolean("success", false)) {
                String appId = json.getString("app_id");
                String bindCode = json.getString("bind_code");

                SharedPreferences sp = getSharedPreferences("alert", MODE_PRIVATE);
                sp.edit().putString("app_id", appId)
                        .putString("bind_code", bindCode)
                        .apply();

                runOnUiThread(() -> {
                    tvBindCode.setText("绑定码: " + bindCode + "\n打开神灯网页输入绑定码");
                });
            } else {
                final String err = json.optString("error", "未知错误");
                runOnUiThread(() -> {
                    tvBindCode.setText("获取失败: " + err);
                });
            }
        } catch (Exception e) {
            final String errMsg = e.getMessage() != null ? e.getMessage() : "网络错误";
            runOnUiThread(() -> {
                tvBindCode.setText("获取失败: " + errMsg);
            });
        }
    }

    private void toggleService() {
        if (serviceRunning) {
            Intent intent = new Intent(this, AlertService.class);
            stopService(intent);
            if (alertService != null) {
                unbindService(connection);
            }
            serviceRunning = false;
            alertService = null;
        } else {
            SharedPreferences sp = getSharedPreferences("alert", MODE_PRIVATE);
            String appId = sp.getString("app_id", "");
            if (appId.isEmpty()) {
                Toast.makeText(this, "正在获取绑定码，请稍候", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent intent = new Intent(this, AlertService.class);
            intent.putExtra("app_id", appId);
            startForegroundService(intent);
            bindService(intent, connection, Context.BIND_AUTO_CREATE);
        }
        updateUI();
    }

    private void updateUI() {
        if (serviceRunning) {
            btnToggle.setText("停止告警播报");
            btnToggle.setBackgroundColor(0xFFFF6B6B);
            tvStatus.setText("状态: 运行中 ✓");
            tvStatus.setTextColor(0xFF2ECC71);
        } else {
            btnToggle.setText("开启告警播报");
            btnToggle.setBackgroundColor(0xFF4ECDC4);
            tvStatus.setText("状态: 已停止");
            tvStatus.setTextColor(0xFF999999);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (alertService != null) {
            unbindService(connection);
        }
    }
}
