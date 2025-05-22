package com.hjict.audiodb;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/// 말 시작 3초 전 포함 버전
public class SpeechSegmentRecorderWithPreBuffer {

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

    private static final double START_THRESHOLD_DB = -30.0;
    private static final double STOP_THRESHOLD_DB = -45.0;
    private static final int SILENCE_TIMEOUT_MS = 1000;
    private static final int PRE_BUFFER_SECONDS = 3;

    private final Context context;
    private AudioRecord recorder;
    private boolean isRunning = false;
    private boolean isRecording = false;
    private long lastSpeechTime = 0;

    private ByteArrayOutputStream currentBuffer;
    private int fileIndex = 0;

    // 3초 pre-buffer용 ring buffer
    private final CircularAudioBuffer preBuffer;

    public SpeechSegmentRecorderWithPreBuffer(Context ctx) {
        this.context = ctx;
        int chunkSize = BUFFER_SIZE;
        int chunkCount = (SAMPLE_RATE * 2 * PRE_BUFFER_SECONDS) / chunkSize;  // 2 bytes/sample
        preBuffer = new CircularAudioBuffer(chunkCount, chunkSize);
    }
    @SuppressLint("MissingPermission")
    public void start() {
        isRunning = true;
        new Thread(() -> {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE);

            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e("SpeechRecorder", "AudioRecord 초기화 실패");
                return;
            }

            recorder.startRecording();
            byte[] buffer = new byte[BUFFER_SIZE];

            while (isRunning) {
                int read = recorder.read(buffer, 0, buffer.length);
                Log.e("SpeechRecorder", ""+read);
                if (read > 0) {
                    double db = calculateDb(buffer, read);
                    long now = System.currentTimeMillis();
//                    Log.d("SpeechRecorder", "dB: " + db);

                    // 항상 preBuffer에 저장
                    preBuffer.addChunk(buffer);

                    if (db > START_THRESHOLD_DB) {
                        lastSpeechTime = now;

                        if (!isRecording) {
                            Log.d("SpeechRecorder", "▶ 음성 감지 "+ (int) db);
                            currentBuffer = new ByteArrayOutputStream();
                            try {
                                currentBuffer.write(preBuffer.getBufferedData());
                            } catch (IOException e) {
                                Log.e("SpeechRecorder", "preBuffer write 실패", e);
                            }
                            isRecording = true;
                        }
                    }

                    if (isRecording) {
                        currentBuffer.write(buffer, 0, read);
                        if (now - lastSpeechTime > SILENCE_TIMEOUT_MS) {
                            Log.d("SpeechRecorder", "■ 음성 종료 → 파일 저장" + (int) db);
                            saveWavFile(currentBuffer.toByteArray());
                            isRecording = false;
                        }
                    }
                }
            }

            recorder.stop();
            recorder.release();
        }).start();
    }

    public void stop() {
        isRunning = false;
    }

    private void saveWavFile(byte[] pcmData) {
        try {
            File dir = new File(context.getExternalFilesDir(null), "utterances");
            if (!dir.exists()) dir.mkdirs();

            File file = new File(dir, "utterance_" + (fileIndex++) + ".wav");
            FileOutputStream fos = new FileOutputStream(file);

            byte[] wavData = Utils.pcmToWav(pcmData, SAMPLE_RATE);
            fos.write(wavData);
            fos.close();

            Log.d("SpeechRecorder", "파일 저장됨: " + file.getAbsolutePath());
        } catch (IOException e) {
            Log.e("SpeechRecorder", "파일 저장 실패", e);
        }
    }

    private double calculateDb(byte[] audioData, int readBytes) {
        long sum = 0;
        for (int i = 0; i < readBytes; i += 2) {
            short s = (short) ((audioData[i + 1] << 8) | (audioData[i] & 0xFF));
            sum += s * s;
        }
        double rms = Math.sqrt(sum / (readBytes / 2.0));
        return 20.0 * Math.log10(rms / 32768.0 + 1e-6);
    }
}
