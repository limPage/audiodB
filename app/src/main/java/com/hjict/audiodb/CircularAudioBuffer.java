package com.hjict.audiodb;

import java.io.ByteArrayOutputStream;

public class CircularAudioBuffer {
    private final byte[][] chunks;
    private final int chunkSize;
    private int index = 0;
    private final int capacity;

    public CircularAudioBuffer(int numChunks, int chunkSize) {
        this.capacity = numChunks;
        this.chunkSize = chunkSize;
        this.chunks = new byte[numChunks][chunkSize];
    }

    public void addChunk(byte[] data) {
        int i = index % capacity;
        System.arraycopy(data, 0, chunks[i], 0, chunkSize);
        index++;
    }

    public byte[] getBufferedData() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < capacity; i++) {
            int pos = (index + i) % capacity;
            out.write(chunks[pos], 0, chunkSize);
        }
        return out.toByteArray();
    }
    public byte[] getLastNBytes(int n) {
        int totalBytes = capacity * chunkSize;
        if (n > totalBytes) n = totalBytes;

        ByteArrayOutputStream out = new ByteArrayOutputStream(n);
        int numChunksToRead = (int) Math.ceil(n / (double) chunkSize);
        int startIdx = index - numChunksToRead;
        if (startIdx < 0) startIdx += capacity;

        for (int i = 0; i < numChunksToRead; i++) {
            int pos = (startIdx + i) % capacity;
            out.write(chunks[pos], 0, chunkSize);
        }

        byte[] result = out.toByteArray();
        if (result.length > n) {
            // 잘라내기 (가장 최근 N바이트)
            byte[] trimmed = new byte[n];
            System.arraycopy(result, result.length - n, trimmed, 0, n);
            return trimmed;
        }

        return result;
    }

    public void reset() {
        index = 0;
    }

}
