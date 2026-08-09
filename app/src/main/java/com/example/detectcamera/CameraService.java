package com.example.detectcamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.WindowManager;

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
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
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
    private PreviewView pendingPreviewView;

    private WebServer webServer;
    private boolean isServerRunning = false;
    private boolean isAutoMode = true;

    private boolean isBackCameraEnabled = true;
    private boolean isFrontCameraEnabled = false;
    private boolean isScreenShareEnabled = false;
    private boolean isAudioEnabled = false;

    private volatile boolean isDetected = false;
    private volatile byte[] backFrameBytes;
    private volatile byte[] frontFrameBytes;
    private volatile byte[] screenFrameBytes;

    // Proyección de Pantalla
    private int screenResultCode = 0;
    private Intent screenPermissionData = null;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader screenImageReader;

    // Audio
    private AudioRecord audioRecord;
    private Thread audioThread;
    private boolean isRecordingAudio = false;

    private ServiceCallback callback;

    public interface ServiceCallback {
        void onDetectionStatusChanged(boolean detected, String statusText);
        void onRequestScreenCapturePermission();
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

        Notification notification = createNotification("Servicio de Monitoreo Activo");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                serviceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            }
            startForeground(NOTIFICATION_ID, notification, serviceType);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        startCamera();

        return START_STICKY;
    }

    public void setScreenCapturePermission(int resultCode, Intent data) {
        this.screenResultCode = resultCode;
        this.screenPermissionData = data;
        if (isScreenShareEnabled) {
            startScreenCaptureInternal();
        }
    }

    @Override
    public void setScreenShareEnabled(boolean enable) {
        this.isScreenShareEnabled = enable;
        if (enable) {
            if (screenPermissionData != null) {
                startScreenCaptureInternal();
            } else if (callback != null) {
                // Solicitar al usuario permiso en la pantalla del celular
                callback.onRequestScreenCapturePermission();
            }
        } else {
            stopScreenCapture();
        }
    }

    private void startScreenCaptureInternal() {
        if (screenPermissionData == null || virtualDisplay != null) return;

        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (projectionManager != null) {
            mediaProjection = projectionManager.getMediaProjection(screenResultCode, screenPermissionData);
            setupVirtualDisplay();
        }
    }

    public void stopScreenCapture() {
        isScreenShareEnabled = false;
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (screenImageReader != null) {
            screenImageReader.close();
            screenImageReader = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        screenFrameBytes = null;
    }

    private void setupVirtualDisplay() {
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        if (windowManager != null) {
            windowManager.getDefaultDisplay().getMetrics(metrics);
        }

        int width = metrics.widthPixels / 2;
        int height = metrics.heightPixels / 2;
        int density = metrics.densityDpi;

        screenImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        screenImageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image != null) {
                try {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * width;

                    Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
                    bitmap.copyPixelsFromBuffer(buffer);

                    Bitmap cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);

                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 45, out);
                    screenFrameBytes = out.toByteArray();
                } catch (Exception ignored) {
                } finally {
                    image.close();
                }
            }
        }, null);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                screenImageReader.getSurface(), null, null
        );
    }

    @Override
    public void setAudioEnabled(boolean enable) {
        this.isAudioEnabled = enable;
        if (enable) {
            startAudioRecording();
        } else {
            stopAudioRecording();
        }
    }

    private synchronized void startAudioRecording() {
        if (isRecordingAudio) return;
        isRecordingAudio = true;

        audioThread = new Thread(() -> {
            int sampleRate = 16000;
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            try {
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                audioRecord.startRecording();
            } catch (SecurityException e) {
                isRecordingAudio = false;
            }
        });
        audioThread.start();
    }

    private synchronized void stopAudioRecording() {
        isRecordingAudio = false;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }
    }

    @Override
    public InputStream getAudioStream() {
        if (!isRecordingAudio || audioRecord == null) return null;

        PipedInputStream pipedInputStream = new PipedInputStream();
        try {
            PipedOutputStream pipedOutputStream = new PipedOutputStream(pipedInputStream);

            new Thread(() -> {
                try {
                    byte[] header = createWavHeader(16000, 1, 16);
                    pipedOutputStream.write(header);

                    byte[] buffer = new byte[1024];
                    while (isRecordingAudio && audioRecord != null) {
                        int read = audioRecord.read(buffer, 0, buffer.length);
                        if (read > 0) {
                            pipedOutputStream.write(buffer, 0, read);
                        }
                    }
                    pipedOutputStream.close();
                } catch (IOException ignored) {}
            }).start();

        } catch (IOException e) {
            return null;
        }
        return pipedInputStream;
    }

    private byte[] createWavHeader(int sampleRate, int channels, int bitsPerSample) {
        byte[] header = new byte[44];
        long byteRate = sampleRate * channels * bitsPerSample / 8;

        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = 0; header[5] = 0; header[6] = 0; header[7] = 0;
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0;
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (channels * bitsPerSample / 8); header[33] = 0;
        header[34] = (byte) bitsPerSample; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = 0; header[41] = 0; header[42] = 0; header[43] = 0;

        return header;
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
    public boolean isBackCameraEnabled() { return isBackCameraEnabled; }

    @Override
    public boolean isFrontCameraEnabled() { return isFrontCameraEnabled; }

    @Override
    public boolean isScreenShareEnabled() { return isScreenShareEnabled; }

    @Override
    public boolean isAudioEnabled() { return isAudioEnabled; }

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

    public boolean isServerRunning() { return isServerRunning; }

    public void setAutoMode(boolean autoMode) { this.isAutoMode = autoMode; }

    @Override
    public byte[] getBackFrame() { return backFrameBytes; }

    @Override
    public byte[] getFrontFrame() { return frontFrameBytes; }

    @Override
    public byte[] getScreenFrame() { return screenFrameBytes; }

    @Override
    public boolean isStreamingAllowed() {
        if (!isAutoMode) return true;
        return isDetected;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Canal de Monitoreo",
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
                .setContentTitle("DetectCamera Activo")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopScreenCapture();
        stopAudioRecording();
        stopWebServer();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}
