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
    private static final int SILENCE_TIMEOUT_MS = 2000;

    private boolean isRecording = false;
    private int silenceFrameCount = 0;
    private int speechFrameCount = 0;
    private final int MIN_SPEECH_FRAMES = 100;  // 예: 100 frames = 1초 (10ms * 100)
    private final int MAX_SILENCE_FRAMES = 100; // 예: 200 frames = 2초 (10ms * 200)

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
        final long MAX_RECORDING_MS = 5000;
        final long MIN_RECORDING_MS = 3000;
        final long SILENCE_TIMEOUT_MS = 1000;

        long recordingStartTime = 0;
        long lastSpeechTime = 0;
        boolean isRecording = false;

        speechBuffer = new ByteArrayOutputStream();
        CircularAudioBuffer preBuffer = new CircularAudioBuffer(300, FRAME_SIZE * 2);
        List<byte[]> fftWindow = new ArrayList<>();

        byte[] buffer = new byte[FRAME_SIZE * 2];

        while (isRunning) {
            int read = recorder.read(buffer, 0, buffer.length);
            if (read != buffer.length) continue;

            preBuffer.addChunk(buffer);
            fftWindow.add(buffer.clone());
            if (fftWindow.size() > 3) fftWindow.remove(0);  // 50ms sliding window

            double db = Utils.calculateDb(buffer, read);
            boolean isSpeech = vad.isSpeech(buffer, SAMPLE_RATE);
            boolean isVoiceFreq = false;

            if (fftWindow.size() == 3) {
                byte[] fftInput = mergeFrames(fftWindow);
                isVoiceFreq = FftUtils.isHumanVoice(fftInput, SAMPLE_RATE);
            }

            long now = System.currentTimeMillis();
            if (isSpeech){
                if (db > -30 && isVoiceFreq) {
                    Log.i("limdb","db:"+(int)db+"  isSpeech:"+isSpeech+"  Freg:"+ isVoiceFreq);
                } else {
//                    Log.e("limdb","db:"+(int)db+"  isSpeech:"+isSpeech+"  Freg:"+ isVoiceFreq);
                }
            }else{
//                Log.e("limdb","db:"+(int)db+" isSpeech:"+isSpeech+"  Freg:"+ isVoiceFreq);

            }
            if (db > -30 && isSpeech && isVoiceFreq) {
                if (!isRecording) {
                    isRecording = true;
                    recordingStartTime = now;
                    speechBuffer.reset();
                    Log.i("Recorder", "▶ 녹음 시작");
                }

                try {
                    speechBuffer.write(buffer);
                    lastSpeechTime = now;
                } catch (IOException e) {
                    Log.e("Recorder", "버퍼 쓰기 실패", e);
                }
            } else if (isRecording) {
                long duration = now - recordingStartTime;
                long silenceDuration = now - lastSpeechTime;

                if (silenceDuration > SILENCE_TIMEOUT_MS && duration >= MIN_RECORDING_MS) {
                    saveWavFile(speechBuffer.toByteArray());

//                    finalizeAndSave(preBuffer, speechBuffer, duration, MAX_RECORDING_MS);
                    isRecording = false;
                } else if (silenceDuration > SILENCE_TIMEOUT_MS) {
                    isRecording = false;
                    Log.i("Recorder", "■ 녹음 취소 (짧음)");
                } else if (duration >= MAX_RECORDING_MS) {
                    saveWavFile(speechBuffer.toByteArray());
                    isRecording = false;
                    Log.i("Recorder", "■ 녹음 강제종료 (5초 초과)");
                }
            }
        }

        recorder.stop();
        recorder.release();
    }


