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
import java.util.Set;

public class AlertService extends Service {

    public static boolean isRunning = false;
    private static final int NOTIFICATION_ID = 1001;

    private final IBinder binder = new AlertBinder();
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String appId = "";
    private boolean sseRunning = false;
    private Thread sseThread = null;
    private Set<String> msgIds = new HashSet<>();

    public class AlertBinder extends Binder {
        public AlertService getService() {
            return AlertService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
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
        String fullText = text + "。" + text;
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
        sseThread.setDaemon(true);
        sseThread.start();
    }

    private void connectSSE() throws Exception {
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
            if ("alert".equals(event)) {
                org.json.JSONObject json = new org.json.JSONObject(data);
                String msgId = json.optString("id", "");
                String msg = json.optString("msg", "");

                if (!msgId.isEmpty() && msgIds.contains(msgId)) {
                    return;
                }
                if (!msgId.isEmpty()) {
                    msgIds.add(msgId);
                    if (msgIds.size() > 100) {
                        msgIds.clear();
                    }
                }

                if (!msg.isEmpty()) {
                    speakAlert(msg);
                    updateNotification("新告警: " + msg);
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
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
