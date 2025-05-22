package com.hjict.audiodb;

import org.jtransforms.fft.DoubleFFT_1D;
public class FftUtils {

    // PCM 데이터 → 사람이 말하는 주파수 영역 에너지 검사 (300~3000Hz)
    public static boolean isHumanVoice(byte[] pcmBytes, int sampleRate) {
        int fftSize = 512; // 최소 256 이상 권장
        double[] audioData = new double[fftSize];

        // byte[] → double[]
        for (int i = 0; i < fftSize / 2 && i * 2 + 1 < pcmBytes.length; i++) {
            short sample = (short) ((pcmBytes[i * 2 + 1] << 8) | (pcmBytes[i * 2] & 0xFF));
            audioData[i] = sample / 32768.0;
        }

        // zero-padding
        for (int i = pcmBytes.length / 2; i < fftSize; i++) {
            audioData[i] = 0;
        }

        // 실수 FFT 실행
        DoubleFFT_1D fft = new DoubleFFT_1D(fftSize);
        fft.realForward(audioData); // in-place

        // 주파수 bin 단위 계산
        double freqResolution = sampleRate / (double) fftSize;

        // 주파수 구간 선택 (300~3000Hz)
        int lowIndex = (int) (300 / freqResolution);
        int highIndex = (int) (3000 / freqResolution);

        double energy = 0.0;
        for (int i = lowIndex; i <= highIndex; i++) {
            double real = audioData[2 * i];
            double imag = audioData[2 * i + 1];
            energy += real * real + imag * imag;
        }

        // 에너지 기준값 설정 (조절 가능)
        return energy > 5.0; // 실험적으로 threshold 조정 필요
    }
}
