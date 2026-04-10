package com.example.gesturegame;

import android.os.Bundle;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GameActivity extends AppCompatActivity {

    // UI
    private PreviewView cameraPreview;
    private TextView tvGestureLabel;
    private TextView tvScore;
    private TextView tvHealth;
    private Button btnPause;

    // Camera
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;

    // Game
    private GameView gameView;

    // Gesture
    private GestureDetector gestureDetector;

    // State
    private boolean isPaused = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game);

        // Link UI
        cameraPreview  = findViewById(R.id.cameraPreview);
        tvGestureLabel = findViewById(R.id.tvGestureLabel);
        tvScore        = findViewById(R.id.tvScore);
        tvHealth       = findViewById(R.id.tvHealth);
        btnPause       = findViewById(R.id.btnPause);

        // Add GameView to container
        gameView = new GameView(this);
        FrameLayout gameContainer = findViewById(R.id.gameContainer);
        gameContainer.addView(gameView);

        // Pause button
        btnPause.setOnClickListener(v -> togglePause());

        // Init gesture detector
        gestureDetector = new GestureDetector(this, (gesture, landmarks) -> {
            updateGestureLabel(gesture);
            if (gameView != null && !isPaused) {
                gameView.onGestureReceived(gesture);
            }
        });

        // Start camera
        cameraExecutor = Executors.newSingleThreadExecutor();
        startCamera();
    }

    // ── Camera setup ─────────────────────────────────────────────
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        // Preview
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

        // Image Analysis — feed frames to gesture detector
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, imageProxy ->
                gestureDetector.processFrame(imageProxy));

        // Front camera
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalysis);
    }

    // ── UI update methods (called from gesture detector thread) ──
    public void updateGestureLabel(String gesture) {
        runOnUiThread(() ->
                tvGestureLabel.setText("Gesture: " + gesture));
    }

    public void updateScore(int score) {
        runOnUiThread(() ->
                tvScore.setText("Score: " + score));
    }

    public void updateHealth(int health) {
        runOnUiThread(() -> {
            StringBuilder hearts = new StringBuilder();
            for (int i = 0; i < health; i++) hearts.append("❤️ ");
            tvHealth.setText(hearts.toString().trim());
        });
    }

    // ── Pause / Resume ───────────────────────────────────────────
    private void togglePause() {
        isPaused = !isPaused;
        if (isPaused) {
            gameView.pause();
            btnPause.setText("RESUME");
        } else {
            gameView.resume();
            btnPause.setText("PAUSE");
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────
    @Override
    protected void onPause() {
        super.onPause();
        if (gameView != null) gameView.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gameView != null && !isPaused) gameView.resume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (gestureDetector != null) gestureDetector.close();
    }
}