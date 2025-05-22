package com.hjict.audiodb;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.Manifest;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    SpeechSegmentRecorderWithPreBuffer recorder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d("MainActivity", "onCreate");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1000);
            }
        }
//        startService(new Intent(this, SoundDetectionService.class));
//        startForegroundService(new Intent(this, SoundDetectionService.class));

//        finish();  // UI는 없음 (서비스만 실행)

//        recorder = new SpeechSegmentRecorderWithPreBuffer(this); //이 방식은 mainActivity가 닫히면 서비스가 종료됨
//        recorder.start();

// MainActivity 또는 BroadcastReceiver에서
        startForegroundService(new Intent(this, SpeechRecorderService.class));


    }
    @Override
    protected void onStart() {
        super.onStart();
        Log.d("MainActivity", "onStart");


    }

    @Override
    protected void onStop() {
        super.onStop();
        recorder.stop();
    }
}

