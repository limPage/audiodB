package com.hjict.audiodb;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

// 2025-05-26 체크 -> 녹음을 녹음 후 체크 변경했을 때 잘 인식되는지 테스트
public class WavOfflineAnalyzer {

    public boolean hasHumanVoice(File wavFile) {
        try {
            byte[] pcm = Utils.readWavToPCM(wavFile);  // WAV → PCM
            int frameSize = 160 * 2;  // 10ms
            int totalFrames = pcm.length / frameSize;

            VadWrapper vad = new VadWrapper();
            vad.init(3);  // 민감도

            int voiceFrames = 0;
            for (int i = 0; i < totalFrames; i++) {
                byte[] frame = Arrays.copyOfRange(pcm, i * frameSize, (i + 1) * frameSize);
                boolean isSpeech = vad.isSpeech(frame, 16000);
                boolean isVoice = FftUtils.isHumanVoice(frame, 16000);
                double db = Utils.calculateDb(frame, frame.length);
//                Log.e("OfflineVAD","isSpeech:"+isSpeech+", isVoice:"+ isVoice+", dB"+(int)db);

                if (isSpeech && isVoice && db > -35) {
                    voiceFrames++;
                }
            }

            float ratio = voiceFrames / (float) totalFrames;
            return voiceFrames >= 20 || ratio > 0.2;

        } catch (Exception e) {
            Log.e("OfflineVAD", "분석 오류", e);
            return false;
        }
    }
}

