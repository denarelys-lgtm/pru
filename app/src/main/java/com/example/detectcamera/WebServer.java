package com.example.detectcamera;

import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class WebServer extends NanoHTTPD {

    private final String authUser;
    private final String authPass;
    private final FrameProvider frameProvider;

    public interface FrameProvider {
        byte[] getBackFrame();
        byte[] getFrontFrame();
        boolean isStreamingAllowed();
        void setBackCameraEnabled(boolean enable);
        void setFrontCameraEnabled(boolean enable);
        boolean isBackCameraEnabled();
        boolean isFrontCameraEnabled();
        boolean isConcurrentSupported();
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
        
        if (!isAuthorized(headers)) {
            Response response = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Acceso Requerido");
            response.addHeader("WWW-Authenticate", "Basic realm=\"DetectCamera Realm\"");
            return response;
        }

        String uri = session.getUri();

        // Control API para conmutar las cámaras
        if (uri.equals("/api/camera/toggle")) {
            Map<String, String> params = session.getParms();
            String cam = params.get("cam");
            boolean enabled = "true".equalsIgnoreCase(params.get("enabled"));

            if ("back".equals(cam)) {
                frameProvider.setBackCameraEnabled(enabled);
            } else if ("front".equals(cam)) {
                frameProvider.setFrontCameraEnabled(enabled);
            }

            return getStatusResponse();
        }

        // Estado actual de las cámaras
        if (uri.equals("/api/camera/status")) {
            return getStatusResponse();
        }

        // Endpoint Imagen Cámara Trasera
        if (uri.equals("/live_back.jpg")) {
            if (!frameProvider.isBackCameraEnabled() || !frameProvider.isStreamingAllowed()) {
                return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "");
            }
            byte[] frame = frameProvider.getBackFrame();
            return createFrameResponse(frame);
        }

        // Endpoint Imagen Cámara Frontal
        if (uri.equals("/live_front.jpg")) {
            if (!frameProvider.isFrontCameraEnabled() || !frameProvider.isStreamingAllowed()) {
                return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "");
            }
            byte[] frame = frameProvider.getFrontFrame();
            return createFrameResponse(frame);
        }

        // Panel Web Dual HTML
        String html = "<!DOCTYPE html>" +
                "<html lang='es'>" +
                "<head>" +
                "  <meta charset='UTF-8'>" +
                "  <meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "  <title>Panel Dual - DetectCamera</title>" +
                "  <style>" +
                "    body { font-family: system-ui, -apple-system, sans-serif; background: #121212; color: #fff; margin: 0; padding: 20px; display: flex; flex-direction: column; align-items: center; }" +
                "    h1 { font-size: 22px; margin-bottom: 20px; text-align: center; }" +
                "    .grid { display: flex; flex-wrap: wrap; gap: 20px; justify-content: center; width: 100%; max-width: 900px; }" +
                "    .card { background: #1e1e1e; padding: 16px; border-radius: 16px; box-shadow: 0 4px 20px rgba(0,0,0,0.5); text-align: center; flex: 1 1 380px; max-width: 440px; }" +
                "    .stream-box { width: 100%; height: 260px; background: #000; border-radius: 12px; overflow: hidden; margin: 12px 0; display: flex; align-items: center; justify-content: center; }" +
                "    img { width: 100%; height: 100%; object-fit: cover; }" +
                "    .btn { width: 100%; padding: 12px; border: none; border-radius: 8px; font-weight: bold; cursor: pointer; transition: 0.2s; font-size: 14px; }" +
                "    .btn-on { background: #e53935; color: white; }" +
                "    .btn-off { background: #4caf50; color: white; }" +
                "    .status { font-size: 13px; font-weight: 500; margin-bottom: 8px; }" +
                "  </style>" +
                "</head>" +
                "<body>" +
                "  <h1>Panel de Monitoreo Dual</h1>" +
                "  <div class='grid'>" +
                "    <!-- CÁMARA TRASERA -->" +
                "    <div class='card'>" +
                "      <h3>Cámara Trasera</h3>" +
                "      <div id='stBack' class='status'>Cargando...</div>" +
                "      <div class='stream-box'>" +
                "        <img id='imgBack' src='/live_back.jpg' alt='Trasera Apagada' onerror='this.style.opacity=0.2' onload='this.style.opacity=1'/>" +
                "      </div>" +
                "      <button id='btnBack' class='btn' onclick='toggleCam(\"back\")'>...</button>" +
                "    </div>" +
                "" +
                "    <!-- CÁMARA FRONTAL -->" +
                "    <div class='card'>" +
                "      <h3>Cámara Frontal</h3>" +
                "      <div id='stFront' class='status'>Cargando...</div>" +
                "      <div class='stream-box'>" +
                "        <img id='imgFront' src='/live_front.jpg' alt='Frontal Apagada' onerror='this.style.opacity=0.2' onload='this.style.opacity=1'/>" +
                "      </div>" +
                "      <button id='btnFront' class='btn' onclick='toggleCam(\"front\")'>...</button>" +
                "    </div>" +
                "  </div>" +
                "" +
                "  <script>" +
                "    let backActive = false;" +
                "    let frontActive = false;" +
                "" +
                "    function updateUi(data) {" +
                "      backActive = data.back;" +
                "      frontActive = data.front;" +
                "" +
                "      const btnB = document.getElementById('btnBack');" +
                "      const stB = document.getElementById('stBack');" +
                "      if(backActive) {" +
                "        btnB.innerText = 'Apagar Cámara Trasera';" +
                "        btnB.className = 'btn btn-on';" +
                "        stB.innerText = '• Estado: EN VIVO';" +
                "        stB.style.color = '#4caf50';" +
                "      } else {" +
                "        btnB.innerText = 'Encender Cámara Trasera';" +
                "        btnB.className = 'btn btn-off';" +
                "        stB.innerText = '• Estado: APAGADA';" +
                "        stB.style.color = '#e53935';" +
                "      }" +
                "" +
                "      const btnF = document.getElementById('btnFront');" +
                "      const stF = document.getElementById('stFront');" +
                "      if(frontActive) {" +
                "        btnF.innerText = 'Apagar Cámara Frontal';" +
                "        btnF.className = 'btn btn-on';" +
                "        stF.innerText = '• Estado: EN VIVO';" +
                "        stF.style.color = '#4caf50';" +
                "      } else {" +
                "        btnF.innerText = 'Encender Cámara Frontal';" +
                "        btnF.className = 'btn btn-off';" +
                "        stF.innerText = '• Estado: APAGADA';" +
                "        stF.style.color = '#e53935';" +
                "      }" +
                "    }" +
                "" +
                "    function fetchStatus() {" +
                "      fetch('/api/camera/status')" +
                "        .then(r => r.json())" +
                "        .then(data => updateUi(data));" +
                "    }" +
                "" +
                "    function toggleCam(cam) {" +
                "      const state = (cam === 'back') ? !backActive : !frontActive;" +
                "      fetch('/api/camera/toggle?cam=' + cam + '&enabled=' + state)" +
                "        .then(r => r.json())" +
                "        .then(data => updateUi(data));" +
                "    }" +
                "" +
                "    setInterval(() => {" +
                "      if(backActive) document.getElementById('imgBack').src = '/live_back.jpg?t=' + Date.now();" +
                "      if(frontActive) document.getElementById('imgFront').src = '/live_front.jpg?t=' + Date.now();" +
                "    }, 150);" +
                "" +
                "    fetchStatus();" +
                "  </script>" +
                "</body>" +
                "</html>";

        return newFixedLengthResponse(Response.Status.OK, "text/html", html);
    }

    private Response getStatusResponse() {
        String json = "{" +
                "\"back\":" + frameProvider.isBackCameraEnabled() + "," +
                "\"front\":" + frameProvider.isFrontCameraEnabled() + "," +
                "\"concurrent\":" + frameProvider.isConcurrentSupported() +
                "}";
        return newFixedLengthResponse(Response.Status.OK, "application/json", json);
    }

    private Response createFrameResponse(byte[] frame) {
        if (frame != null && frame.length > 0) {
            return newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(frame), frame.length);
        } else {
            return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "");
        }
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