//    private void recordLoop() {
//        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
//                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
//
//        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
//                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
//                AudioFormat.ENCODING_PCM_16BIT, bufferSize);
//
//        recorder.startRecording();
//        isRunning = true;
//
//        speechBuffer = new ByteArrayOutputStream();
//        byte[] buffer = new byte[FRAME_SIZE * 2];
//
//// 설정값: 최소/최대 녹음 시간, 무음 지속 시간
//        final long MAX_RECORDING_MS = 5000;       // 최대 5초 녹음
//        final long MIN_RECORDING_MS = 3000;       // 최소 2초 이상일 때만 저장 (2초 + 무음 감지 2초)
//        final long SILENCE_TIMEOUT_MS = 2000;     // 2초 이상 무음이면 말이 끝났다고 판단
//
//// 타이머 초기화
//        long recordingStartTime = 0;              // 녹음 시작 시간 기록용
//        long lastSpeechTime = 0;                  // 마지막으로 말소리 감지된 시점
//        boolean isRecording = false;              // 현재 녹음 중인지 여부
//
//        while (isRunning) {
//            // AudioRecord로 오디오 프레임 읽기 (10ms 단위)
//            int read = recorder.read(buffer, 0, buffer.length);
//            if (read != buffer.length) continue;  // 읽기 실패 또는 불완전한 프레임은 건너뜀
//
//            // 프리버퍼에 항상 쌓기 (10ms 단위 프레임)
//            preBuffer.addChunk(buffer);
//
//            // 현재 프레임의 데시벨 계산
//            double db = Utils.calculateDb(buffer, read);
//
//            // 현재 프레임의 주파수가 사람 목소리 범위에 해당하는지 검사 (ex: 300~3000Hz)
//            boolean isVoiceFreq = FftUtils.isHumanVoice(buffer, SAMPLE_RATE);
//
//            // WebRTC VAD로 사람이 말한 것으로 판단되는지 검사
//            boolean isSpeech = vad.isSpeech(buffer, SAMPLE_RATE);
//
//            long now = System.currentTimeMillis();
//            Log.e("limdb", ""+(int)db + " freq:"+isVoiceFreq +" -speech:"+isSpeech);
//
//            // 위 3가지 조건을 모두 만족하면 말소리로 판단
//            if (db > -30 && isVoiceFreq && isSpeech) {
//                Log.e("limdb", ""+(int)db + " freq:"+isVoiceFreq +" -10:"+isSpeech);
//                // 처음 말소리가 감지되면 녹음 시작
//                if (!isRecording) {
//                    isRecording = true;
//                    recordingStartTime = now;
//                    speechBuffer.reset();                          // 버퍼 초기화
//                    Log.i("SpeechRecorder", "▶ 녹음 시작됨");
//                }
//                Log.e("limdb",""+(int) db);
//                try {
//                    // 현재 프레임을 speechBuffer에 추가 저장
//                    speechBuffer.write(buffer);
//                    lastSpeechTime = now;  // 마지막 말소리 시간 갱신
//                } catch (IOException e) {
//                    Log.e("SpeechRecorder", "버퍼 쓰기 실패", e);
//                }
//            } else if (isRecording) {
//                long duration = now - recordingStartTime; // 녹음 시작으로 부터 2초가 경과했는지
//                long detectiedTime = now - lastSpeechTime;
//                // 📌 조건 1: 무음이 2초 이상 지속되고, 총 녹음 길이가 2초 이상이면 저장
//
//                if ((detectiedTime > SILENCE_TIMEOUT_MS) && duration >= MIN_RECORDING_MS) {
//                    byte[] speech = speechBuffer.toByteArray();
//                    Log.i("limdb", ""+(int)db + " freq:"+isVoiceFreq +" -10:"+isSpeech);
//
//                    // 현재 녹음 구간 길이 (ms 기준)
//                    long recordedMs = duration;
//
//                    // 5초보다 부족한 경우 → preBuffer에서 부족한 시간만큼 앞에 추가
//                    if (recordedMs < MAX_RECORDING_MS) {
//                        long neededMs = MAX_RECORDING_MS - recordedMs;
//
//                        int frameBytes = 160 * 2; // 10ms당 320byte
//                        int neededFrames = (int)(neededMs / 10); // 10ms 단위 프레임 수
//                        int preBytes = neededFrames * frameBytes;
//
//                        byte[] prefix = preBuffer.getLastNBytes(preBytes); // 부족한 앞부분만큼 프리버퍼에서 가져오기
//
//                        ByteArrayOutputStream finalAudio = new ByteArrayOutputStream();
//                        try {
//                            finalAudio.write(prefix);
//                            finalAudio.write(speech);
//                        } catch (IOException e) {
//                            Log.e("finalAudio", "버퍼 쓰기 실패", e);
//                        }
//                        saveWavFile(finalAudio.toByteArray());
//                        Log.i("SpeechRecorder", "■ 녹음 종료됨, duration = " + duration + "ms");
//                    } else {
//                        // 프리버퍼 붙일 필요 없음
//                        saveWavFile(speech);
//                        Log.i("SpeechRecorder", "■ 녹음 종료됨, duration = " + duration + "ms");
//                    }
//
//                isRecording = false;
//                } else if ((detectiedTime > SILENCE_TIMEOUT_MS) && duration < MIN_RECORDING_MS){
//
//
//                    Log.i("limdb", ""+(int)db + " freq:"+isVoiceFreq +" -10:"+isSpeech);
//
//
//                    isRecording = false;
//                    Log.e("SpeechRecorder", "■ 녹음 취소됨, duration = " + duration + "ms");
//                    // 📌 조건 2: 5초를 초과해도 자동으로 저장 및 종료
//                } else if(duration >= MAX_RECORDING_MS) {
//                    saveWavFile(speechBuffer.toByteArray());
////                    runWhisper();
//                    isRecording = false;
//                    Log.e("SpeechRecorder", "■ 녹음 종료됨, duration = " + duration + "ms");
//                }
//            }
//        }
//
//
//        recorder.stop();
//        recorder.release();
//    }


    private void saveWavFile(byte[] pcm) {
        try {
            File dir = new File(getExternalFilesDir(null), "vad_speech");
            if (!dir.exists()) dir.mkdirs();

//            File file = new File(dir, "utterance_" + System.currentTimeMillis() + ".wav");
            String timeStamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            File file = new File(dir, "utterance_" + timeStamp + ".wav");
            byte[] wav = Utils.pcmToWav(pcm, SAMPLE_RATE);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(wav);
            fos.close();

            Log.d("VAD", "Saved: " + file.getAbsolutePath());
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

        long missingMs = maxMs - duration;
        int frameBytes = 160 * 2;
        int neededFrames = (int)(missingMs / 10);
        int preBytes = neededFrames * frameBytes;

        byte[] prefix = preBuffer.getLastNBytes(preBytes);

        try {
            ByteArrayOutputStream finalAudio = new ByteArrayOutputStream();
            finalAudio.write(prefix);
            finalAudio.write(speech);
            saveWavFile(finalAudio.toByteArray());
            Log.i("Recorder", "■ 녹음 완료. 저장됨");
        } catch (IOException e) {
            Log.e("Recorder", "저장 실패", e);
        }
    }

}
