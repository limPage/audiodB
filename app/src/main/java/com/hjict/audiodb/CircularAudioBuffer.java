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
}
