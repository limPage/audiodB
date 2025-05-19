package com.hjict.audiodb;

public class AudioBuffer {
    private final byte[] buffer;
    private int index = 0;

    public AudioBuffer(int size) {
        buffer = new byte[size];
    }

    public synchronized void append(byte[] data, int length) {
        int remain = buffer.length - index;
        if (length > remain) {
            System.arraycopy(buffer, length, buffer, 0, buffer.length - length);
            index = buffer.length - length;
        }
        System.arraycopy(data, 0, buffer, index, length);
        index = Math.min(index + length, buffer.length);
    }

    public synchronized byte[] getData() {
        byte[] copy = new byte[index];
        System.arraycopy(buffer, 0, copy, 0, index);
        return copy;
    }
}
