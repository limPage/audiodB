package com.hjict.audiodb;

//        import android.util.Log;
//
//        import org.jtransforms.fft.DoubleFFT_1D;
//public class FftUtils {
//
//    public static boolean isHumanVoice(byte[] pcmBytes, int sampleRate) {
//        int fftSize = 512;
//        double[] audioData = new double[fftSize];
//
//        // PCM to double
//        for (int i = 0; i < fftSize / 2 && i * 2 + 1 < pcmBytes.length; i++) {
//            short s = (short) ((pcmBytes[i * 2 + 1] << 8) | (pcmBytes[i * 2] & 0xFF));
//            audioData[i] = s / 32768.0;
//        }
//        for (int i = pcmBytes.length / 2; i < fftSize; i++) audioData[i] = 0;
//
//        // Hamming window
//        for (int i = 0; i < fftSize; i++) {
//            audioData[i] *= (0.54 - 0.46 * Math.cos(2 * Math.PI * i / (fftSize - 1)));
//        }
//
//        // FFT
//        DoubleFFT_1D fft = new DoubleFFT_1D(fftSize);
//        fft.realForward(audioData);
//
//        double freqRes = sampleRate / (double) fftSize;
//        int low = (int) (300 / freqRes);
//        int high = (int) (3000 / freqRes);
//
//        double voiceEnergy = 0.0, totalEnergy = 0.0;
//        for (int i = 0; i < fftSize / 2; i++) {
//            double re = audioData[2 * i];
//            double im = audioData[2 * i + 1];
//            double mag2 = re * re + im * im;
//            totalEnergy += mag2;
//            if (i >= low && i <= high) voiceEnergy += mag2;
//        }
//
//        double ratio = voiceEnergy / (totalEnergy + 1e-9);
//        double voiceDb = 10 * Math.log10(voiceEnergy + 1e-10);
//
////        Log.d("fft", String.format("ratio=%.3f, voiceDb=%.2f dB", ratio, voiceDb));
//
//        return ratio >= 0.5 && voiceDb >= 8;
//
//// 예시
////         |     상황       | ratio  | voiceDb |       판단      |
////         |  조용한 백색소음|  0.45  |  -6db   |  너무 작음 x     |
////         |  사람이 속삭임  | 0/60   |  12dB   | 조건 완화 시 가능 |
////         |  보통 말하기    | 0.75   |  28 db  | 적격 o           |
////         |  음악 or 라디오 | 0.25   |  35 dB  | 음역대가 다름 x   |
//
//    }
//
//
//}

import android.util.Log;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;
import org.apache.commons.math3.transform.DftNormalization;

public class FftUtils {
    private static final int HUMAN_VOICE_FREQ_MIN = 80; // Hz
    private static final int HUMAN_VOICE_FREQ_MAX = 400; // Hz
    private static final double SPECTRAL_FLATNESS_THRESHOLD = 0.5; // 스펙트럴 플랫니스 임계값
    private static final double VOICE_ENERGY_THRESHOLD = 0.01; // 에너지 임계값

    public static boolean isHumanVoice(byte[] pcmData, int sampleRate) {
        // PCM 데이터를 double 배열로 변환
        int originalLength = pcmData.length / 2;
        // 다음 2의 제곱수로 패딩 (예: 1600 -> 2048)
        int targetLength = nextPowerOfTwo(originalLength);
        double[] audioData = new double[targetLength];
        for (int i = 0; i < originalLength && i * 2 < pcmData.length; i++) {
            short sample = (short) ((pcmData[i * 2] & 0xff) | (pcmData[i * 2 + 1] << 8));
            audioData[i] = sample / 32768.0; // 정규화 (-1.0 ~ 1.0)
        }
        // 나머지 부분은 0으로 패딩
        for (int i = originalLength; i < targetLength; i++) {
            audioData[i] = 0.0;
        }

        // FFT 수행
        FastFourierTransformer transformer = new FastFourierTransformer(DftNormalization.STANDARD);
        Complex[] fftResult = transformer.transform(audioData, TransformType.FORWARD);

        // 주파수와 크기 배열 생성
        int n = audioData.length;
        double[] frequencies = new double[n / 2];
        double[] magnitudes = new double[n / 2];
        for (int i = 0; i < n / 2; i++) {
            frequencies[i] = i * ((double) sampleRate / n);
            magnitudes[i] = fftResult[i].abs() / n; // 정규화
        }

        // 사람 목소리 주파수 범위 내 에너지 계산
        double voiceEnergy = 0.0;
        double totalEnergy = 0.0;
        for (int i = 0; i < frequencies.length; i++) {
            totalEnergy += magnitudes[i];
            if (frequencies[i] >= HUMAN_VOICE_FREQ_MIN && frequencies[i] <= HUMAN_VOICE_FREQ_MAX) {
                voiceEnergy += magnitudes[i];
            }
        }

        // 스펙트럴 플랫니스 계산
        double meanMagnitude = totalEnergy / frequencies.length;
        double geometricMean = 0.0;
        int count = 0;
        for (double mag : magnitudes) {
            if (mag > 0) {
                geometricMean += Math.log(mag);
                count++;
            }
        }
        geometricMean = count > 0 ? Math.exp(geometricMean / count) : 0.0;
        double spectralFlatness = geometricMean / (meanMagnitude + 1e-10);

        // 사람 목소리 판단
        double voiceEnergyRatio = voiceEnergy / (totalEnergy + 1e-10);
        boolean isVoice = voiceEnergyRatio > 0.4 && spectralFlatness < SPECTRAL_FLATNESS_THRESHOLD && voiceEnergy > VOICE_ENERGY_THRESHOLD;
        // 디버깅 로그 추가
//        Log.d("FftUtils", "VoiceEnergyRatio: " + voiceEnergyRatio + ", SpectralFlatness: " + spectralFlatness + ", VoiceEnergy: " + voiceEnergy + ", IsVoice: " + isVoice);
        return isVoice;
    }

    // 다음 2의 제곱수를 계산하는 헬퍼 메서드
    private static int nextPowerOfTwo(int n) {
        if (n <= 0) return 1;
        int power = 1;
        while (power < n) {
            power *= 2;
        }
        return power;
    }
}
