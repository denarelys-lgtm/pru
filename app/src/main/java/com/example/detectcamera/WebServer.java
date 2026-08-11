package com.example.detectcamera;

import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;

public class WebServer extends NanoHTTPD {

    private byte[] ultimoFrame = null;

    public WebServer(int port) {
        super(port);
    }

    public void actualizarFrame(byte[] frame) {
        this.ultimoFrame = frame;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();

        if ("/stream".equals(uri) || "/frame.jpg".equals(uri)) {
            if (ultimoFrame != null && ultimoFrame.length > 0) {
                return newFixedLengthResponse(
                        Response.Status.OK, 
                        "image/jpeg", 
                        new ByteArrayInputStream(ultimoFrame), 
                        ultimoFrame.length
                );
            } else {
                return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "");
            }
        }

        // Panel de control HTML por defecto
        String html = "<!DOCTYPE html>"
                + "<html>"
                + "<head>"
                + "<title>Panel de Transmisión</title>"
                + "<meta title='viewport' content='width=device-width, initial-scale=1.0'>"
                + "<style>"
                + "body { background-color: #121212; color: #ffffff; font-family: Arial, sans-serif; text-align: center; margin: 0; padding: 20px; }"
                + "h1 { color: #00E676; }"
                + "img { max-width: 90%; height: auto; border: 2px solid #333; border-radius: 8px; margin-top: 20px; }"
                + "</style>"
                + "</head>"
                + "<body>"
                + "<h1>Servidor Transmitiendo</h1>"
                + "<p>Estado: Activo</p>"
                + "<img src='/frame.jpg' id='streamImg' alt='Esperando video...'>"
                + "<script>"
                + "  setInterval(function() {"
                + "     document.getElementById('streamImg').src = '/frame.jpg?' + new Date().getTime();"
                + "  }, 200);"
                + "</script>"
                + "</body>"
                + "</html>";

        return newFixedLengthResponse(Response.Status.OK, "text/html", html);
    }
}
