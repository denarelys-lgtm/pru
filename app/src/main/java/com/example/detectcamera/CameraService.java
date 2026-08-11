package com.example.detectcamera;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class CameraService extends Service {

    private static final String CHANNEL_ID = "CameraServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int PUERTO_WEB = 8080;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private WebServer webServer;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        
        backgroundThread = new HandlerThread("ImageReaderThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Servidor Transmitiendo")
                .setContentText("Puerto: " + PUERTO_WEB)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        if (intent != null && intent.hasExtra("RESULT_CODE") && intent.hasExtra("DATA_INTENT")) {
            int resultCode = intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra("DATA_INTENT");
            String user = intent.getStringExtra("USER_PARAM");
            String pass = intent.getStringExtra("PASS_PARAM");

            if (resultCode == Activity.RESULT_OK && data != null) {
                MediaProjectionManager projectionManager = 
                        (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                if (projectionManager != null) {
                    mediaProjection = projectionManager.getMediaProjection(resultCode, data);
                    iniciarServidorYCaptura(user, pass);
                }
            }
        }

        return START_STICKY;
    }

    private void iniciarServidorYCaptura(String user, String pass) {
        // 1. Iniciar Servidor Web
        if (webServer == null) {
            try {
                webServer = new WebServer(PUERTO_WEB);
                webServer.setCredenciales(user, pass);
                webServer.start(10000, false);

                String ip = obtenerIpDispositivo();
                mostrarToastEnUI("Servidor Activo: http://" + ip + ":" + PUERTO_WEB);
            } catch (IOException e) {
                Log.e("CameraService", "Error WebServer: " + e.getMessage());
                mostrarToastEnUI("Error iniciando servidor: " + e.getMessage());
            }
        }

        // 2. Configurar Captura de Pantalla en Tiempo Real
        int width = 720;
        int height = 1280;
        int density = 320;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * width;

                    Bitmap bitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride, 
                            height, 
                            Bitmap.Config.ARGB_8888
                    );
                    bitmap.copyPixelsFromBuffer(buffer);

                    Bitmap cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos);

                    byte[] jpegBytes = baos.toByteArray();
                    if (webServer != null) {
                        webServer.actualizarFrame(jpegBytes);
                    }

                    cleanBitmap.recycle();
                    bitmap.recycle();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }, backgroundHandler);

        if (mediaProjection != null && virtualDisplay == null) {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenCapture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, null
            );
        }
    }

    private String obtenerIpDispositivo() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            return Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
        }
        return "localhost";
    }

    private void mostrarToastEnUI(String mensaje) {
        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(getApplicationContext(), mensaje, Toast.LENGTH_LONG).show()
        );
    }

    @Override
    public void onDestroy() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        if (imageReader != null) {
            imageReader.close();
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
        }
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Camera Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}
