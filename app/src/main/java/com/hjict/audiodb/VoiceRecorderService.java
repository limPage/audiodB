package com.hjict.audiodb;

import android.annotation.SuppressLint;
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

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

public class VoiceRecorderService extends Service {
    private AudioRecord recorder;
    private boolean isRunning = false;
    private VadWrapper vad;
    private ByteArrayOutputStream speechBuffer;
    private CircularAudioBuffer preBuffer;
    private long lastSpeechTime = 0;
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SIZE = 160; // 10ms @ 16kHz
    private static final long SILENCE_TIMEOUT_MS = 3000; // 3초

    // 스레드 풀 및 큐
    private ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    private ExecutorService analysisExecutor = Executors.newFixedThreadPool(2);
    private ExecutorService whisperExecutor = Executors.newFixedThreadPool(1);
    private LinkedBlockingQueue<Pair<File, byte[]>> saveQueue = new LinkedBlockingQueue<>();
    private LinkedBlockingQueue<File> analysisQueue = new LinkedBlockingQueue<>();
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
                .setSmallIcon(R.drawable.ic_launcher_background)
                .build();

        startForeground(1, notification);

        int frameSize = FRAME_SIZE * 2;
        preBuffer = new CircularAudioBuffer(300, frameSize);
        vad = new VadWrapper();
        vad.init(3);

        // 저장 및 분석 스레드 시작
        startSaveThread();
        startAnalysisThread();

        new Thread(this::recordLoop).start();
        return START_STICKY;
    }



    private static class Pair<F, S> {
        final F first;
        final S second;

        Pair(F first, S second) {
            this.first = first;
            this.second = second;
        }
    }
    private void startSaveThread() {
        saveExecutor.submit(() -> {
            while (!saveExecutor.isShutdown()) {
                try {
                    Pair<File, byte[]> pair = saveQueue.take();
                    File file = pair.first;
                    byte[] pcm = pair.second;
                    try {
                        saveWavFile(file, pcm);
                        analysisQueue.put(file);
                        Log.i("Recorder", "WAV 파일 저장 완료: " + file.getAbsolutePath());
                    } catch (IOException e) {
                        Log.e("Recorder", "Failed to save WAV file: " + file.getAbsolutePath() + ", Reason: " + e.getMessage(), e);
                    }
                } catch (InterruptedException e) {
                    Log.e("Recorder", "Save thread interrupted", e);
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    private void startAnalysisThread() {
        analysisExecutor.submit(() -> {
            while (!analysisExecutor.isShutdown()) {
                try {
                    File file = analysisQueue.take();
                    WavOfflineAnalyzer analyzer = new WavOfflineAnalyzer();
                    if (analyzer.hasHumanVoice(file)) {
                        Log.i("STT", "▶ Whisper STT 실행 대상: " + file.getAbsolutePath());
                        whisperExecutor.submit(() -> runWhisper(file));
                    } else {
                        Log.i("STT", "음성 아님: " + file.getAbsolutePath());
                    }
                } catch (InterruptedException e) {
                    Log.e("Recorder", "Analysis thread interrupted", e);
                }
            }
        });
    }

    private void runWhisper(File file) {
        try {
            // TODO: Whisper STT 구현 (예: ONNX Runtime, TensorFlow Lite)
            Log.i("Whisper", "Whisper 처리 중: " + file.getAbsolutePath());
            // 예: String text = whisperModel.transcribe(file);
            // 결과 처리 (DB 저장, 알림 등)
        } catch (Exception e) {
            Log.e("Whisper", "Whisper 처리 실패: " + file.getAbsolutePath(), e);
        }
    }

    @SuppressLint("MissingPermission")
    private void recordLoop() {
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2;

        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize);

        recorder.startRecording();
        isRunning = true;

        final long MAX_RECORDING_MS = 6000;
        final long MIN_RECORDING_MS = 1000;
        final long SILENCE_TIMEOUT_MS = 3000;
        final int SLIDING_WINDOW_FRAMES = 32;

        long recordingStartTime = 0;
        long lastSpeechTime = 0;
        boolean isRecording = false;

        speechBuffer = new ByteArrayOutputStream();
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
            if (fftWindow.size() > SLIDING_WINDOW_FRAMES) fftWindow.remove(0);

            double db = Utils.calculateDb(buffer, read);
            boolean isSpeech = vad.isSpeech(buffer, SAMPLE_RATE);
            boolean isVoiceFreq = false;

            if (fftWindow.size() == SLIDING_WINDOW_FRAMES) {
                byte[] fftInput = mergeFrames(fftWindow);
                isVoiceFreq = FftUtils.isHumanVoice(fftInput, SAMPLE_RATE);
            }

            long now = System.currentTimeMillis();
            if (db > -35 && isSpeech && isVoiceFreq) {
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

                if (silenceDuration >= SILENCE_TIMEOUT_MS && duration >= MIN_RECORDING_MS) {
                    finalizeAndSave(preBuffer, speechBuffer, duration, MAX_RECORDING_MS);
                    isRecording = false;
                    speechBuffer.reset();
                } else if (duration >= MAX_RECORDING_MS) {
                    finalizeAndSave(preBuffer, speechBuffer, duration, MAX_RECORDING_MS);
                    isRecording = false;
                    speechBuffer.reset();
                }
            }else{
                preBuffer.addChunk(buffer);
            }
        }

        recorder.stop();
        recorder.release();
    }

    private void finalizeAndSave(CircularAudioBuffer preBuffer, ByteArrayOutputStream speechBuffer,
                                 long duration, long maxMs) {
        byte[] speech = speechBuffer.toByteArray();
        final int frameBytes = FRAME_SIZE * 2;
        final int fixedPreMs = 1000;
        int fixedPreBytes = (fixedPreMs / 10) * frameBytes;

        byte[] preData = preBuffer.getLastNBytes(fixedPreBytes);
        byte[] padding = new byte[Math.max(0, fixedPreBytes - preData.length)];

        try {
            ByteArrayOutputStream finalAudio = new ByteArrayOutputStream();
            finalAudio.write(padding);
            finalAudio.write(preData);
            finalAudio.write(speech);

            File dir = new File(getExternalFilesDir(null), "vad_speech");
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e("Recorder", "Failed to create directory: " + dir.getAbsolutePath());
                return;
            }

            String timeStamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            File file = new File(dir, "utterance_" + timeStamp + ".wav");
            saveQueue.put(new Pair<>(file, finalAudio.toByteArray())); // Pair로 추가
            Log.i("Recorder", "■ 녹음 저장 요청: " + file.getAbsolutePath());

        } catch (IOException | InterruptedException e) {
            Log.e("Recorder", "저장 실패: " + e.getMessage(), e);
        }
    }

    private void saveWavFile(File file, byte[] pcm) throws IOException {
        byte[] wav = Utils.pcmToWav(pcm, SAMPLE_RATE);
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(wav);
        fos.close();
        Log.d("VAD", "Saved: " + file.getAbsolutePath());
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
}