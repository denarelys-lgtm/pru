package com.example.detectcamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
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
import java.nio.ByteBuffer;
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
    private ImageAnalysis imageAnalysis;
    private PreviewView pendingPreviewView;

    private WebServer webServer;
    private boolean isServerRunning = false;
    private boolean isAutoMode = true;
    private volatile boolean isDetected = false;
    private volatile byte[] currentFrameBytes;

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

        Notification notification = createNotification("Transmisión y detección activas en segundo plano");

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

    private void updateCameraUseCases() {
        if (cameraProvider == null || imageAnalysis == null) return;

        try {
            cameraProvider.unbindAll();
            CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

            if (pendingPreviewView != null) {
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(pendingPreviewView.getSurfaceProvider());
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            } else {
                // Si la app está minimizada, solo mantenemos el análisis de imagen para la web
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

                updateCameraUseCases();

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
                        isDetected = true;
                        notifyStatus(true, "Estado: ¡Rostro Detectado!");
                        imageProxy.close();
                    } else {
                        poseDetector.process(inputImage)
                                .addOnSuccessListener(pose -> {
                                    boolean poseFound = !pose.getAllPoseLandmarks().isEmpty();
                                    isDetected = poseFound;
                                    if (poseFound) {
                                        notifyStatus(true, "Estado: ¡Cuerpo/Silueta Detectada!");
                                    } else {
                                        notifyStatus(false, "Estado: Sin detección activa");
                                    }
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
