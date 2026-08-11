package com.example.detectcamera;

import android.util.Base64;
import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;

public class WebServer extends NanoHTTPD {

    private byte[] ultimoFrame = null;
    private String usuarioValido = "";
    private String passwordValida = "";

    public WebServer(int port) {
        super(port);
    }

    public void setCredenciales(String user, String pass) {
        this.usuarioValido = user != null ? user.trim() : "";
        this.passwordValida = pass != null ? pass.trim() : "";
    }

    public synchronized void actualizarFrame(byte[] frame) {
        this.ultimoFrame = frame;
    }

    private boolean estaAutenticado(IHTTPSession session) {
        // Si no se configuró usuario o contraseña, permite el acceso libre
        if (usuarioValido.isEmpty() || passwordValida.isEmpty()) {
            return true;
        }

        String authHeader = session.getHeaders().get("authorization");
        if (authHeader != null && authHeader.startsWith("Basic ")) {
            try {
                String base64Creds = authHeader.substring(6).trim();
                String credenciales = new String(Base64.decode(base64Creds, Base64.DEFAULT));
                String[] partes = credenciales.split(":", 2);
                if (partes.length == 2) {
                    return usuarioValido.equals(partes[0]) && passwordValida.equals(partes[1]);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    @Override
    public Response serve(IHTTPSession session) {
        // Exigir autenticación HTTP Basic
        if (!estaAutenticado(session)) {
            Response response = newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, 
                    "text/plain", 
                    "Acceso Denegado. Inicie sesión."
            );
            response.addHeader("WWW-Authenticate", "Basic realm=\"Acceso Restringido\"");
            return response;
        }

        String uri = session.getUri();

        if ("/frame.jpg".equals(uri)) {
            byte[] frameActual;
            synchronized (this) {
                frameActual = ultimoFrame;
            }

            if (frameActual != null && frameActual.length > 0) {
                return newFixedLengthResponse(
                        Response.Status.OK, 
                        "image/jpeg", 
                        new ByteArrayInputStream(frameActual), 
                        frameActual.length
                );
            } else {
                return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "");
            }
        }

        String html = "<!DOCTYPE html>"
                + "<html>"
                + "<head>"
                + "<title>Panel de Transmisión</title>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                + "<style>"
                + "body { background-color: #121212; color: #ffffff; font-family: Arial, sans-serif; text-align: center; margin: 0; padding: 20px; }"
                + "h1 { color: #00E676; }"
                + "img { max-width: 95%; height: auto; border: 2px solid #333; border-radius: 8px; margin-top: 20px; }"
                + "</style>"
                + "</head>"
                + "<body>"
                + "<h1>Servidor Transmitiendo</h1>"
                + "<p>Estado: Activo</p>"
                + "<img src='/frame.jpg' id='streamImg' alt='Cargando video...'>"
                + "<script>"
                + "  setInterval(function() {"
                + "     document.getElementById('streamImg').src = '/frame.jpg?' + new Date().getTime();"
                + "  }, 150);"
                + "</script>"
                + "</body>"
                + "</html>";

        return newFixedLengthResponse(Response.Status.OK, "text/html", html);
    }
}
