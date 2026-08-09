package com.example.detectcamera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Size;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements WebServer.FrameProvider {

    private static final int CAMERA_PERMISSION_CODE = 101;

    private PreviewView previewView;
    private SwitchMaterial switchMode;
    private TextView tvDetectionStatus, tvServerIp;
    private TextInputEditText etPort, etUser, etPass;
    private Button btnToggleServer;

    private ExecutorService cameraExecutor;
    private FaceDetector faceDetector;
    private PoseDetector poseDetector;

    private WebServer webServer;
    private boolean isServerRunning = false;
    private boolean isAutoMode = true;
    private boolean isDetected = false;

    private volatile byte[] currentFrameBytes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        switchMode = findViewById(R.id.switchMode);
        tvDetectionStatus = findViewById(R.id.tvDetectionStatus);
        tvServerIp = findViewById(R.id.tvServerIp);
        etPort = findViewById(R.id.etPort);
        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        btnToggleServer = findViewById(R.id.btnToggleServer);

        cameraExecutor = Executors.newSingleThreadExecutor();

        FaceDetectorOptions faceOptions = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build();
        faceDetector = FaceDetection.getClient(faceOptions);

        PoseDetectorOptions poseOptions = new PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build();
        poseDetector = PoseDetection.getClient(poseOptions);

        switchMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isAutoMode = isChecked;
            if (isAutoMode) {
                switchMode.setText("Modo: Automático (Transmitir al detectar)");
            } else {
                switchMode.setText("Modo: Manual (Transmitir siempre)");
            }
        });

        btnToggleServer.setOnClickListener(v -> toggleServer());

        if (checkCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }

        updateIpDisplay();
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Permiso de cámara requerido", Toast.LENGTH_SHORT).show();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @ExperimentalGetImage
    private void processImageProxy(ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        byte[] jpeg = imageProxyToJpeg(imageProxy);
        if (jpeg != null) {
            currentFrameBytes = jpeg;
        }

        InputImage inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());

        faceDetector.process(inputImage)
                .addOnSuccessListener(faces -> {
                    boolean faceFound = !faces.isEmpty();
                    if (faceFound) {
                        runOnUiThread(() -> {
                            isDetected = true;
                            tvDetectionStatus.setText("Estado: ¡Rostro Detectado!");
                        });
                        imageProxy.close();
                    } else {
                        poseDetector.process(inputImage)
                                .addOnSuccessListener(pose -> {
                                    boolean poseFound = !pose.getAllPoseLandmarks().isEmpty();
                                    runOnUiThread(() -> {
                                        isDetected = poseFound;
                                        if (poseFound) {
                                            tvDetectionStatus.setText("Estado: ¡Cuerpo/Silueta Detectada!");
                                        } else {
                                            tvDetectionStatus.setText("Estado: Sin detección activa");
                                        }
                                    });
                                    imageProxy.close();
                                })
                                .addOnFailureListener(e -> imageProxy.close());
                    }
                })
                .addOnFailureListener(e -> imageProxy.close());
    }

    private byte[] imageProxyToJpeg(ImageProxy imageProxy) {
        ImageProxy.PlaneProxy yPlane = imageProxy.getPlanes()[0];
        ImageProxy.PlaneProxy uPlane = imageProxy.getPlanes()[1];
        ImageProxy.PlaneProxy vPlane = imageProxy.getPlanes()[2];

        ByteBuffer yBuffer = yPlane.getBuffer();
        ByteBuffer uBuffer = uPlane.getBuffer();
        ByteBuffer vBuffer = vPlane.getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, imageProxy.getWidth(), imageProxy.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 60, out);
        return out.toByteArray();
    }

    private void toggleServer() {
        if (isServerRunning) {
            stopServer();
        } else {
            startServer();
        }
    }

    private void startServer() {
        String portStr = etPort.getText() != null ? etPort.getText().toString().trim() : "8080";
        String user = etUser.getText() != null ? etUser.getText().toString().trim() : "admin";
        String pass = etPass.getText() != null ? etPass.getText().toString().trim() : "1234";

        int port = 8080;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException ignored) {}

        try {
            webServer = new WebServer(port, user, pass, this);
            webServer.start();
            isServerRunning = true;
            btnToggleServer.setText("Detener Servidor");
            updateIpDisplay();
            Toast.makeText(this, "Servidor iniciado en puerto " + port, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Error al iniciar servidor: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopServer() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        isServerRunning = false;
        btnToggleServer.setText("Iniciar Servidor Web");
        tvServerIp.setText("IP: Servidor Detenido");
        Toast.makeText(this, "Servidor detenido", Toast.LENGTH_SHORT).show();
    }

    private void updateIpDisplay() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifiManager != null) {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            int ipAddress = wifiInfo.getIpAddress();
            String ip = Formatter.formatIpAddress(ipAddress);
            String port = etPort.getText() != null ? etPort.getText().toString().trim() : "8080";
            if (isServerRunning) {
                tvServerIp.setText("Acceso Web: http://" + ip + ":" + port);
            } else {
                tvServerIp.setText("IP local: " + ip);
            }
        }
    }

    @Override
    public byte[] getCurrentFrame() {
        return currentFrameBytes;
    }

    @Override
    public boolean isStreamingAllowed() {
        if (!isAutoMode) {
            return true;
        }
        return isDetected;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopServer();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}

