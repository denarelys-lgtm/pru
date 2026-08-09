package com.example.detectcamera;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.format.Formatter;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements CameraService.ServiceCallback {

    private static final int PERMISSION_REQUEST_CODE = 101;

    private PreviewView previewView;
    private SwitchMaterial switchMode;
    private TextView tvDetectionStatus, tvServerIp;
    private TextInputEditText etPort, etUser, etPass;
    private Button btnToggleServer, btnRequestScreenShare;

    private CameraService cameraService;
    private boolean isBound = false;

    private ActivityResultLauncher<Intent> screenCaptureLauncher;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CameraService.LocalBinder binder = (CameraService.LocalBinder) service;
            cameraService = binder.getService();
            cameraService.setCallback(MainActivity.this);
            isBound = true;

            cameraService.bindPreview(previewView);
            updateServerUiState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            cameraService = null;
        }
    };

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

        // Registro para capturar pantalla con autorización del sistema
        screenCaptureLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        if (isBound && cameraService != null) {
                            cameraService.startScreenCapture(result.getResultCode(), result.getData());
                            Toast.makeText(this, "Permiso de pantalla concedido", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(this, "Permiso de pantalla denegado", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        switchMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isBound && cameraService != null) {
                cameraService.setAutoMode(isChecked);
            }
            switchMode.setText(isChecked ? "Modo: Automático (Transmitir al detectar)" : "Modo: Manual (Transmitir siempre)");
        });

        btnToggleServer.setOnClickListener(v -> toggleServer());

        if (checkAndRequestPermissions()) {
            startAndBindService();
        }

        updateIpDisplay();
        checkDeviceOwnerStatus();
    }

    public void requestScreenCapturePermission() {
        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (projectionManager != null) {
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isBound && cameraService != null) {
            cameraService.bindPreview(previewView);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isBound && cameraService != null) {
            cameraService.unbindPreview();
        }
    }

    private boolean checkAndRequestPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.RECORD_AUDIO);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }

        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startAndBindService();
            } else {
                Toast.makeText(this, "Permisos requeridos para cámaras y audio", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startAndBindService() {
        Intent serviceIntent = new Intent(this, CameraService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void toggleServer() {
        if (!isBound || cameraService == null) return;

        if (cameraService.isServerRunning()) {
            cameraService.stopWebServer();
            updateServerUiState();
        } else {
            String portStr = etPort.getText() != null ? etPort.getText().toString().trim() : "8080";
            String user = etUser.getText() != null ? etUser.getText().toString().trim() : "admin";
            String pass = etPass.getText() != null ? etPass.getText().toString().trim() : "1234";

            int port = 8080;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException ignored) {}

            if (cameraService.startWebServer(port, user, pass)) {
                updateServerUiState();
                requestScreenCapturePermission(); // Pedir permiso de transmisión de pantalla
                Toast.makeText(this, "Servidor Iniciado", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Error al iniciar servidor", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void updateServerUiState() {
        if (!isBound || cameraService == null) return;

        if (cameraService.isServerRunning()) {
            btnToggleServer.setText("Detener Servidor");
        } else {
            btnToggleServer.setText("Iniciar Servidor Web");
        }
        updateIpDisplay();
    }

    private void updateIpDisplay() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifiManager != null) {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            int ipAddress = wifiInfo.getIpAddress();
            String ip = Formatter.formatIpAddress(ipAddress);
            String port = etPort.getText() != null ? etPort.getText().toString().trim() : "8080";

            if (isBound && cameraService != null && cameraService.isServerRunning()) {
                tvServerIp.setText("Acceso Web: http://" + ip + ":" + port);
            } else {
                tvServerIp.setText("IP local: " + ip);
            }
        }
    }

    private void checkDeviceOwnerStatus() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            Toast.makeText(this, "Modo Device Owner ACTIVO", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onDetectionStatusChanged(boolean detected, String statusText) {
        runOnUiThread(() -> tvDetectionStatus.setText(statusText));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }
}
