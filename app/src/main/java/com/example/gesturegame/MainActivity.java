package com.example.gesturegame;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    // Permission request code
    private static final int CAMERA_PERMISSION_CODE = 100;

    private Button btnStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStart = findViewById(R.id.btnStart);

        btnStart.setOnClickListener(v -> {
            // Check camera permission before starting game
            if (isCameraPermissionGranted()) {
                startGame();
            } else {
                requestCameraPermission();
            }
        });
    }

    // ── Check if camera permission is already granted ──
    private boolean isCameraPermissionGranted() {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED;
    }

    // ── Ask user for camera permission ──
    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.CAMERA},
                CAMERA_PERMISSION_CODE
        );
    }

    // ── Handle permission result ──
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted → start game
                startGame();
            } else {
                // Permission denied → show message
                Toast.makeText(this,
                        "Camera permission is required to play!",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ── Navigate to Game Screen ──
    private void startGame() {
        Intent intent = new Intent(this, GameActivity.class);
        startActivity(intent);
    }
}