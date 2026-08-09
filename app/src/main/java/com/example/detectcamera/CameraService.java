package com.example.detectcamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraService extends LifecycleService implements WebServer.FrameProvider {

    public static final String CHANNEL_ID = "CameraServiceChannel";
    public static final int NOTIFICATION_ID = 1001;

    private final IBinder binder = new LocalBinder();

    private ExecutorService cameraExecutor;
    private FaceDetector faceDetector;
    private PoseDetector poseDetector;

    private ProcessCameraProvider cameraProvider;
    private PreviewView pendingPreviewView;

    private WebServer webServer;
    private boolean isServerRunning = false;
    private boolean isAutoMode = true;

    // Estado independiente de cada cámara
    private boolean isBackCameraEnabled = true;
    private boolean isFrontCameraEnabled = false;

    private volatile boolean isDetected = false;
    private volatile byte[] backFrameBytes;
    private volatile byte[] frontFrameBytes;

    private ServiceCallback callback;

    public interface ServiceCallback {
        void onDetectionStatusChanged(boolean detected, String statusText);
    }

    public class LocalBinder extends Binder {
        public CameraService getService() {
            return CameraService.this;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        super.onBind(intent);
        return binder;
    }

    public void setCallback(ServiceCallback callback) {
        this.callback = callback;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        cameraExecutor = Executors.newSingleThreadExecutor();

        FaceDetectorOptions faceOptions = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build();
        faceDetector = FaceDetection.getClient(faceOptions);

        PoseDetectorOptions poseOptions = new PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build();
        poseDetector = PoseDetection.getClient(poseOptions);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        Notification notification = createNotification("Transmisión y detección activas");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        startCamera();

        return START_STICKY;
    }

    public void bindPreview(PreviewView previewView) {
        this.pendingPreviewView = previewView;
        ContextCompat.getMainExecutor(this).execute(this::updateCameraUseCases);
    }

    public void unbindPreview() {
        this.pendingPreviewView = null;
        ContextCompat.getMainExecutor(this).execute(this::updateCameraUseCases);
    }

    @Override
    public void setBackCameraEnabled(boolean enable) {
        this.isBackCameraEnabled = enable;
        ContextCompat.getMainExecutor(this).execute(this::updateCameraUseCases);
    }

    @Override
    public void setFrontCameraEnabled(boolean enable) {
        this.isFrontCameraEnabled = enable;
        ContextCompat.getMainExecutor(this).execute(this::updateCameraUseCases);
    }

    @Override
    public boolean isBackCameraEnabled() {
        return isBackCameraEnabled;
    }

    @Override
    public boolean isFrontCameraEnabled() {
        return isFrontCameraEnabled;
    }

    @Override
    public boolean isConcurrentSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && cameraProvider != null) {
            return !cameraProvider.getAvailableConcurrentCameraInfos().isEmpty();
        }
        return false;
    }

    private void updateCameraUseCases() {
        if (cameraProvider == null) return;

        try {
            cameraProvider.unbindAll();

            if (!isBackCameraEnabled && !isFrontCameraEnabled) {
                backFrameBytes = null;
                frontFrameBytes = null;
                notifyStatus(false, "Estado: Cámaras Apagadas");
                return;
            }

            // Si ambas están solicitadas y el dispositivo soporta cámara dual simultánea
            if (isBackCameraEnabled && isFrontCameraEnabled && isConcurrentSupported()) {
                bindBothCameras();
            } else if (isBackCameraEnabled) {
                bindSingleCamera(CameraSelector.DEFAULT_BACK_CAMERA, true);
            } else if (isFrontCameraEnabled) {
                bindSingleCamera(CameraSelector.DEFAULT_FRONT_CAMERA, false);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void bindSingleCamera(CameraSelector selector, boolean isBack) {
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        analysis.setAnalyzer(cameraExecutor, imageProxy -> processImageProxy(imageProxy, isBack));

        if (isBack && pendingPreviewView != null) {
            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(pendingPreviewView.getSurfaceProvider());
            cameraProvider.bindToLifecycle(this, selector, preview, analysis);
        } else {
            cameraProvider.bindToLifecycle(this, selector, analysis);
        }
    }

    private void bindBothCameras() {
        // En dispositivos compatibles vincula ambas simultáneamente
        ImageAnalysis backAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        backAnalysis.setAnalyzer(cameraExecutor, imageProxy -> processImageProxy(imageProxy, true));

        ImageAnalysis frontAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        frontAnalysis.setAnalyzer(cameraExecutor, imageProxy -> processImageProxy(imageProxy, false));

        try {
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, backAnalysis);
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, frontAnalysis);
        } catch (Exception e) {
            // Fallback si falla el modo concurrente
            bindSingleCamera(CameraSelector.DEFAULT_BACK_CAMERA, true);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                updateCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @ExperimentalGetImage
    private void processImageProxy(ImageProxy imageProxy, boolean isBack) {
        if (imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        byte[] jpeg = imageProxyToJpeg(imageProxy);
        if (jpeg != null) {
            if (isBack) {
                backFrameBytes = jpeg;
            } else {
                frontFrameBytes = jpeg;
            }
        }

        InputImage inputImage = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

        faceDetector.process(inputImage)
                .addOnSuccessListener(faces -> {
                    boolean faceFound = !faces.isEmpty();
                    if (faceFound) {
                        isDetected = true;
                        notifyStatus(true, "Estado: ¡Rostro Detectado!");
                        imageProxy.close();
                    } else {
                        poseDetector.process(inputImage)
                                .addOnSuccessListener(pose -> {
                                    boolean poseFound = !pose.getAllPoseLandmarks().isEmpty();
                                    isDetected = poseFound;
                                    notifyStatus(poseFound, poseFound ? "Estado: ¡Cuerpo Detectado!" : "Estado: Sin detección");
                                    imageProxy.close();
                                })
                                .addOnFailureListener(e -> imageProxy.close());
                    }
                })
                .addOnFailureListener(e -> imageProxy.close());
    }

    private void notifyStatus(boolean detected, String text) {
        if (callback != null) {
            callback.onDetectionStatusChanged(detected, text);
        }
    }

    private byte[] imageProxyToJpeg(ImageProxy imageProxy) {
        try {
            Bitmap bitmap = imageProxy.toBitmap();
            int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();

            if (rotationDegrees != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotationDegrees);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    public boolean startWebServer(int port, String user, String pass) {
        try {
            if (webServer != null) {
                webServer.stop();
            }
            webServer = new WebServer(port, user, pass, this);
            webServer.start();
            isServerRunning = true;
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public void stopWebServer() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        isServerRunning = false;
    }

    public boolean isServerRunning() {
        return isServerRunning;
    }

    public void setAutoMode(boolean autoMode) {
        this.isAutoMode = autoMode;
    }

    @Override
    public byte[] getBackFrame() {
        return backFrameBytes;
    }

    @Override
    public byte[] getFrontFrame() {
        return frontFrameBytes;
    }

    @Override
    public boolean isStreamingAllowed() {
        if (!isAutoMode) {
            return true;
        }
        return isDetected;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Canal de Cámara en Vivo",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DetectCamera Activa")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopWebServer();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}
