package com.hjict.audiodb;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Environment;
import android.util.Log;

import java.io.*;

public class Utils {
    public static double calculateDb(byte[] buffer, int length) {
        long sum = 0;
        for (int i = 0; i < length; i += 2) {
            short s = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
            sum += s * s;
        }
        double rms = Math.sqrt(sum / (length / 2.0));
        return 20 * Math.log10(rms);
    }

//    public static byte[] recordWithHistory(AudioBuffer history, AudioRecord recorder, int sampleRate, int durationMs) {
//        byte[] past = history.getData();
//        byte[] future = new byte[sampleRate * 2 * durationMs / 1000];
//
//        int offset = 0;
//        while (offset < future.length) {
//            int read = recorder.read(future, offset, future.length - offset);
//            if (read > 0) offset += read;
//        }
//
//        byte[] total = new byte[past.length + future.length];
//        System.arraycopy(past, 0, total, 0, past.length);
//        System.arraycopy(future, 0, total, past.length, future.length);
//        return total;
//    }
//
    public static void saveWavFile(Context context, byte[] audioData, int sampleRate, double db) {

//        File dir = new File(Environment.getExternalStorageDirectory(), "VoiceTriggers");
        File dir = new File(context.getExternalFilesDir(null), "VoiceTriggers");
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                Log.e("Utils", "디렉토리 생성 실패");
                return;
            }
        }
//        File dir = new File(Environment.getExternalStorageDirectory(), "VoiceTriggers");
//        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, "record_" + System.currentTimeMillis() + "_" + (int) db +"dB.wav");

        try (FileOutputStream fos = new FileOutputStream(file)) {
            writeWavHeader(fos, audioData.length, sampleRate, 1, 16);
            fos.write(audioData);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static byte[] recordWithHistory(AudioBuffer history, int sampleRate, int durationMs) {
        int bufferSize = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        AudioRecord tempRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize);

        byte[] future = new byte[sampleRate * 2 * durationMs / 1000];

        tempRecorder.startRecording();

        int offset = 0;
        while (offset < future.length) {
            int read = tempRecorder.read(future, offset, future.length - offset);
            if (read > 0) offset += read;
        }

        tempRecorder.stop();
        tempRecorder.release();

        byte[] past = history.getData();
        byte[] total = new byte[past.length + future.length];
        System.arraycopy(past, 0, total, 0, past.length);
        System.arraycopy(future, 0, total, past.length, future.length);

        return total;
    }




    private static void writeWavHeader(OutputStream out, long totalAudioLen, int sampleRate, int channels, int bitPerSample) throws IOException {
        long byteRate = sampleRate * channels * bitPerSample / 8;
        byte[] header = new byte[44];

        // RIFF/WAVE header
        header[0] = 'R';  header[1] = 'I';  header[2] = 'F';  header[3] = 'F';
        long dataSize = totalAudioLen + 36;
        header[4] = (byte)(dataSize & 0xff);
        header[5] = (byte)((dataSize >> 8) & 0xff);
        header[6] = (byte)((dataSize >> 16) & 0xff);
        header[7] = (byte)((dataSize >> 24) & 0xff);
        header[8] = 'W';  header[9] = 'A';  header[10] = 'V';  header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16;  header[17] = 0;   header[18] = 0;   header[19] = 0;   // Subchunk1Size
        header[20] = 1;   header[21] = 0;   // PCM format
        header[22] = (byte) channels;
        header[23] = 0;
        header[24] = (byte)(sampleRate & 0xff);
        header[25] = (byte)((sampleRate >> 8) & 0xff);
        header[26] = (byte)((sampleRate >> 16) & 0xff);
        header[27] = (byte)((sampleRate >> 24) & 0xff);
        header[28] = (byte)(byteRate & 0xff);
        header[29] = (byte)((byteRate >> 8) & 0xff);
        header[30] = (byte)((byteRate >> 16) & 0xff);
        header[31] = (byte)((byteRate >> 24) & 0xff);
        header[32] = (byte)(channels * bitPerSample / 8);
        header[33] = 0;
        header[34] = (byte)bitPerSample;
        header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte)(totalAudioLen & 0xff);
        header[41] = (byte)((totalAudioLen >> 8) & 0xff);
        header[42] = (byte)((totalAudioLen >> 16) & 0xff);
        header[43] = (byte)((totalAudioLen >> 24) & 0xff);
        out.write(header, 0, 44);
    }
}
