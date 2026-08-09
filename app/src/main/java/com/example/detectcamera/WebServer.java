package com.example.detectcamera;

import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class WebServer extends NanoHTTPD {

    private final String authUser;
    private final String authPass;
    private final FrameProvider frameProvider;

    public interface FrameProvider {
        byte[] getCurrentFrame();
        boolean isStreamingAllowed();
        void setCameraHardwareEnabled(boolean enable);
        boolean isCameraHardwareEnabled();
    }

    public WebServer(int port, String user, String pass, FrameProvider frameProvider) {
        super(port);
        this.authUser = user;
        this.authPass = pass;
        this.frameProvider = frameProvider;
    }

    @Override
    public Response serve(IHTTPSession session) {
        Map<String, String> headers = session.getHeaders();
        
        // Autenticación Básica HTTP
        if (!isAuthorized(headers)) {
            Response response = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Acceso Requerido");
            response.addHeader("WWW-Authenticate", "Basic realm=\"DetectCamera Realm\"");
            return response;
        }

        String uri = session.getUri();

        // API para encender/apagar la cámara
        if (uri.equals("/api/camera/toggle")) {
            Map<String, String> params = session.getParms();
            if (params.containsKey("enabled")) {
                boolean enable = "true".equalsIgnoreCase(params.get("enabled"));
                frameProvider.setCameraHardwareEnabled(enable);
            }
            boolean currentState = frameProvider.isCameraHardwareEnabled();
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"enabled\":" + currentState + "}");
        }

        // API para obtener el estado actual
        if (uri.equals("/api/camera/status")) {
            boolean currentState = frameProvider.isCameraHardwareEnabled();
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"enabled\":" + currentState + "}");
        }

        // Endpoint de la imagen en vivo (MJPEG / Single frame request)
        if (uri.equals("/live.jpg")) {
            if (!frameProvider.isCameraHardwareEnabled()) {
                return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "");
            }

            if (!frameProvider.isStreamingAllowed()) {
                return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "");
            }

            byte[] frame = frameProvider.getCurrentFrame();
            if (frame != null && frame.length > 0) {
                return newFixedLengthResponse(
                        Response.Status.OK,
                        "image/jpeg",
                        new ByteArrayInputStream(frame),
                        frame.length
                );
            } else {
                return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "");
            }
        }

        // Página principal HTML con panel web
        String html = "<!DOCTYPE html>" +
                "<html lang='es'>" +
                "<head>" +
                "  <meta charset='UTF-8'>" +
                "  <meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "  <title>Panel de Control - DetectCamera</title>" +
                "  <style>" +
                "    body { font-family: system-ui, -apple-system, sans-serif; background: #121212; color: #fff; display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 100vh; margin: 0; }" +
                "    .card { background: #1e1e1e; padding: 24px; border-radius: 16px; box-shadow: 0 4px 20px rgba(0,0,0,0.5); text-align: center; max-width: 450px; width: 90%; }" +
                "    .stream-box { width: 100%; height: 320px; background: #000; border-radius: 12px; overflow: hidden; display: flex; align-items: center; justify-content: center; margin-top: 15px; position: relative; }" +
                "    img { width: 100%; height: 100%; object-fit: cover; }" +
                "    .controls { margin-top: 20px; display: flex; flex-direction: column; gap: 12px; }" +
                "    .btn { padding: 12px 20px; border: none; border-radius: 8px; font-weight: bold; cursor: pointer; transition: 0.2s; font-size: 15px; }" +
                "    .btn-toggle-on { background: #e53935; color: white; }" +
                "    .btn-toggle-off { background: #4caf50; color: white; }" +
                "    .status-badge { font-size: 14px; margin-bottom: 10px; color: #888; }" +
                "  </style>" +
                "</head>" +
                "<body>" +
                "  <div class='card'>" +
                "    <h2>Transmisión en Vivo</h2>" +
                "    <div id='status' class='status-badge'>Comprobando estado...</div>" +
                "    <div class='stream-box'>" +
                "      <img id='cam' src='/live.jpg' alt='Cámara Desactivada' onerror='this.style.opacity=0.3' onload='this.style.opacity=1'/>" +
                "    </div>" +
                "    <div class='controls'>" +
                "      <button id='btnToggle' class='btn btn-toggle-on' onclick='toggleCamera()'>Cargando...</button>" +
                "    </div>" +
                "  </div>" +
                "  <script>" +
                "    let isEnabled = true;" +
                "    const img = document.getElementById('cam');" +
                "    const btn = document.getElementById('btnToggle');" +
                "    const status = document.getElementById('status');" +
                "" +
                "    function updateUi(active) {" +
                "      isEnabled = active;" +
                "      if(active) {" +
                "        btn.innerText = 'Apagar Cámara (Liberar Sensor)';" +
                "        btn.className = 'btn btn-toggle-on';" +
                "        status.innerText = '• Cámara Física: ACTIVA';" +
                "        status.style.color = '#4caf50';" +
                "      } else {" +
                "        btn.innerText = 'Encender Cámara';" +
                "        btn.className = 'btn btn-toggle-off';" +
                "        status.innerText = '• Cámara Física: LIBERADA (Apagada)';" +
                "        status.style.color = '#e53935';" +
                "      }" +
                "    }" +
                "" +
                "    function fetchStatus() {" +
                "      fetch('/api/camera/status')" +
                "        .then(r => r.json())" +
                "        .then(data => updateUi(data.enabled));" +
                "    }" +
                "" +
                "    function toggleCamera() {" +
                "      fetch('/api/camera/toggle?enabled=' + (!isEnabled))" +
                "        .then(r => r.json())" +
                "        .then(data => updateUi(data.enabled));" +
                "    }" +
                "" +
                "    setInterval(() => {" +
                "      if(isEnabled) {" +
                "        img.src = '/live.jpg?t=' + new Date().getTime();" +
                "      }" +
                "    }, 150);" +
                "" +
                "    fetchStatus();" +
                "  </script>" +
                "</body>" +
                "</html>";

        return newFixedLengthResponse(Response.Status.OK, "text/html", html);
    }

    private boolean isAuthorized(Map<String, String> headers) {
        String authHeader = headers.get("authorization");
        if (authHeader != null && authHeader.toLowerCase().startsWith("basic ")) {
            String base64Credentials = authHeader.substring("basic ".length()).trim();
            String credentials = new String(Base64.decode(base64Credentials, Base64.DEFAULT));
            String[] parts = credentials.split(":", 2);
            if (parts.length == 2) {
                return authUser.equals(parts[0]) && authPass.equals(parts[1]);
            }
        }
        return false;
    }
}
