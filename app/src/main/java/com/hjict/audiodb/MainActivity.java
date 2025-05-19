package com.hjict.audiodb;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d("MainActivity", "onCreate");
//        startService(new Intent(this, SoundDetectionService.class));
        startForegroundService(new Intent(this, SoundDetectionService.class));

        finish();  // UI는 없음 (서비스만 실행)
    }
}
