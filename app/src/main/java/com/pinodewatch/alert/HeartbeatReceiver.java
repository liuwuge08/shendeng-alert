package com.pinodewatch.alert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class HeartbeatReceiver extends BroadcastReceiver {
    private static final String TAG = "HeartbeatReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if ("com.pinodewatch.alert.HEARTBEAT".equals(action)) {
            Log.d(TAG, "心跳唤醒，检查服务状态");
            // 检查AlertService是否在运行，不在则启动
            try {
                Intent serviceIntent = new Intent(context, AlertService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } catch (Exception e) {
                Log.e(TAG, "心跳重启服务失败: " + e.getMessage());
            }
        }
    }
}
