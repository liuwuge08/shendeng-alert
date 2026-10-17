package com.pinodewatch.alert;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import androidx.core.app.NotificationCompat;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.util.Set;
import android.net.wifi.WifiManager;
import android.os.PowerManager;

public class AlertService extends Service {

    public static boolean isRunning = false;
    private static final int NOTIFICATION_ID = 1001;

    private final IBinder binder = new AlertBinder();
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String appId = "";
    private boolean sseRunning = false;
    private android.media.AudioManager audioManager;
    private int originalVolume = -1;
    private Thread sseThread = null;
    private Set<String> msgIds = new HashSet<>();
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    public class AlertBinder extends Binder {
        public AlertService getService() {
            return AlertService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        // WiFi锁，防止息屏断网
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AlertWiFiLock");
        wifiLock.setReferenceCounted(false);
        // 唤醒锁，防止CPU休眠
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlertWakeLock");
        wakeLock.setReferenceCounted(false);
        initTTS();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        appId = intent.getStringExtra("app_id");
        if (appId == null || appId.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("神灯告警服务运行中"));
        // 加锁保活
        try { wifiLock.acquire(); } catch (Exception e) {}
        try { wakeLock.acquire(); } catch (Exception e) {}
        isRunning = true;
        startSSE();
        return START_STICKY;
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, AlertApp.CHANNEL_ID)
            .setContentTitle("神灯监控告警")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    private void initTTS() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.CHINA);
                ttsReady = (result == TextToSpeech.LANG_COUNTRY_AVAILABLE
                            || result == TextToSpeech.LANG_AVAILABLE);
                tts.setPitch(1.0f);
                tts.setSpeechRate(1.0f);
            }
        });
    }

    private void speakAlert(String text) {
        if (!ttsReady || tts == null) return;

        // 保存原音量，拉满媒体音量
        try {
            originalVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            int maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, maxVolume, 0);
        } catch (Exception e) {
            originalVolume = -1;
        }

        // 播报3遍
        String fullText = text + "。" + text + "。" + text;
        tts.speak(fullText, TextToSpeech.QUEUE_ADD, null, "alert_" + System.currentTimeMillis());
    }

    private void startSSE() {
        if (sseRunning) return;
        sseRunning = true;

        sseThread = new Thread(() -> {
            int retryCount = 0;
            while (sseRunning) {
                try {
                    connectSSE();
                } catch (Exception e) {
                    retryCount++;
                    long delay = Math.min(retryCount * 3000L, 30000L);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        });

        sseThread.start();
    }

    private void connectSSE() throws Exception {
        // 信任所有SSL证书（解决部分手机Let's Encrypt证书验证失败问题）
        try {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {}

        String sseUrl = AlertApp.SERVER_BASE + "/api/alert/app/sse/" + appId;
        URL url = new URL(sseUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(0);
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Cache-Control", "no-cache");
        conn.setRequestProperty("Connection", "keep-alive");

        InputStream is = conn.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));

        updateNotification("神灯告警服务运行中 - 已连接");

        String line;
        StringBuilder eventData = new StringBuilder();
        String eventName = "message";

        while (sseRunning && (line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (eventData.length() > 0) {
                    handleSSEEvent(eventName, eventData.toString());
                }
                eventData = new StringBuilder();
                eventName = "message";
            } else if (line.startsWith("event:")) {
                eventName = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (eventData.length() > 0) eventData.append("\n");
                eventData.append(line.substring(5).trim());
            }
        }

        reader.close();
        conn.disconnect();
    }

    private void handleSSEEvent(String event, String data) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(data);
            String type = json.optString("type", "");

            if ("alert".equals(type)) {
                String msgId = json.optString("msg_id", "");
                String msgText = json.optString("text", "");

                if (!msgId.isEmpty() && msgIds.contains(msgId)) {
                    return;
                }
                if (!msgId.isEmpty()) {
                    msgIds.add(msgId);
                    if (msgIds.size() > 100) {
                        msgIds.clear();
                    }
                }

                if (!msgText.isEmpty()) {
                    speakAlert(msgText);
                    updateNotification("新告警: " + msgText);
                }
            }
        } catch (Exception e) {
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    @Override
    public void onDestroy() {
        sseRunning = false;
        isRunning = false;
        // 释放锁
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception e) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception e) {}
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        // 恢复原音量
        if (originalVolume >= 0 && audioManager != null) {
            try {
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, originalVolume, 0);
            } catch (Exception e) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
