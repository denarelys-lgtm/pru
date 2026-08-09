package com.example.detectcamera;

import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class WebServer extends NanoHTTPD {

    private final String authUser;
    private final String authPass;
    private final FrameProvider frameProvider;

    public interface FrameProvider {
        byte[] getBackFrame();
        byte[] getFrontFrame();
        byte[] getScreenFrame();
        InputStream getAudioStream();
        
        boolean isStreamingAllowed();
        void setBackCameraEnabled(boolean enable);
        void setFrontCameraEnabled(boolean enable);
        void setAudioEnabled(boolean enable);
        
        boolean isBackCameraEnabled();
        boolean isFrontCameraEnabled();
        boolean isScreenShareEnabled();
        boolean isAudioEnabled();
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

        // Control API para conmutar las fuentes
        if (uri.equals("/api/camera/toggle")) {
            Map<String, String> params = session.getParms();
            String target = params.get("target");
            boolean enabled = "true".equalsIgnoreCase(params.get("enabled"));

            if ("back".equals(target)) {
                frameProvider.setBackCameraEnabled(enabled);
            } else if ("front".equals(target)) {
                frameProvider.setFrontCameraEnabled(enabled);
            } else if ("audio".equals(target)) {
                frameProvider.setAudioEnabled(enabled);
            }

            return getStatusResponse();
        }

        if (uri.equals("/api/camera/status")) {
            return getStatusResponse();
        }

        // Endpoint Imagen Cámara Trasera
        if (uri.equals("/live_back.jpg")) {
            if (!frameProvider.isBackCameraEnabled() || !frameProvider.isStreamingAllowed()) {
                return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "");
            }
            return createFrameResponse(frameProvider.getBackFrame());
        }

        // Endpoint Imagen Cámara Frontal
        if (uri.equals("/live_front.jpg")) {
            if (!frameProvider.isFrontCameraEnabled() || !frameProvider.isStreamingAllowed()) {
                return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "");
            }
            return createFrameResponse(frameProvider.getFrontFrame());
        }

        // Endpoint Transmisión de Pantalla
        if (uri.equals("/live_screen.jpg")) {
            if (!frameProvider.isScreenShareEnabled()) {
                return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "");
            }
            return createFrameResponse(frameProvider.getScreenFrame());
        }

        // Endpoint Transmisión de Audio en Vivo (WAV Chunked)
        if (uri.equals("/audio.wav")) {
            if (!frameProvider.isAudioEnabled()) {
                return newFixedLengthResponse(Response.Status.NO_CONTENT, "audio/wav", "");
            }
            InputStream audioStream = frameProvider.getAudioStream();
            if (audioStream != null) {
                return newChunkedResponse(Response.Status.OK, "audio/wav", audioStream);
            }
            return newFixedLengthResponse(Response.Status.NO_CONTENT, "audio/wav", "");
        }

        // HTML Panel Web Completo
        String html = "<!DOCTYPE html>" +
                "<html lang='es'>" +
                "<head>" +
                "  <meta charset='UTF-8'>" +
                "  <meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "  <title>Panel de Monitoreo Pro - DetectCamera</title>" +
                "  <style>" +
                "    body { font-family: system-ui, -apple-system, sans-serif; background: #121212; color: #fff; margin: 0; padding: 20px; display: flex; flex-direction: column; align-items: center; }" +
                "    h1 { font-size: 22px; margin-bottom: 20px; text-align: center; }" +
                "    .grid { display: flex; flex-wrap: wrap; gap: 20px; justify-content: center; width: 100%; max-width: 1000px; }" +
                "    .card { background: #1e1e1e; padding: 16px; border-radius: 16px; box-shadow: 0 4px 20px rgba(0,0,0,0.5); text-align: center; flex: 1 1 280px; max-width: 440px; }" +
                "    .stream-box { width: 100%; height: 240px; background: #000; border-radius: 12px; overflow: hidden; margin: 12px 0; display: flex; align-items: center; justify-content: center; }" +
                "    img { width: 100%; height: 100%; object-fit: contain; }" +
                "    .btn { width: 100%; padding: 12px; border: none; border-radius: 8px; font-weight: bold; cursor: pointer; transition: 0.2s; font-size: 14px; margin-top: 5px; }" +
                "    .btn-on { background: #e53935; color: white; }" +
                "    .btn-off { background: #4caf50; color: white; }" +
                "    .status { font-size: 13px; font-weight: 500; margin-bottom: 8px; }" +
                "    audio { width: 100%; margin-top: 10px; }" +
                "  </style>" +
                "</head>" +
                "<body>" +
                "  <h1>Centro de Monitoreo en Vivo</h1>" +
                "  <div class='grid'>" +
                "    <!-- TRASERA -->" +
                "    <div class='card'>" +
                "      <h3>Cámara Trasera</h3>" +
                "      <div id='stBack' class='status'>Cargando...</div>" +
                "      <div class='stream-box'><img id='imgBack' src='/live_back.jpg' alt='Trasera Apagada' onerror='this.style.opacity=0.2' onload='this.style.opacity=1'/></div>" +
                "      <button id='btnBack' class='btn' onclick='toggleSource(\"back\")'>...</button>" +
                "    </div>" +
                "" +
                "    <!-- FRONTAL -->" +
                "    <div class='card'>" +
                "      <h3>Cámara Frontal</h3>" +
                "      <div id='stFront' class='status'>Cargando...</div>" +
                "      <div class='stream-box'><img id='imgFront' src='/live_front.jpg' alt='Frontal Apagada' onerror='this.style.opacity=0.2' onload='this.style.opacity=1'/></div>" +
                "      <button id='btnFront' class='btn' onclick='toggleSource(\"front\")'>...</button>" +
                "    </div>" +
                "" +
                "    <!-- PANTALLA -->" +
                "    <div class='card'>" +
                "      <h3>Pantalla del Dispositivo</h3>" +
                "      <div id='stScreen' class='status'>• Transmisión Activa</div>" +
                "      <div class='stream-box'><img id='imgScreen' src='/live_screen.jpg' alt='Esperando pantalla...' onerror='this.style.opacity=0.2' onload='this.style.opacity=1'/></div>" +
                "    </div>" +
                "" +
                "    <!-- AUDIO -->" +
                "    <div class='card'>" +
                "      <h3>Audio del Micrófono</h3>" +
                "      <div id='stAudio' class='status'>Cargando...</div>" +
                "      <audio id='audioPlayer' controls></audio>" +
                "      <button id='btnAudio' class='btn' onclick='toggleSource(\"audio\")'>...</button>" +
                "    </div>" +
                "  </div>" +
                "" +
                "  <script>" +
                "    let backActive = false, frontActive = false, audioActive = false;" +
                "" +
                "    function updateUi(data) {" +
                "      backActive = data.back;" +
                "      frontActive = data.front;" +
                "      audioActive = data.audio;" +
                "" +
                "      const btnB = document.getElementById('btnBack');" +
                "      btnB.innerText = backActive ? 'Apagar Cámara Trasera' : 'Encender Cámara Trasera';" +
                "      btnB.className = backActive ? 'btn btn-on' : 'btn btn-off';" +
                "" +
                "      const btnF = document.getElementById('btnFront');" +
                "      btnF.innerText = frontActive ? 'Apagar Cámara Frontal' : 'Encender Cámara Frontal';" +
                "      btnF.className = frontActive ? 'btn btn-on' : 'btn btn-off';" +
                "" +
                "      const btnA = document.getElementById('btnAudio');" +
                "      const player = document.getElementById('audioPlayer');" +
                "      btnA.innerText = audioActive ? 'Desactivar Micrófono' : 'Activar Micrófono';" +
                "      btnA.className = audioActive ? 'btn btn-on' : 'btn btn-off';" +
                "      if(audioActive && player.paused) {" +
                "        player.src = '/audio.wav?t=' + Date.now();" +
                "        player.play().catch(e => {});" +
                "      } else if(!audioActive) {" +
                "        player.pause();" +
                "        player.src = '';" +
                "      }" +
                "    }" +
                "" +
                "    function fetchStatus() {" +
                "      fetch('/api/camera/status').then(r => r.json()).then(data => updateUi(data));" +
                "    }" +
                "" +
                "    function toggleSource(target) {" +
                "      let state = false;" +
                "      if(target === 'back') state = !backActive;" +
                "      if(target === 'front') state = !frontActive;" +
                "      if(target === 'audio') state = !audioActive;" +
                "      fetch('/api/camera/toggle?target=' + target + '&enabled=' + state).then(r => r.json()).then(data => updateUi(data));" +
                "    }" +
                "" +
                "    setInterval(() => {" +
                "      if(backActive) document.getElementById('imgBack').src = '/live_back.jpg?t=' + Date.now();" +
                "      if(frontActive) document.getElementById('imgFront').src = '/live_front.jpg?t=' + Date.now();" +
                "      document.getElementById('imgScreen').src = '/live_screen.jpg?t=' + Date.now();" +
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
                "\"screen\":" + frameProvider.isScreenShareEnabled() + "," +
                "\"audio\":" + frameProvider.isAudioEnabled() +
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
