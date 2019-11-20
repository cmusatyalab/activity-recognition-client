package com.example.cameraxapp;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraX;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageAnalysisConfig;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureConfig;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.PreviewConfig;
import androidx.camera.core.VideoCapture;
import androidx.camera.core.VideoCaptureConfig;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Rational;
import android.util.Size;
import android.graphics.Matrix;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import android.os.Bundle;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

@SuppressLint("RestrictedApi")
public class MainActivity extends AppCompatActivity {

    private final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String TAG = "CameraXApp";

    private String[] REQUIRED_PERMISSIONS = new String[] {
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.view_finder);
        // Request camera permissions
        if (allPermissionsGranted()) {
            viewFinder.post(startCamera);
        } else {
            ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        // Every time the provided texture view changes, recompute layout
        viewFinder.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(
                    View v, int left, int top, int right, int bottom, int oldLeft, int oldTop,
                    int oldRight, int oldBottom) {
                updateTransform();
            }
        });
    }

    private TextureView viewFinder;
    private Executor executor = Executors.newSingleThreadExecutor();

    private Runnable startCamera = new Runnable() {
        @Override
        public void run() {
            // Create configuration object for the viewfinder use case
            PreviewConfig previewConfig = new PreviewConfig.Builder()
                    .setTargetResolution(new Size(640, 480))
                    .build();

            // Build the viewfinder use case
            Preview preview = new Preview(previewConfig);

            // Every time the viewfinder is updated, recompute layout
            preview.setOnPreviewOutputUpdateListener(
                previewOutput -> {
                    // To update the SurfaceTexture, we have to remove it and re-add it
                    ViewGroup parent = (ViewGroup) viewFinder.getParent();
                    parent.removeView(viewFinder);
                    parent.addView(viewFinder, 0);

                    viewFinder.setSurfaceTexture(previewOutput.getSurfaceTexture());
                    updateTransform();
                });

            VideoCaptureConfig videoCaptureConfig = new VideoCaptureConfig.Builder().build();
            VideoCapture videoCapture = new VideoCapture(videoCaptureConfig);

            findViewById(R.id.capture_button).setOnClickListener(view -> {
                File file = new File(getExternalMediaDirs()[0], "video.mp4");
                videoCapture.startRecording(file, executor, new VideoCapture.OnVideoSavedListener() {
                    @Override
                    public void onVideoSaved(File file) {
                        try {
                            byte[] fileContent = Files.readAllBytes(file.toPath());
                        } catch (java.io.IOException e) {
                            Log.e(TAG, "Exception reading video file", e);
                        }

                    }

                    @Override
                    public void onError(VideoCapture.VideoCaptureError videoCaptureError,
                                        String message, @Nullable Throwable cause) {
                        String msg = "Video capture failed: " + message;
                        Log.e(TAG, msg, cause);
                    }
                });

            });

            // Bind use cases to lifecycle
            CameraX.bindToLifecycle((LifecycleOwner) MainActivity.this, preview, videoCapture);
        }
    };

    private void updateTransform() {
        Matrix matrix = new Matrix();

        float centerX = viewFinder.getWidth() / 2f;
        float centerY = viewFinder.getHeight() / 2f;

        // Correct preview output to account for display rotation
        float rotationDegrees;
        switch (viewFinder.getDisplay().getRotation()) {
            case Surface.ROTATION_0:
                rotationDegrees = 0f;
                break;
            case Surface.ROTATION_90:
                rotationDegrees = 90f;
                break;
            case Surface.ROTATION_180:
                rotationDegrees = 180f;
                break;
            case Surface.ROTATION_270:
                rotationDegrees = 270f;
                break;
            default:
                return;
        }

        matrix.postRotate(-rotationDegrees, centerX, centerY);

        // Finally, apply transformations to our TextureView
        viewFinder.setTransform(matrix);
    }

    /**
     * Process result from permission request dialog box, has the request
     * been granted? If yes, start Camera. Otherwise display a toast
     */
    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                viewFinder.post(startCamera);
            } else {
                Toast.makeText(this,
                        "Permissions not granted by the user.",
                        Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(getBaseContext(), permission) !=
                    PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
