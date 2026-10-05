package com.stockflow.identity.adapter.in.web;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Landing mínima del enlace de activación. El token llega en el fragmento (#token=...), que el navegador
 * no envía al servidor; la cuenta solo se activa con un clic explícito que hace POST. Un GET (prefetch,
 * escáner de enlaces) nunca muta estado.
 */
@RestController
class ActivationLandingController {

    private static final String STYLE = """
            body{font-family:system-ui,sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem}
            button{font-size:1rem;padding:.6rem 1.2rem}""";

    private static final String SCRIPT = """
            (function () {
              var params = new URLSearchParams(location.hash.slice(1));
              var token = params.get('token');
              var button = document.getElementById('activate');
              var status = document.getElementById('status');
              if (!token) { button.hidden = true; status.textContent = 'Enlace de activación inválido.'; return; }
              history.replaceState(null, '', location.pathname);
              button.addEventListener('click', function () {
                button.disabled = true;
                fetch('/api/v1/auth/activate', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ token: token })
                }).then(function (response) {
                  button.hidden = true;
                  status.textContent = response.status === 204
                    ? 'Cuenta activada. Ya puede cerrar esta página.'
                    : 'El enlace es inválido, venció o ya fue usado.';
                }).catch(function () {
                  button.disabled = false;
                  status.textContent = 'No se pudo contactar al servidor. Intente de nuevo.';
                });
              });
            })();""";

    private static final String HTML = """
            <!doctype html>
            <html lang="es">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Activar cuenta - StockFlow</title>
            <style>%s</style>
            </head>
            <body>
            <h1>StockFlow</h1>
            <p id="status">Pulse el botón para activar su cuenta.</p>
            <button id="activate" type="button">Activar cuenta</button>
            <script>%s</script>
            </body>
            </html>
            """.formatted(STYLE, SCRIPT);

    private static final String CSP = "default-src 'none'; script-src '" + sha256(SCRIPT) + "'; style-src '"
            + sha256(STYLE) + "'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    @GetMapping(value = "/activate", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> landing() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", CSP)
                .body(HTML);
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return "sha256-" + Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
