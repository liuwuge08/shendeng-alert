package com.pinodewatch.alert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences sp = context.getSharedPreferences("alert", Context.MODE_PRIVATE);
            String appId = sp.getString("app_id", "");
            boolean autoStart = sp.getBoolean("auto_start", true);
            
            if (!appId.isEmpty() && autoStart) {
                Intent serviceIntent = new Intent(context, AlertService.class);
                serviceIntent.putExtra("app_id", appId);
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
