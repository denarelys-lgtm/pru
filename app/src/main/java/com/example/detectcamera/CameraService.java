package com.example.detectcamera;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import java.io.IOException;

public class CameraService extends Service {

    private static final String CHANNEL_ID = "CameraServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int PUERTO_WEB = 8080;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private WebServer webServer;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
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

            if (resultCode == Activity.RESULT_OK && data != null) {
                MediaProjectionManager projectionManager = 
                        (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                if (projectionManager != null) {
                    mediaProjection = projectionManager.getMediaProjection(resultCode, data);
                    iniciarServidorYCaptura();
                }
            }
        }

        return START_STICKY;
    }

    private void iniciarServidorYCaptura() {
        // 1. Iniciar el Servidor Web NanoHTTPD
        if (webServer == null) {
            try {
                webServer = new WebServer(PUERTO_WEB);
                webServer.start(10000, false);
                
                String ip = obtenerIpDispositivo();
                mostrarToastEnUI("Servidor corriendo en: http://" + ip + ":" + PUERTO_WEB);
                Log.d("CameraService", "Servidor HTTP iniciado en http://" + ip + ":" + PUERTO_WEB);

            } catch (IOException e) {
                Log.e("CameraService", "Error iniciando WebServer: " + e.getMessage());
                mostrarToastEnUI("Error iniciando puerto " + PUERTO_WEB + ": " + e.getMessage());
            }
        }

        // 2. Crear la proyección en pantalla
        if (mediaProjection != null && virtualDisplay == null) {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenCapture",
                    1280, 720, 320,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    null, null, null
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
        if (mediaProjection != null) {
            mediaProjection.stop();
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
