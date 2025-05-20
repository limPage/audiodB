package com.hjict.audiodb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

public class SpeechRecorderService extends Service {
    private SpeechSegmentRecorderWithPreBuffer recorder;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        startForeground(1, createNotification());

        recorder = new SpeechSegmentRecorderWithPreBuffer(getApplicationContext());
        recorder.start();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (recorder != null) recorder.stop();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // 바인드형 서비스 아님
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "speech_channel", "Speech Recorder", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, "speech_channel")
                .setContentTitle("음성 감지 중")
                .setContentText("비상벨이 음성을 분석 중입니다.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
    }
}
