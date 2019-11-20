package com.example.cameraxapp;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraX;
import androidx.camera.core.Preview;
import androidx.camera.core.PreviewConfig;
import androidx.camera.core.VideoCapture;
import androidx.camera.core.VideoCaptureConfig;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.util.Log;
import android.util.Size;
import android.graphics.Matrix;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import android.os.Bundle;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.Toast;

import com.google.protobuf.ByteString;

import edu.cmu.cs.gabriel.client.comm.ServerComm;
import edu.cmu.cs.gabriel.client.comm.ServerCommCore;
import edu.cmu.cs.gabriel.client.function.Consumer;

import edu.cmu.cs.gabriel.protocol.Protos.ResultWrapper;
import edu.cmu.cs.gabriel.protocol.Protos.FromClient;
import edu.cmu.cs.gabriel.protocol.Protos.PayloadType;

@SuppressLint("RestrictedApi")
public class MainActivity extends AppCompatActivity {

    private final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String TAG = "CameraXApp";
    private static final String ENGINE_NAME = "activity_recognition";
    // private static final String SERVER_IP = "deluge.elijah.cs.cmu.edu";
    private static final String SERVER_IP = "gs17934.sp.cs.cmu.edu";

    private String[] REQUIRED_PERMISSIONS = new String[] {
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
    };

    private TextureView viewFinder;
    private Executor executor = Executors.newSingleThreadExecutor();
    private ServerCommCore serverCommCore;

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

        Consumer<ResultWrapper> consumer = new Consumer<ResultWrapper>() {
            @Override
            public void accept(ResultWrapper resultWrapper) {

            }
        };

        Runnable onDisconnect = new Runnable() {
            @Override
            public void run() {

            }
        };

        serverCommCore = new ServerComm(consumer, onDisconnect, SERVER_IP,
                9099, getApplication());
    }

    private Runnable startCamera = new Runnable() {
        @Override
        public void run() {
            // Create configuration object for the viewfinder use case
            PreviewConfig previewConfig = new PreviewConfig.Builder().setTargetResolution(
                    new Size(640, 480)).build();

            // Build the viewfinder use case
            Preview preview = new Preview(previewConfig);

            Matrix matrix = new Matrix();

            // Every time the viewfinder is updated, recompute layout
            preview.setOnPreviewOutputUpdateListener(
                previewOutput -> {
                    // To update the SurfaceTexture, we have to remove it and re-add it
                    ViewGroup parent = (ViewGroup) viewFinder.getParent();
                    parent.removeView(viewFinder);
                    parent.addView(viewFinder, 0);

                    viewFinder.setSurfaceTexture(previewOutput.getSurfaceTexture());
                });

            VideoCaptureConfig videoCaptureConfig = new VideoCaptureConfig.Builder()
                    .setTargetResolution(new Size(640, 480)).build();
            VideoCapture videoCapture = new VideoCapture(videoCaptureConfig);

            findViewById(R.id.capture_button).setOnClickListener(view -> {
                File file = new File(getExternalMediaDirs()[0], "video.mp4");
                videoCapture.startRecording(file, executor, new VideoCapture.OnVideoSavedListener() {
                    @Override
                    public void onVideoSaved(File file) {
                        try {
                            byte[] fileContent = Files.readAllBytes(file.toPath());

                            FromClient.Builder fromClientBuilder = FromClient.newBuilder();
                            fromClientBuilder.setPayloadType(PayloadType.VIDEO);
                            fromClientBuilder.setEngineName(ENGINE_NAME);
                            fromClientBuilder.setPayload(ByteString.copyFrom(fileContent));

                            serverCommCore.sendBlocking(fromClientBuilder);
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
                viewFinder.postDelayed(videoCapture::stopRecording, 2000);
            });

            // Bind use cases to lifecycle
            CameraX.bindToLifecycle(MainActivity.this, preview, videoCapture);
        }
    };

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
