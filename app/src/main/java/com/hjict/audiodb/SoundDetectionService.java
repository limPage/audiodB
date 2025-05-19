package com.hjict.audiodb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class SoundDetectionService extends Service {
    private static final int SAMPLE_RATE = 16000;
    private static final double THRESHOLD_DB = 70.0;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
    );

    private AudioRecord recorder;
    private boolean isRunning = false;
    private boolean isTriggered = false;
    private AudioBuffer preBuffer;

//    private void startForegroundService() {
//        String channelId = "voice_trigger_channel";
//        NotificationChannel channel = new NotificationChannel(
//                channelId, "Voice Trigger Service", NotificationManager.IMPORTANCE_LOW
//        );
//        NotificationManager manager = getSystemService(NotificationManager.class);
//        manager.createNotificationChannel(channel);
//
//        Notification notification = new NotificationCompat.Builder(this, channelId)
//                .setContentTitle("음성 감지 중")
//                .setSmallIcon(R.drawable.ic_launcher_foreground)
//                .build();
//
//        startForeground(1, notification);
//    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("Service", "Foreground Service 시작");

        // 알림 채널 생성 (Android 8 이상)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "voice_trigger_channel",
                    "Voice Trigger Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        // 알림 생성 및 Foreground 시작
        Notification notification = new NotificationCompat.Builder(this, "voice_trigger_channel")
                .setContentTitle("음성 감지 중")
                .setContentText("앱이 백그라운드에서 음성을 감지하고 있습니다.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();

        startForeground(1, notification);

        // ✅ 음성 감지 시작
        if (!isRunning) {
            isRunning = true;
            preBuffer = new AudioBuffer(SAMPLE_RATE); // AudioBuffer 초기화도 필요
            new Thread(this::recordLoop).start(); // ★ 중요: Thread로 실행
        }

        return START_STICKY;
    }



    private void recordLoop() {
        releaseRecorderSafely();

        recorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                BUFFER_SIZE
        );

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioRecord", "AudioRecord 초기화 실패");
            stopSelf();
            return;
        }

        recorder.startRecording();
        byte[] buffer = new byte[BUFFER_SIZE];

        while (isRunning) {
            int read = recorder.read(buffer, 0, buffer.length);

            if (read < 0) {
                Log.e("AudioRecord", "read 실패, 상태 코드: " + read);
                releaseRecorderSafely();

                // 재시도 타이밍 추가 (너무 빠르면 무한 루프 위험)
                try { Thread.sleep(500); } catch (InterruptedException e) {}
                recordLoop();  // 재귀 호출로 재시작
                return;
            }

            if (read > 0) {
                preBuffer.append(buffer, read);
                double db = Utils.calculateDb(buffer, read);
                Log.d("VoiceTrigger", String.valueOf((int) db));
                if (db > THRESHOLD_DB && !isTriggered) {
                    isTriggered = true;
                    Log.d("VoiceTrigger", "Trigger detected! dB: " + db);

                    new Thread(() -> {
                        byte[] triggeredData = Utils.recordWithHistory(preBuffer, SAMPLE_RATE, 5000);
                        Utils.saveWavFile(getApplicationContext(), triggeredData, SAMPLE_RATE, db);
                        isTriggered = false;
                    }).start();
                }
            }
        }

        releaseRecorderSafely();
    }

    private void releaseRecorderSafely() {
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception e) {
                Log.w("AudioRecord", "stop() 중 예외", e);
            }
            try {
                recorder.release();
            } catch (Exception e) {
                Log.w("AudioRecord", "release() 중 예외", e);
            }
            recorder = null;
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
