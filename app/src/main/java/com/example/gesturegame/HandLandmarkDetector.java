package com.example.gesturegame;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.util.Log;

import androidx.camera.core.ImageProxy;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmark;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;

public class HandLandmarkDetector {

    private static final String TAG = "HandLandmarkDetector";
    private static final String MODEL_FILE = "hand_landmarker.task";

    private HandLandmarker handLandmarker;
    private final Context context;
    private LandmarkListener listener;

    // ── Callback interface ─────────────────────────────────
    public interface LandmarkListener {
        void onLandmarksDetected(float[] landmarks);
        void onNoHandDetected();
    }

    // ── Constructor ────────────────────────────────────────
    public HandLandmarkDetector(Context context, LandmarkListener listener) {
        this.context  = context;
        this.listener = listener;
        initHandLandmarker();
    }

    // ── Initialize MediaPipe Hand Landmarker ───────────────
    private void initHandLandmarker() {
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_FILE)
                    .build();

            HandLandmarker.HandLandmarkerOptions options =
                    HandLandmarker.HandLandmarkerOptions.builder()
                            .setBaseOptions(baseOptions)
                            .setNumHands(1)           // detect 1 hand only
                            .setMinHandDetectionConfidence(0.5f)
                            .setMinHandPresenceConfidence(0.5f)
                            .setMinTrackingConfidence(0.5f)
                            .setRunningMode(RunningMode.IMAGE)
                            .build();

            handLandmarker = HandLandmarker.createFromOptions(context, options);
            Log.d(TAG, "HandLandmarker initialized successfully");

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize HandLandmarker: " + e.getMessage());
        }
    }

    // ── Process each camera frame ──────────────────────────
    public void detectLandmarks(ImageProxy imageProxy) {
        if (handLandmarker == null) {
            imageProxy.close();
            return;
        }

        try {
            // Step 1: Convert ImageProxy → Bitmap
            Bitmap bitmap = imageProxyToBitmap(imageProxy);
            if (bitmap == null) {
                imageProxy.close();
                return;
            }

            // Step 2: Flip horizontally (front camera mirrors)
            bitmap = flipBitmap(bitmap);

            // Step 3: Wrap in MPImage for MediaPipe
            MPImage mpImage = new BitmapImageBuilder(bitmap).build();

            // Step 4: Run detection
            HandLandmarkerResult result = handLandmarker.detect(mpImage);

            // Step 5: Extract landmarks
            if (result != null
                    && result.landmarks() != null
                    && !result.landmarks().isEmpty()) {

                // Get first hand's landmarks
                List<com.google.mediapipe.tasks.components.containers
                        .NormalizedLandmark> handLandmarks =
                        result.landmarks().get(0);

                // Convert to float array [x0,y0,z0, x1,y1,z1, ... x20,y20,z20]
                float[] landmarkArray = new float[63];
                for (int i = 0; i < handLandmarks.size(); i++) {
                    landmarkArray[i * 3]     = handLandmarks.get(i).x();
                    landmarkArray[i * 3 + 1] = handLandmarks.get(i).y();
                    landmarkArray[i * 3 + 2] = handLandmarks.get(i).z();
                }

                // Send to listener (GestureDetector)
                listener.onLandmarksDetected(landmarkArray);

            } else {
                listener.onNoHandDetected();
            }

        } catch (Exception e) {
            Log.e(TAG, "Detection error: " + e.getMessage());
        } finally {
            imageProxy.close();
        }
    }

    // ── Convert ImageProxy to Bitmap ───────────────────────
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();

            // Get Y, U, V planes
            ByteBuffer yBuffer  = planes[0].getBuffer();
            ByteBuffer uBuffer  = planes[1].getBuffer();
            ByteBuffer vBuffer  = planes[2].getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];
            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            // Convert NV21 → JPEG → Bitmap
            YuvImage yuvImage = new YuvImage(
                    nv21,
                    ImageFormat.NV21,
                    imageProxy.getWidth(),
                    imageProxy.getHeight(),
                    null
            );

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(
                    new Rect(0, 0,
                            imageProxy.getWidth(),
                            imageProxy.getHeight()),
                    90, out
            );

            byte[] jpegBytes = out.toByteArray();
            return BitmapFactory.decodeByteArray(
                    jpegBytes, 0, jpegBytes.length);

        } catch (Exception e) {
            Log.e(TAG, "Bitmap conversion failed: " + e.getMessage());
            return null;
        }
    }

    // ── Flip bitmap horizontally (mirror front camera) ─────
    private Bitmap flipBitmap(Bitmap bitmap) {
        Matrix matrix = new Matrix();
        matrix.preScale(-1f, 1f);
        return Bitmap.createBitmap(
                bitmap, 0, 0,
                bitmap.getWidth(),
                bitmap.getHeight(),
                matrix, false
        );
    }

    // ── Release resources ──────────────────────────────────
    public void close() {
        if (handLandmarker != null) {
            handLandmarker.close();
        }
    }
}