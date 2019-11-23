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
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.graphics.Matrix;

import java.io.File;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import android.os.Bundle;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.protobuf.ByteString;

import edu.cmu.cs.gabriel.client.comm.ServerComm;
import edu.cmu.cs.gabriel.client.comm.ServerCommCore;
import edu.cmu.cs.gabriel.client.function.Consumer;

import edu.cmu.cs.gabriel.protocol.Protos.ResultWrapper;
import edu.cmu.cs.gabriel.protocol.Protos.ResultWrapper.Result;
import edu.cmu.cs.gabriel.protocol.Protos.FromClient;
import edu.cmu.cs.gabriel.protocol.Protos.PayloadType;

@SuppressLint("RestrictedApi")
public class MainActivity extends AppCompatActivity {

    private final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String TAG = "MainActivity";
    private static final String ENGINE_NAME = "activity_recognition";
    private static final String SERVER_IP = "deluge.elijah.cs.cmu.edu";
    // private static final String SERVER_IP = "gs17934.sp.cs.cmu.edu";

    private String[] REQUIRED_PERMISSIONS = new String[] {
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
    };

    private TextureView viewFinder;
    private Executor executor = Executors.newSingleThreadExecutor();
    private ServerCommCore serverCommCore;
    private TextView output;
    private TextToSpeech textToSpeech;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        output = findViewById(R.id.text_output);

        // Based on https://javapapers.com/android/android-text-to-speech-tutorial/
        textToSpeech = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech.setLanguage(Locale.US);
                }
            }
        });

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

        Consumer<ResultWrapper> consumer = new Consumer<ResultWrapper>() {
            @Override
            public void accept(ResultWrapper resultWrapper) {
                runOnUiThread(() -> output.setText(""));

                for (int i = 0; i < resultWrapper.getResultsCount(); i++) {
                    Result result = resultWrapper.getResults(i);
                    if (result.getPayloadType() == PayloadType.TEXT) {
                        textToSpeech.speak(
                                result.getPayload().toStringUtf8(), TextToSpeech.QUEUE_FLUSH,
                                null, null);
                    } else if (result.getPayloadType() == PayloadType.IMAGE) {
                        runOnUiThread(() -> {
                            // Based on https://stackoverflow.com/a/24946375/859277
                            Dialog builder = new Dialog(MainActivity.this);
                            builder.requestWindowFeature(Window.FEATURE_NO_TITLE);
                            builder.getWindow().setBackgroundDrawable(
                                    new ColorDrawable(android.graphics.Color.TRANSPARENT));
                            builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                                @Override
                                public void onDismiss(DialogInterface dialogInterface) {
                                    //nothing;
                                }
                            });

                            ImageView imageView = new ImageView(MainActivity.this);
                            ByteString dataString = result.getPayload();

                            Bitmap bitmapOriginalSize = BitmapFactory.decodeByteArray(
                                    dataString.toByteArray(), 0, dataString.size());
                            Bitmap bitmap = Bitmap.createScaledBitmap(bitmapOriginalSize, 1440, 1080, false);
                            imageView.setImageBitmap(bitmap);

                            builder.addContentView(imageView, new RelativeLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT));
                            builder.show();
                        });
                    }
                }
            }
        };

        Runnable onDisconnect = new Runnable() {
            @Override
            public void run() {
                Log.e(TAG, getString(R.string.disconnected));
                finishAndRemoveTask();
            }
        };

        serverCommCore = new ServerComm(consumer, onDisconnect, SERVER_IP,
                9099, getApplication());
    }

    private Runnable startCamera = new Runnable() {
        @Override
        public void run() {
            // Create configuration object for the viewfinder use case
            PreviewConfig previewConfig = new PreviewConfig.Builder().build();

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

            VideoCaptureConfig videoCaptureConfig = new VideoCaptureConfig.Builder()
                    .setTargetResolution(new Size(640, 480)).build();
            VideoCapture videoCapture = new VideoCapture(videoCaptureConfig);

            findViewById(R.id.capture_button).setOnClickListener(view -> {
                runOnUiThread(() -> output.setText(getString(R.string.recording)));

                File file = new File(getExternalMediaDirs()[0], "video.mp4");
                videoCapture.startRecording(
                        file, executor, new VideoCapture.OnVideoSavedListener() {
                    @Override
                    public void onVideoSaved(File file) {
                        try {
                            runOnUiThread(() -> output.setText(getString(R.string.uploading)));

                            byte[] fileContent = Files.readAllBytes(file.toPath());

                            FromClient.Builder fromClientBuilder = FromClient.newBuilder();
                            fromClientBuilder.setPayloadType(PayloadType.VIDEO);
                            fromClientBuilder.setEngineName(ENGINE_NAME);
                            fromClientBuilder.setPayload(ByteString.copyFrom(fileContent));

                            serverCommCore.sendBlocking(fromClientBuilder);

                            runOnUiThread(() -> output.setText(getString(R.string.waiting)));
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
        matrix.preScale((3f/4f), (4f/3f), centerX, centerY);

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
