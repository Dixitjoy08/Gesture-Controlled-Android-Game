package com.example.gesturegame;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * GestureDetector
 * ───────────────
 * Uses MediaPipe HandLandmarker to extract 21 hand landmarks (63 floats),
 * then feeds them into a TFLite MLP to classify the gesture.
 *
 * Assets required in  app/src/main/assets/:
 *   • hand_landmarker.task        (MediaPipe model — unchanged)
 *   • gesture_classifier.tflite  (trained by train_gesture_model.py)
 *   • gesture_labels.txt         (one label per line, same order as training)
 */
public class GestureDetector {

    private static final String TAG = "GestureDetector";

    // ── Asset file names ──────────────────────────────────────────
    private static final String TFLITE_MODEL_FILE = "gesture_classifier.tflite";
    private static final String LABELS_FILE        = "gesture_labels.txt";

    // ── TFLite ────────────────────────────────────────────────────
    private Interpreter tfliteInterpreter;
    private List<String> labels = new ArrayList<>();

    // Input  : float[1][63]
    // Output : float[1][NUM_CLASSES]
    private static final int  NUM_LANDMARKS = 21;
    private static final int  NUM_FEATURES  = NUM_LANDMARKS * 3; // 63

    // Confidence threshold — below this we report "Unknown"
    private static final float CONFIDENCE_THRESHOLD = 0.60f;

    // ── MediaPipe HandLandmarker ──────────────────────────────────
    private HandLandmarker handLandmarker;

    // ── Callback interface ────────────────────────────────────────
    public interface GestureListener {
        void onGestureDetected(String gestureName, float[] landmarks);
    }

    private final GestureListener listener;
    private final Context context;

    // ── Constructor ───────────────────────────────────────────────
    public GestureDetector(Context context, GestureListener listener) {
        this.context  = context;
        this.listener = listener;

        setupHandLandmarker();
        setupTFLite();
    }

