package com.hjict.audiodb;

        import android.util.Log;

        import org.jtransforms.fft.DoubleFFT_1D;
public class FftUtils {

    public static boolean isHumanVoice(byte[] pcmBytes, int sampleRate) {
        int fftSize = 512;
        double[] audioData = new double[fftSize];

        // PCM to double
        for (int i = 0; i < fftSize / 2 && i * 2 + 1 < pcmBytes.length; i++) {
            short s = (short) ((pcmBytes[i * 2 + 1] << 8) | (pcmBytes[i * 2] & 0xFF));
            audioData[i] = s / 32768.0;
        }
        for (int i = pcmBytes.length / 2; i < fftSize; i++) audioData[i] = 0;

        // Hamming window
        for (int i = 0; i < fftSize; i++) {
            audioData[i] *= (0.54 - 0.46 * Math.cos(2 * Math.PI * i / (fftSize - 1)));
        }

        // FFT
        DoubleFFT_1D fft = new DoubleFFT_1D(fftSize);
        fft.realForward(audioData);

        double freqRes = sampleRate / (double) fftSize;
        int low = (int) (300 / freqRes);
        int high = (int) (3000 / freqRes);

        double voiceEnergy = 0.0, totalEnergy = 0.0;
        for (int i = 0; i < fftSize / 2; i++) {
            double re = audioData[2 * i];
            double im = audioData[2 * i + 1];
            double mag2 = re * re + im * im;
            totalEnergy += mag2;
            if (i >= low && i <= high) voiceEnergy += mag2;
        }

        double ratio = voiceEnergy / (totalEnergy + 1e-9);
        double voiceDb = 10 * Math.log10(voiceEnergy + 1e-10);

//        Log.d("fft", String.format("ratio=%.3f, voiceDb=%.2f dB", ratio, voiceDb));

        return ratio >= 0.6 && voiceDb >= 12;

// 예시
//         |     상황       | ratio  | voiceDb |       판단      |
//         |  조용한 백색소음|  0.45  |  -6db   |  너무 작음 x     |
//         |  사람이 속삭임  | 0/60   |  12dB   | 조건 완화 시 가능 |
//         |  보통 말하기    | 0.75   |  28 db  | 적격 o           |
//         |  음악 or 라디오 | 0.25   |  35 dB  | 음역대가 다름 x   |

    }


}
