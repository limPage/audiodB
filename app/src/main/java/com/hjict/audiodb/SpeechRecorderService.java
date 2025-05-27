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

import com.hjict.audiodb.Utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import java.text.SimpleDateFormat;
import java.util.Date;
public class SpeechRecorderService extends Service {
    private AudioRecord recorder;
    private boolean isRunning = false;
    private VadWrapper vad;
    private ByteArrayOutputStream speechBuffer;

    private CircularAudioBuffer preBuffer;
    private long lastSpeechTime = 0;
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SIZE = 160; // 10ms @ 16kHz
    private static final int SILENCE_TIMEOUT_MS = 1000;

    private boolean isRecording = false;
    private int silenceFrameCount = 0;
    private int speechFrameCount = 0;
    private final int MIN_SPEECH_FRAMES = 100;  // 예: 100 frames = 1초 (10ms * 100)
    private final int MAX_SILENCE_FRAMES = 100; // 예: 200 frames = 2초 (10ms * 200)

    WavOfflineAnalyzer isTest = new WavOfflineAnalyzer();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Foreground 서비스용 알림 생성
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "speech_channel",
                    "Speech Recorder",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Notification notification = new NotificationCompat.Builder(this, "speech_channel")
                .setContentTitle("음성 감지 중")
                .setContentText("녹음 중입니다...")
                .setSmallIcon(R.drawable.ic_launcher_background)  // 적절한 아이콘 설정 필요
                .build();

        startForeground(1, notification); // 필수 호출

        // 3초 = 300 프레임 (10ms), 프레임당 160 samples * 2 bytes = 320 bytes
        int frameSize = FRAME_SIZE * 2;
        preBuffer = new CircularAudioBuffer(300, frameSize); // 300개 프레임 보관용

        // 나머지 로직
        vad = new VadWrapper();
        vad.init(3); //  민감
        new Thread(this::recordLoop).start();
        return START_STICKY;
    }


