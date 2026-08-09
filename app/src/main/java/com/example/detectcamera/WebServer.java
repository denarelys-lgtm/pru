package com.example.detectcamera;

import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

public class WebServer extends NanoHTTPD {

    public interface FrameProvider {
        byte[] getCurrentFrame();
        boolean isStreamingAllowed();
    }

    private final String username;
    private final String password;
    private final FrameProvider frameProvider;

    public WebServer(int port, String username, String password, FrameProvider frameProvider) {
        super(port);
        this.username = username;
        this.password = password;
        this.frameProvider = frameProvider;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        Map<String, String> headers = session.getHeaders();
        String cookieHeader = headers.get("cookie");

        boolean isAuthenticated = cookieHeader != null && cookieHeader.contains("session=authenticated");

        if (uri.equals("/login") && Method.POST.equals(method)) {
            try {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                Map<String, String> params = session.getParms();
                String user = params.get("username");
                String pass = params.get("password");

                if (this.username.equals(user) && this.password.equals(pass)) {
                    Response response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "<html><body>Iniciando sesión...</body></html>");
                    response.addHeader("Location", "/");
                    response.addHeader("Set-Cookie", "session=authenticated; Path=/");
                    return response;
                } else {
                    return newFixedLengthResponse(Response.Status.OK, MIME_HTML, getLoginPage("Credenciales incorrectas"));
                }
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error procesando inicio de sesión");
            }
        }

        if (uri.equals("/logout")) {
            Response response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "<html><body>Cerrando sesión...</body></html>");
            response.addHeader("Location", "/");
            response.addHeader("Set-Cookie", "session=expired; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
            return response;
        }

        if (!isAuthenticated) {
            return newFixedLengthResponse(Response.Status.OK, MIME_HTML, getLoginPage(null));
        }

        if (uri.equals("/stream.jpg")) {
            if (frameProvider != null && frameProvider.isStreamingAllowed()) {
                byte[] frame = frameProvider.getCurrentFrame();
                if (frame != null && frame.length > 0) {
                    return newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(frame), frame.length);
                }
            }
            return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "");
        }

        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, getDashboardPage());
    }

    private String getLoginPage(String error) {
        return "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                + "<title>Login - Cámara</title><style>"
                + "body { font-family: sans-serif; background: #121212; color: #fff; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }"
                + ".card { background: #1e1e1e; padding: 25px; border-radius: 10px; width: 280px; text-align: center; box-shadow: 0 4px 10px rgba(0,0,0,0.5); }"
                + "input { width: 90%; padding: 10px; margin: 8px 0; border-radius: 5px; border: 1px solid #333; background: #2a2a2a; color: white; }"
                + "button { width: 98%; padding: 10px; background: #6200ee; color: white; border: none; border-radius: 5px; font-weight: bold; cursor: pointer; margin-top: 10px; }"
                + ".error { color: #ff5252; font-size: 13px; margin-bottom: 8px; }"
                + "</style></head><body>"
                + "<div class=\"card\">"
                + "<h3>Panel de Cámara</h3>"
                + (error != null ? "<div class=\"error\">" + error + "</div>" : "")
                + "<form action=\"/login\" method=\"post\">"
                + "<input type=\"text\" name=\"username\" placeholder=\"Usuario\" required/><br/>"
                + "<input type=\"password\" name=\"password\" placeholder=\"Contraseña\" required/><br/>"
                + "<button type=\"submit\">Ingresar</button>"
                + "</form></div></body></html>";
    }

    private String getDashboardPage() {
        return "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                + "<title>Panel Transmisión</title><style>"
                + "body { font-family: sans-serif; background: #121212; color: #fff; text-align: center; margin: 0; padding: 15px; }"
                + ".container { max-width: 550px; margin: 0 auto; background: #1e1e1e; padding: 20px; border-radius: 12px; }"
                + "img { width: 100%; height: auto; border-radius: 8px; border: 2px solid #333; background: #000; }"
                + ".btn { background: #e53935; color: white; padding: 10px 20px; text-decoration: none; border-radius: 5px; display: inline-block; margin-top: 15px; font-weight: bold; }"
                + ".status { margin-bottom: 12px; color: #00e676; font-size: 14px; }"
                + "</style></head><body>"
                + "<div class=\"container\">"
                + "<h3>Transmisión en Vivo</h3>"
                + "<div class=\"status\">● Servidor Activo</div>"
                + "<img id=\"stream\" src=\"/stream.jpg\" alt=\"Esperando detección o captura...\" />"
                + "<br/><a href=\"/logout\" class=\"btn\">Cerrar Sesión</a>"
                + "</div>"
                + "<script>"
                + "setInterval(() => {"
                + "  const img = document.getElementById('stream');"
                + "  img.src = '/stream.jpg?t=' + new Date().getTime();"
                + "}, 150);"
                + "</script>"
                + "</body></html>";
    }
}

