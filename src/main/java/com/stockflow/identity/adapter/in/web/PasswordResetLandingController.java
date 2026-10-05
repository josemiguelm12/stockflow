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
 * Landing mínima del enlace de recuperación. El token llega en el fragmento (#token=...), que el navegador no
 * envía al servidor; el script lo guarda en una variable, lo borra de la barra de direcciones antes de cualquier
 * petición y solo hace POST cuando el usuario envía el formulario. Un GET (prefetch, escáner de enlaces) nunca
 * muta estado. El HTML devuelto es estático: no contiene ni refleja el token.
 */
@RestController
class PasswordResetLandingController {

    private static final String STYLE = """
            body{font-family:system-ui,sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem}
            label{display:block;margin:1rem 0 .25rem}
            input{font-size:1rem;padding:.5rem;width:100%;box-sizing:border-box}
            button{font-size:1rem;padding:.6rem 1.2rem;margin-top:1rem}""";

    private static final String SCRIPT = """
            (function () {
              var token = new URLSearchParams(location.hash.slice(1)).get('token');
              var form = document.getElementById('reset-form');
              var status = document.getElementById('status');
              var button = document.getElementById('submit');
              if (!token) { form.hidden = true; status.textContent = 'Enlace de recuperación inválido.'; return; }
              history.replaceState(null, '', location.pathname);
              form.addEventListener('submit', function (event) {
                event.preventDefault();
                var first = document.getElementById('password').value;
                var second = document.getElementById('confirm').value;
                if (first !== second) { status.textContent = 'Las contraseñas no coinciden.'; return; }
                button.disabled = true;
                status.textContent = 'Enviando…';
                fetch('/api/v1/auth/password/reset', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ token: token, newPassword: first })
                }).then(function (response) {
                  if (response.status === 204) {
                    token = null;
                    form.hidden = true;
                    status.textContent = 'Contraseña actualizada. Ya puede iniciar sesión con la nueva contraseña.';
                    return;
                  }
                  button.disabled = false;
                  if (response.status === 429) { status.textContent = 'Demasiados intentos. Espere un minuto.'; return; }
                  return response.json().then(function (body) {
                    status.textContent = body && body.errors
                      ? 'La contraseña debe tener al menos 8 caracteres, una letra y un número.'
                      : 'El enlace es inválido, venció o ya fue usado.';
                  }, function () { status.textContent = 'No se pudo completar la solicitud.'; });
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
            <title>Restablecer contraseña - StockFlow</title>
            <style>%s</style>
            </head>
            <body>
            <h1>StockFlow</h1>
            <p id="status">Escriba su nueva contraseña.</p>
            <form id="reset-form">
            <label for="password">Nueva contraseña</label>
            <input id="password" type="password" autocomplete="new-password" minlength="8" maxlength="72" required>
            <label for="confirm">Repita la contraseña</label>
            <input id="confirm" type="password" autocomplete="new-password" minlength="8" maxlength="72" required>
            <button id="submit" type="submit">Cambiar contraseña</button>
            </form>
            <script>%s</script>
            </body>
            </html>
            """.formatted(STYLE, SCRIPT);

    private static final String CSP = "default-src 'none'; script-src '" + sha256(SCRIPT) + "'; style-src '"
            + sha256(STYLE) + "'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    @GetMapping(value = "/reset-password", produces = MediaType.TEXT_HTML_VALUE)
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
