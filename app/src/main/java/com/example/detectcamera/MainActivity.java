package com.example.detectcamera;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PERMISSIONS = 100;
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        gestionarPermisos();
    }

    private void gestionarPermisos() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponent = new ComponentName(this, MyDeviceAdminReceiver.class);

        // Comprobación de Device Owner
        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            Toast.makeText(this, "Ejecutando en Modo Device Owner", Toast.LENGTH_SHORT).show();

            String[] permisos = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            };

            for (String permiso : permisos) {
                dpm.setPermissionGrantState(
                    adminComponent,
                    getPackageName(),
                    permiso,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                );
            }

            solicitarCapturaPantalla();

        } else {
            // Modo Normal (Solicitar permisos si faltan)
            if (faltanPermisosRuntime()) {
                solicitarPermisosEstandar();
            } else {
                solicitarCapturaPantalla();
            }
        }
    }

    private boolean faltanPermisosRuntime() {
        boolean camara = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED;
        boolean audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED;
        return camara || audio;
    }

    private void solicitarPermisosEstandar() {
        ActivityCompat.requestPermissions(
            this,
            new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            },
            REQUEST_CODE_PERMISSIONS
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            solicitarCapturaPantalla();
        }
    }

    private void solicitarCapturaPantalla() {
        MediaProjectionManager projectionManager = 
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (projectionManager != null) {
            startActivityForResult(
                    projectionManager.createScreenCaptureIntent(), 
                    REQUEST_CODE_SCREEN_CAPTURE
            );
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE && resultCode == Activity.RESULT_OK && data != null) {
            Intent serviceIntent = new Intent(this, CameraService.class);
            serviceIntent.putExtra("RESULT_CODE", resultCode);
            serviceIntent.putExtra("DATA_INTENT", data);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }
    }
}