private void recordLoop() {
    int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

    recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufferSize);

    recorder.startRecording();
    isRunning = true;

    final int FRAME_SIZE = 160; // 10ms @ 16kHz
    final long MAX_RECORDING_MS = 5000; // 최대 5초
    final long MIN_RECORDING_MS = 3000; // 최소 3초
    final long SILENCE_TIMEOUT_MS = 2000; // 2초 무음
    final int SLIDING_WINDOW_FRAMES = 16; // 160ms (2560 샘플, 2의 제곱수에 가까움)

    long recordingStartTime = 0;
    long lastSpeechTime = 0;
    boolean isRecording = false;

    speechBuffer = new ByteArrayOutputStream();
    preBuffer = new CircularAudioBuffer(300, FRAME_SIZE * 2); // 3초 프리버퍼
    List<byte[]> fftWindow = new ArrayList<>();

    byte[] buffer = new byte[FRAME_SIZE * 2];
    while (isRunning) {
        int read = recorder.read(buffer, 0, buffer.length);
        if (read <= 0) {
            Log.e("Recorder", "read() failed with result = " + read);
            restartRecorder();
            continue;
        }
        if (read != buffer.length) continue;

        fftWindow.add(buffer.clone());
        if (fftWindow.size() > SLIDING_WINDOW_FRAMES) fftWindow.remove(0); // 160ms 윈도우 유지

        double db = Utils.calculateDb(buffer, read);
        boolean isSpeech = vad.isSpeech(buffer, SAMPLE_RATE);
        boolean isVoiceFreq = false;

        if (fftWindow.size() == SLIDING_WINDOW_FRAMES) {
            byte[] fftInput = mergeFrames(fftWindow);
            isVoiceFreq = FftUtils.isHumanVoice(fftInput, SAMPLE_RATE);
        }

        long now = System.currentTimeMillis();
        if (db > -30 && isSpeech && isVoiceFreq) {
            if (!isRecording) {
                isRecording = true;
                recordingStartTime = now;
                speechBuffer.reset();
                Log.i("Recorder", "▶ 녹음 시작");
            }
            lastSpeechTime = now;

        }
        if (isRecording) {
            try {
                speechBuffer.write(buffer.clone());
            } catch (IOException e) {
                Log.e("Recorder", "버퍼 쓰기 실패", e);
            }

            long duration = now - recordingStartTime;
            long silenceDuration = now - lastSpeechTime;

            if (silenceDuration >= SILENCE_TIMEOUT_MS) {
                if (duration < MAX_RECORDING_MS) {
                    finalizeAndSave(preBuffer, speechBuffer, duration, MAX_RECORDING_MS);
                    Log.i("Recorder", "■ 녹음 저장 완료 (pre: " + Math.round((MAX_RECORDING_MS - duration) * 10) / 10.0 + "ms)");
                } else {
                    finalizeAndSave(preBuffer, speechBuffer, duration, MAX_RECORDING_MS);
                    isRecording = false;
                    preBuffer.reset();
                    Log.i("Recorder", "■ 녹음 저장 완료 (최대 시간 초과)");
                }
                isRecording = false;
                preBuffer.reset();
            }
        } else {
            preBuffer.addChunk(buffer);
        }
    }

    recorder.stop();
    recorder.release();
}

    private void saveWavFile(byte[] pcm) {
        try {
            File dir = new File(getExternalFilesDir(null), "vad_speech");
            if (!dir.exists()) dir.mkdirs();

//            File file = new File(dir, "utterance_" + System.currentTimeMillis() + ".wav");
            String timeStamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            File file = new File(dir, "voice_" + timeStamp + ".wav");
            byte[] wav = Utils.pcmToWav(pcm, SAMPLE_RATE);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(wav);
            fos.close();

            Log.d("VAD", "Saved: " + file.getAbsolutePath());


//            isTest.analyzeWav(file);
            // 🎯 여기서 분석
            boolean hasVoice = isTest.hasHumanVoice(file);
            if (hasVoice) {
                Log.i("STT", "▶ Whisper STT 실행 대상입니다.");
                //runWhisper(file);  // 👈 Whisper 실행 함수 연결
            } else {
                Log.i("STT", "⛔ 사람이 말한 내용 없음 → STT 생략됨");
            }
        } catch (Exception e) {
            Log.e("VAD", "파일 저장 실패", e);
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // 보조 함수
    private byte[] mergeFrames(List<byte[]> frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] f : frames) {
            try {
                out.write(f);
            } catch (IOException e) {
                Log.e("Recorder", "merge 실패", e);
            }
        }
        return out.toByteArray();
    }

    // 보조 함수
    private void finalizeAndSave(CircularAudioBuffer preBuffer, ByteArrayOutputStream speechBuffer,
                                 long duration, long maxMs) {
        byte[] speech = speechBuffer.toByteArray();
        final int frameBytes = 160 * 2;

        byte[] preData;
        byte[] padding = new byte[0];

        if (duration > maxMs) {
            // ✅ duration이 길면 항상 1초 프리버퍼 붙이기
            int fixedPreMs = 1000;
            int fixedPreBytes = (fixedPreMs / 10) * frameBytes;

            preData = preBuffer.getLastNBytes(fixedPreBytes);
            int paddingBytes = fixedPreBytes - preData.length;
            if (paddingBytes > 0) {
                padding = new byte[paddingBytes]; // 0으로 채움
            }
        } else {
            // ✅ duration이 짧으면 부족한 만큼만 프리버퍼 보완
            long missingMs = maxMs - duration;
            int neededFrames = (int)(missingMs / 10);
            int neededBytes = neededFrames * frameBytes;

            preData = preBuffer.getLastNBytes(neededBytes);
            int paddingBytes = neededBytes - preData.length;
            if (paddingBytes > 0) {
                padding = new byte[paddingBytes];
            }
        }

        try {
            ByteArrayOutputStream finalAudio = new ByteArrayOutputStream();
            finalAudio.write(padding);
            finalAudio.write(preData);
            finalAudio.write(speech);

            saveWavFile(finalAudio.toByteArray());

            Log.i("Recorder", String.format(
                    "■ 녹음 저장 완료 (duration: %dms, pre: %.1f초, padding: %.1f초)",
                    duration,
                    preData.length / 320.0 / 100.0,
                    padding.length / 320.0 / 100.0
            ));
        } catch (IOException e) {
            Log.e("Recorder", "저장 실패", e);
        }
    }

    // 재시작하게
    private void restartRecorder() {
        try {
            recorder.stop();
            recorder.release();
        } catch (Exception e) {
            Log.e("Recorder", "restartRecorder 오류", e);
        }

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize);

        recorder.startRecording();
        Log.i("Recorder", "AudioRecord 재시작 완료");
    }


}
