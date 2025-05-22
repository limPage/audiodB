package com.hjict.audiodb;

public class VadWrapper {
    static {
        System.loadLibrary("audiodb");
    }

    public native void init(int mode); // 0~3
    public native boolean isSpeech(byte[] pcm, int sampleRate);
}