    // ── MediaPipe setup ───────────────────────────────────────────
    private void setupHandLandmarker() {
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .build();

            HandLandmarker.HandLandmarkerOptions options =
                    HandLandmarker.HandLandmarkerOptions.builder()
                            .setBaseOptions(baseOptions)
                            .setRunningMode(RunningMode.IMAGE)
                            .setNumHands(1)
                            .setMinHandDetectionConfidence(0.5f)
                            .setMinHandPresenceConfidence(0.5f)
                            .setMinTrackingConfidence(0.5f)
                            .build();

            handLandmarker = HandLandmarker.createFromOptions(context, options);
            Log.d(TAG, "HandLandmarker ready.");

        } catch (Exception e) {
            Log.e(TAG, "HandLandmarker init failed: " + e.getMessage());
        }
    }

    // ── TFLite setup ──────────────────────────────────────────────
    private void setupTFLite() {
        try {
            // Load model
            MappedByteBuffer modelBuffer = loadModelFile();
            Interpreter.Options opts = new Interpreter.Options();
            opts.setNumThreads(2);
            tfliteInterpreter = new Interpreter(modelBuffer, opts);
            Log.d(TAG, "TFLite interpreter ready.");

            // Load labels
            loadLabels();
            Log.d(TAG, "Labels loaded: " + labels);

        } catch (Exception e) {
            Log.e(TAG, "TFLite init failed: " + e.getMessage());
        }
    }

    /** Memory-map the .tflite file from assets for zero-copy loading. */
    private MappedByteBuffer loadModelFile() throws IOException {
        AssetFileDescriptor afd = context.getAssets().openFd(TFLITE_MODEL_FILE);
        FileInputStream fis = new FileInputStream(afd.getFileDescriptor());
        FileChannel channel = fis.getChannel();
        return channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.getStartOffset(),
                afd.getDeclaredLength());
    }

    /** Read gesture_labels.txt — one label per line. */
    private void loadLabels() throws IOException {
        BufferedReader br = new BufferedReader(
                new InputStreamReader(context.getAssets().open(LABELS_FILE)));
        String line;
        while ((line = br.readLine()) != null) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) labels.add(trimmed);
        }
        br.close();
    }

    // ── Process camera frame ──────────────────────────────────────
    public void processFrame(ImageProxy imageProxy) {
        if (handLandmarker == null) {
            imageProxy.close();
            return;
        }

        Bitmap bitmap = imageProxyToBitmap(imageProxy);
        imageProxy.close();
        if (bitmap == null) return;

        bitmap = flipBitmap(bitmap);

        MPImage mpImage = new BitmapImageBuilder(bitmap).build();
        HandLandmarkerResult result = handLandmarker.detect(mpImage);
        handleResult(result);
    }

    // ── Handle MediaPipe result ───────────────────────────────────
    private void handleResult(HandLandmarkerResult result) {
        if (result == null || result.landmarks().isEmpty()) {
            if (listener != null) {
                listener.onGestureDetected("None", new float[0]);
            }
            return;
        }

        List<NormalizedLandmark> landmarks = result.landmarks().get(0);

        // Build flat float array [63]
        float[] landmarkArray = new float[NUM_FEATURES];
        for (int i = 0; i < landmarks.size(); i++) {
            NormalizedLandmark lm = landmarks.get(i);
            landmarkArray[i * 3]     = lm.x();
            landmarkArray[i * 3 + 1] = lm.y();
            landmarkArray[i * 3 + 2] = lm.z();
        }

        String gesture = classifyWithTFLite(landmarkArray);

        if (listener != null) {
            listener.onGestureDetected(gesture, landmarkArray);
        }
    }

    // ── TFLite inference ──────────────────────────────────────────
    /**
     * Runs the gesture_classifier.tflite model.
     *
     * Input  shape : [1, 63]  (float32)
     * Output shape : [1, 7]   (softmax probabilities)
     *
     * Returns the label string, or "Unknown" if below confidence threshold.
     */
    private String classifyWithTFLite(float[] landmarkArray) {
        if (tfliteInterpreter == null || labels.isEmpty()) {
            // Fallback: rule-based classifier (kept as safety net)
            return fallbackClassify(landmarkArray);
        }

        // ── Prepare input buffer ──────────────────────────────────
        // ByteBuffer: 1 sample × 63 floats × 4 bytes
        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(1 * NUM_FEATURES * 4);
        inputBuffer.order(ByteOrder.nativeOrder());
        for (float v : landmarkArray) {
            inputBuffer.putFloat(v);
        }
        inputBuffer.rewind();

        // ── Prepare output buffer ─────────────────────────────────
        float[][] outputBuffer = new float[1][labels.size()];

        // ── Run inference ─────────────────────────────────────────
        try {
            tfliteInterpreter.run(inputBuffer, outputBuffer);
        } catch (Exception e) {
            Log.e(TAG, "TFLite inference error: " + e.getMessage());
            return "Unknown";
        }

        // ── Find best class ───────────────────────────────────────
        float[] probs = outputBuffer[0];
        int bestIdx = 0;
        float bestProb = probs[0];
        for (int i = 1; i < probs.length; i++) {
            if (probs[i] > bestProb) {
                bestProb = probs[i];
                bestIdx  = i;
            }
        }

        if (bestProb < CONFIDENCE_THRESHOLD) {
            return "Unknown";
        }

        Log.v(TAG, "Gesture: " + labels.get(bestIdx)
                + "  conf=" + String.format("%.2f", bestProb));

        return labels.get(bestIdx);
    }

    // ── Fallback rule-based classifier ────────────────────────────
    /**
     * Original heuristic classifier — used only if TFLite fails to load.
     * Kept as a safety net so the game still runs during development.
     */
    private String fallbackClassify(float[] lm) {
        if (lm.length < NUM_FEATURES) return "None";

        float wristY    = lm[1];
        float thumbTipY = lm[4  * 3 + 1];
        float indexTipY = lm[8  * 3 + 1];
        float midTipY   = lm[12 * 3 + 1];
        float ringTipY  = lm[16 * 3 + 1];
        float pinkyTipY = lm[20 * 3 + 1];

        float indexMcpY = lm[5  * 3 + 1];
        float midMcpY   = lm[9  * 3 + 1];
        float ringMcpY  = lm[13 * 3 + 1];
        float pinkyMcpY = lm[17 * 3 + 1];

        boolean indexUp  = indexTipY  < indexMcpY  - 0.05f;
        boolean middleUp = midTipY    < midMcpY    - 0.05f;
        boolean ringUp   = ringTipY   < ringMcpY   - 0.05f;
        boolean pinkyUp  = pinkyTipY  < pinkyMcpY  - 0.05f;
        boolean thumbUp  = thumbTipY  < wristY     - 0.1f;

        if (indexUp && middleUp && ringUp && pinkyUp) return "Open Palm";
        if (!indexUp && !middleUp && !ringUp && !pinkyUp) return "Fist";
        if (indexUp && middleUp && !ringUp && !pinkyUp) return "Peace Sign";
        if (indexUp && !middleUp && !ringUp && !pinkyUp) return "Pointing";
        if (thumbUp && !indexUp && !middleUp && !ringUp && !pinkyUp) return "Thumbs Up";
        if (indexUp && middleUp && ringUp && !pinkyUp) return "Three Fingers";
        if (!indexUp && !middleUp && !ringUp && pinkyUp) return "Pinky Up";

        return "Unknown";
    }

    // ── Convert ImageProxy YUV → Bitmap ──────────────────────────
    @OptIn(markerClass = ExperimentalGetImage.class) private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            Image image = imageProxy.getImage();
            if (image == null) return null;

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];
            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            YuvImage yuvImage = new YuvImage(
                    nv21, ImageFormat.NV21,
                    imageProxy.getWidth(),
                    imageProxy.getHeight(), null);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(
                    new Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()),
                    80, out);

            byte[] bytes = out.toByteArray();
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

        } catch (Exception e) {
            Log.e(TAG, "YUV→Bitmap failed: " + e.getMessage());
            return null;
        }
    }

    // ── Flip bitmap horizontally (front camera mirror) ────────────
    private Bitmap flipBitmap(Bitmap src) {
        Matrix matrix = new Matrix();
        matrix.preScale(-1f, 1f);
        return Bitmap.createBitmap(
                src, 0, 0, src.getWidth(), src.getHeight(), matrix, false);
    }

    // ── Cleanup ───────────────────────────────────────────────────
    public void close() {
        if (handLandmarker != null) {
            handLandmarker.close();
        }
        if (tfliteInterpreter != null) {
            tfliteInterpreter.close();
        }
    }
}
