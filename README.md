# StockFlow

StockFlow utiliza Spring Boot 4.1 y Java 21 para administrar inventario y compras. Por ahora expone un endpoint de salud

## Requisitos

- **JDK 21 o superior.** Comprueba tu versión con `java -version`.
- **Git.**
- **No hace falta instalar Maven.** El proyecto trae Maven Wrapper (`mvnw` y `mvnw.cmd`),
  que descarga Maven 3.9.16 y las dependencias la primera vez que se ejecuta. Para esa
  primera ejecución se necesita internet.

## 1. Clonar el repositorio

```bash
git clone https://github.com/josemiguelm12/stockflow.git
cd stockflow
```

## 2. Instalar las dependencias y ejecutar las pruebas

En Windows (PowerShell):

```powershell
.\mvnw.cmd test
```

En Linux, macOS o Git Bash:

```bash
./mvnw test
```

Debe terminar con `BUILD SUCCESS`.

## 3. Levantar la aplicación

En Windows (PowerShell):

```powershell
.\mvnw.cmd spring-boot:run
```

En Linux, macOS o Git Bash:

```bash
./mvnw spring-boot:run
```

La aplicación queda escuchando en `http://localhost:8080`. Para detenerla, pulsa `Ctrl + C`
en esa terminal.

## 4. Comprobar que responde

Abre `http://localhost:8080/api/health` en el navegador o, desde otra terminal:

```bash
curl http://localhost:8080/api/health
```

En Windows PowerShell escribe `curl.exe` en lugar de `curl`. La respuesta debe ser
parecida a esta (el orden de los campos puede variar):

```json
{"status":"UP","application":"StockFlow"}
```

## 5. PostgreSQL, variables de entorno y migraciones

La aplicación necesita PostgreSQL. Flyway aplica `src/main/resources/db/migration` al arrancar
(`V1__identity_foundation.sql` crea `users`, `auth_sessions`, `one_time_tokens` y
`outbound_emails`). Las migraciones solo avanzan; no hay rollback automático.

Define estas variables en tu entorno (los nombres y su propósito están en `.env.example`;
nunca subas un `.env` con valores reales). Spring Boot no lee `.env` por sí solo:

| Variable | Propósito |
|---|---|
| `SPRING_DATASOURCE_URL` | URL JDBC, p. ej. `jdbc:postgresql://localhost:5432/stockflow` |
| `SPRING_DATASOURCE_USERNAME` | Usuario de PostgreSQL |
| `SPRING_DATASOURCE_PASSWORD` | Contraseña de PostgreSQL |
| `STOCKFLOW_CORS_ALLOWED_ORIGINS` | Orígenes permitidos, separados por comas (sin `*`), p. ej. `http://localhost:4200` |
| `STOCKFLOW_OUTBOX_ENCRYPTION_KEY` | Clave AES-256 del outbox: Base64 de exactamente 32 bytes |
| `STOCKFLOW_OUTBOX_ENCRYPTION_KEY_ID` | Identificador no vacío de esa clave (permite rotarla) |
| `STOCKFLOW_PUBLIC_ACTIVATION_URL` | URL pública de la landing de activación, p. ej. `http://localhost:8080/activate` |
| `STOCKFLOW_ACTIVATION_TOKEN_TTL` | Vigencia del token de activación, duración ISO-8601 (p. ej. `PT24H`) |
| `STOCKFLOW_SMTP_HOST`, `STOCKFLOW_SMTP_PORT`, `STOCKFLOW_SMTP_FROM` | Servidor SMTP y remitente; solo los usa el worker |
| `STOCKFLOW_SMTP_USERNAME`, `STOCKFLOW_SMTP_PASSWORD` | Credenciales SMTP (vacías si el servidor no exige autenticación) |
| `STOCKFLOW_JWT_SECRET` | Secreto HMAC del JWT: Base64 de exactamente 32 bytes aleatorios (obligatorio, solo la API) |
| `STOCKFLOW_RATE_LIMIT_GLOBAL_PER_MINUTE` | Solicitudes por minuto y por IP a `/api/v1/**`; vacío = `120` (no es un secreto) |
| `STOCKFLOW_RATE_LIMIT_LOGIN_PER_MINUTE` | Solicitudes por minuto y por IP a `POST /api/v1/auth/login`; vacío = `10` |
| `STOCKFLOW_PUBLIC_PASSWORD_RESET_URL` | URL pública absoluta de la landing de recuperación, p. ej. `http://localhost:8080/reset-password` (obligatoria, no es un secreto) |
| `STOCKFLOW_PASSWORD_RESET_TOKEN_TTL` | Vigencia del token de recuperación, duración ISO-8601 positiva; vacío = `PT30M` |
| `STOCKFLOW_RATE_LIMIT_PASSWORD_FORGOT_PER_MINUTE` | Solicitudes por minuto y por IP a `POST /api/v1/auth/password/forgot`; vacío = `5` |
| `STOCKFLOW_RATE_LIMIT_PASSWORD_RESET_PER_MINUTE` | Ídem a `POST /api/v1/auth/password/reset`; vacío = `10` |
| `STOCKFLOW_RATE_LIMIT_PASSWORD_CHANGE_PER_MINUTE` | Ídem a `POST /api/v1/auth/password/change`; vacío = `5` |
| `STOCKFLOW_ADMIN_BOOTSTRAP_ENABLED` | `true` solo para crear el primer ADMIN (sección 10); vacío o `false` en el uso normal |
| `STOCKFLOW_ADMIN_BOOTSTRAP_EMAIL` | Email del primer ADMIN; solo se lee con el bootstrap habilitado |
| `STOCKFLOW_ADMIN_BOOTSTRAP_PASSWORD` | Contraseña del primer ADMIN (política de contraseñas); solo con el bootstrap habilitado. Nunca la versione |

Sin las variables `SPRING_DATASOURCE_*`, `STOCKFLOW_CORS_ALLOWED_ORIGINS`, `STOCKFLOW_PUBLIC_ACTIVATION_URL`,
`STOCKFLOW_ACTIVATION_TOKEN_TTL`, `STOCKFLOW_PUBLIC_PASSWORD_RESET_URL`, `STOCKFLOW_JWT_SECRET` y las dos del outbox,
la aplicación no arranca (la URL de recuperación debe ser absoluta, `http` o `https`). Una clave del outbox o un
secreto JWT que no sea Base64 de 32 bytes también impide el arranque. El worker de correo no necesita
`STOCKFLOW_JWT_SECRET`. La API arranca sin SMTP; solo el worker lo exige.

Para generar la clave del outbox y el secreto JWT (cada uno distinto):

```powershell
$b = New-Object byte[] 32; [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

```bash
openssl rand -base64 32
```

Seguridad HTTP: solo `GET /api/health`, `GET /activate`, `GET /reset-password`, los tres `POST /api/v1/auth/*` de la
sección 7, `POST /api/v1/auth/login` y `POST /api/v1/auth/password/{forgot,reset}` son públicos;
`GET /api/v1/auth/me`, `POST /api/v1/auth/logout` y `POST /api/v1/auth/password/change` exigen Bearer; las rutas
`/api/v1/admin/users…` de la sección 10 exigen Bearer de un usuario con rol `ADMIN`; cualquier otra ruta se deniega
(401 sin credenciales, 403 con ellas).

## 6. Pruebas

`./mvnw test` (o `.\mvnw.cmd test`) ejecuta las pruebas unitarias y MVC (incluido un servidor SMTP de
prueba en memoria), que no necesitan base de datos. Las pruebas de integración usan Testcontainers con PostgreSQL real y requieren
Docker en ejecución:

```bash
./mvnw verify -Pintegration-test
```

## 7. Registro, activación y correo

| Método | Ruta | Resultado |
|---|---|---|
| POST | `/api/v1/auth/register` | `{"email","password"}` → `201`; email repetido `409`; datos inválidos `400` |
| POST | `/api/v1/auth/activate` | `{"token"}` → `204`; token inválido, vencido o ya usado `400` |
| POST | `/api/v1/auth/resend-activation` | `{"email"}` → siempre `202` con el mismo cuerpo, exista o no la cuenta |
| GET | `/activate` | Página mínima que activa la cuenta al pulsar un botón |

La contraseña requiere al menos 8 caracteres, una letra y un número. El registro crea la cuenta
`PENDING_ACTIVATION` y deja un correo `PENDING` en el outbox, con el enlace cifrado (AES-256-GCM);
la API nunca habla con SMTP. El enlace del correo es `STOCKFLOW_PUBLIC_ACTIVATION_URL#token=...`: el token va en
el fragmento, que el navegador no envía al servidor.

Los correos los envía un **worker**, un proceso aparte que procesa los `PENDING`, los marca `SENT` y termina.
Con las variables `STOCKFLOW_SMTP_*` definidas (PowerShell):

```powershell
$env:STOCKFLOW_WORKER_ENABLED = "true"
$env:SPRING_MAIN_WEB_APPLICATION_TYPE = "none"
.\mvnw.cmd spring-boot:run
```

(en Bash: `STOCKFLOW_WORKER_ENABLED=true SPRING_MAIN_WEB_APPLICATION_TYPE=none ./mvnw spring-boot:run`).
Si SMTP no responde, el correo sigue `PENDING` y puede reintentarse volviendo a ejecutar el worker; si dos workers
corren a la vez no se reclama el mismo correo. No se garantiza "exactamente una vez": si el proceso cae justo
después de que SMTP acepta el mensaje y antes de marcar `SENT`, ese correo puede reenviarse.

Verificación manual con un SMTP real: registra un correo propio, ejecuta el worker, abre el enlace recibido y pulsa
**Activar cuenta**.

## 8. Sesiones, bloqueo y rate limiting

| Método | Ruta | Resultado |
|---|---|---|
| POST | `/api/v1/auth/login` | `{"email","password"}` → `200 {"accessToken","tokenType":"Bearer","expiresIn":900}` |
| GET | `/api/v1/auth/me` | Con `Authorization: Bearer <token>` → `{"id","email","role"}` |
| POST | `/api/v1/auth/logout` | Con Bearer → `204`; el mismo token deja de servir de inmediato |

- **Rechazos:** un email con formato inválido devuelve `400` (no depende de si la cuenta existe). Usuario
  desconocido, contraseña incorrecta y cuenta bloqueada devuelven el mismo `401` genérico.
  Credenciales correctas de una cuenta que no está `ACTIVE` devuelven `403` (cuenta no activa) y no crean sesión.
  Credenciales correctas de una cuenta con reset de contraseña forzado por un ADMIN devuelven `403`
  (`Password reset required`), tampoco crean sesión, y hay que completar el reset (sección 9).
- **JWT:** HS256, 15 minutos fijos, claims `sub` (usuario), `jti`, `iat` y `exp`; sin email, rol ni datos sensibles.
  El `jti` se guarda en `auth_sessions` (nunca el JWT). En cada petición se validan firma, expiración, sesión
  vigente y no revocada, y que el usuario siga `ACTIVE`; email y rol se leen de la base de datos, así que un cambio
  de rol o una desactivación se reflejan de inmediato. El token solo se acepta en la cabecera `Authorization`
  (no en query string ni cookies).
- **Bloqueo por cuenta:** el quinto fallo consecutivo bloquea la cuenta 15 minutos; durante el bloqueo se rechaza
  incluso la contraseña correcta, sin crear sesión ni incrementar el contador. Al vencer, se evalúan de nuevo las
  credenciales: un fallo mantiene el contador y abre otro bloqueo; un acierto lo limpia. Los intentos de una misma
  cuenta se serializan con un bloqueo de fila, así que solicitudes simultáneas no eluden el umbral.
- **Rate limiting** (independiente del bloqueo): por IP de la conexión, ventana fija de un minuto; `/api/v1/**`
  120 por minuto y `POST /api/v1/auth/login` 10 por minuto adicionales (las rutas de contraseña de la sección 9 tienen
  los suyos, independientes entre sí). Al superarlo responde `429` con
  `Retry-After`. `X-Forwarded-For` se ignora a propósito (es falsificable). `/api/health` y `/activate` quedan fuera.
  **Limitación:** el estado vive en memoria de un solo proceso; con varias instancias o detrás de un
  proxy que oculte la IP real haría falta un almacén distribuido o configurar la IP del cliente, fuera de alcance.
  El estado está acotado a 50 000 IPs: si se llena (p. ej. una inundación de IPs distintas), se descartan las
  ventanas vencidas y, si no basta, las IPs *nuevas* reciben `429` hasta que venza la ventana; los límites de las IPs
  ya registradas nunca se reinician.

## 9. Recuperación y cambio de contraseña

| Método | Ruta | Resultado |
|---|---|---|
| POST | `/api/v1/auth/password/forgot` | `{"email"}` → siempre `202` con el mismo cuerpo `{"message":"If the account is eligible, a password reset email will be sent."}`; email mal formado `400` |
| POST | `/api/v1/auth/password/reset` | `{"token","newPassword"}` → `204`; token inválido, vencido, usado o de otro propósito `400` genérico; contraseña que no cumple la política `400` |
| POST | `/api/v1/auth/password/change` | Con Bearer, `{"currentPassword","newPassword"}` → `204`; contraseña actual incorrecta `400` genérico; sin Bearer `401` |
| GET | `/reset-password` | Página mínima con el formulario de nueva contraseña (no cambia estado por GET) |

- **Solicitud (`forgot`):** devuelve exactamente la misma respuesta exista o no la cuenta y esté `ACTIVE`,
  `PENDING_ACTIVATION` o `DISABLED`. Solo una cuenta `ACTIVE` recibe un token y un correo; para el resto no se muta nada.
  Una nueva solicitud invalida el token anterior, así que nunca hay más de un token vigente por usuario.
- **Token:** 32 bytes aleatorios (`SecureRandom`), de un solo uso, vigente 30 minutos por defecto
  (`STOCKFLOW_PASSWORD_RESET_TOKEN_TTL`; deja de valer en el instante exacto de vencimiento). En la base de datos solo
  existe su hash SHA-256; el correo lo lleva cifrado (AES-256-GCM) en el outbox. El enlace es
  `STOCKFLOW_PUBLIC_PASSWORD_RESET_URL#token=...`: el token va en el fragmento, nunca en path ni query.
- **Correo:** igual que la activación (sección 7): la API solo deja el correo `PENDING` en el outbox y el worker lo envía;
  si SMTP está apagado, la respuesta no cambia y el correo sigue pendiente. El worker entrega la plantilla de
  recuperación sin alterar la de activación.
- **Landing:** lee el token del fragmento, lo borra de la barra de direcciones antes de cualquier petición y solo hace
  `POST /api/v1/auth/password/reset` cuando el usuario envía el formulario. Un GET (prefetch, escáner de enlaces)
  nunca consume el token. Se sirve con `Cache-Control: no-store` y una CSP estricta.
- **Contraseña nueva:** política de siempre (mínimo 8 caracteres, una letra y un número, máximo 72 bytes). Si no la
  cumple, el token no se consume ni cambia nada.
- **Tras un reset o un cambio exitoso:** se actualiza el hash BCrypt, se limpia `password_reset_required`, se invalidan los
  demás tokens de recuperación y se **revocan todas las sesiones del usuario** (incluida la que autorizó `change`);
  no se crea sesión ni se devuelve JWT: hay que iniciar sesión de nuevo. `change` solo actúa sobre el usuario del
  Bearer (no acepta un `userId`) y no modifica el contador de fallos ni el bloqueo de login.
- **Concurrencia:** `forgot`, `reset` y `change` bloquean primero la fila del usuario y después tokens, sesiones y outbox
  (mismo orden que la activación): dos redenciones del mismo token permiten como máximo un éxito.
- **Rate limiting:** `forgot` 5, `reset` 10 y `change` 5 por minuto y por IP (configurables), además del límite global.

## 10. Administración de usuarios y primer ADMIN

Todas estas rutas exigen `Authorization: Bearer` de un usuario con rol `ADMIN`: sin token o con un token inválido
responden `401`; con un usuario `STANDARD`, `403`. El rol se lee de la base de datos en cada petición, así que un
cambio de rol se aplica en la siguiente petición sin emitir otro JWT.

| Método | Ruta | Cuerpo | Resultado |
|---|---|---|---|
| GET | `/api/v1/admin/users?page=0&size=20` | — | `200` con `items` (`id`, `email`, `role`, `accountStatus`, `passwordResetRequired`, `createdAt`, `updatedAt`), `page`, `size`, `totalElements`, `totalPages` |
| PATCH | `/api/v1/admin/users/{id}/role` | `{"role":"ADMIN"}` o `"STANDARD"` | `204` |
| PATCH | `/api/v1/admin/users/{id}/status` | `{"accountStatus":"ACTIVE"}` o `"DISABLED"` | `204` |
| POST | `/api/v1/admin/users/{id}/force-password-reset` | — | `202` (sin token ni enlace en la respuesta) |

- **Listado:** orden estable `createdAt`, luego `id`. `page >= 0` y `size` entre 1 y 100; otro valor da `400`. Nunca
  incluye hash, intentos fallidos, bloqueo, tokens ni sesiones.
- **Errores:** UUID mal formado `400`; usuario inexistente `404`; cambio no permitido `409` genérico. Los cuerpos son
  cerrados: cualquier propiedad distinta de la indicada (p. ej. `userId`, `email`) da `400`.
- **Rol:** solo `ADMIN` o `STANDARD`; asignar el mismo rol no hace nada (`204`). Un ADMIN no puede cambiar su propio
  rol, y no se puede degradar al último ADMIN activo (`409`).
- **Estado:** solo `ACTIVE` o `DISABLED`. Desactivar exige una cuenta `ACTIVE` y revoca todas sus sesiones de inmediato;
  reactivar exige una cuenta `DISABLED` que alguna vez se activó y no revive sesiones ni limpia el bloqueo de login.
  Un ADMIN no puede desactivarse a sí mismo ni desactivar al último ADMIN activo (`409`). Pedir el estado actual no
  hace nada (`204`).
- **Reset forzado:** solo sobre una cuenta `ACTIVE` (pendiente o deshabilitada: `409`). Marca el reset como
  obligatorio, invalida los tokens de recuperación anteriores, encola un correo de recuperación (el mismo flujo de la
  sección 9) y revoca todas las sesiones del usuario, también la del ADMIN si se lo aplica a sí mismo. La contraseña
  anterior deja de abrir sesión de inmediato; al completar el reset con el enlace, todo vuelve a la normalidad.
  Repetirlo invalida el token anterior. La operación no llama a SMTP: si el servidor de correo está caído, el correo
  queda pendiente para el worker.
- **Concurrencia:** los cambios de rol y estado (y el bootstrap) se serializan con un bloqueo transaccional de
  PostgreSQL, así que dos operaciones simultáneas nunca dejan el sistema sin ADMIN activos.
- **Registro:** cada operación deja un evento `admin_event` en el log con IDs, acción y resultado (sin emails,
  contraseñas ni tokens). No hay auditoría persistente.

### Crear el primer ADMIN (bootstrap)

El primer ADMIN se crea con un **proceso aparte** (no hay endpoint HTTP para ello). Solo necesita las variables
`SPRING_DATASOURCE_*` y las tres de bootstrap: no arranca el servidor web, no envía correo y no pide el secreto JWT,
CORS, URLs públicas, SMTP ni la clave del outbox. En PowerShell:

```powershell
$env:STOCKFLOW_ADMIN_BOOTSTRAP_ENABLED = "true"
$env:STOCKFLOW_ADMIN_BOOTSTRAP_EMAIL = "<email del primer ADMIN>"
$env:STOCKFLOW_ADMIN_BOOTSTRAP_PASSWORD = "<contraseña segura>"
$env:SPRING_MAIN_WEB_APPLICATION_TYPE = "none"
.\mvnw.cmd spring-boot:run
```

(en Bash: `STOCKFLOW_ADMIN_BOOTSTRAP_ENABLED=true STOCKFLOW_ADMIN_BOOTSTRAP_EMAIL=... STOCKFLOW_ADMIN_BOOTSTRAP_PASSWORD=... SPRING_MAIN_WEB_APPLICATION_TYPE=none ./mvnw spring-boot:run`).

El proceso termina e informa en el log `Admin bootstrap finished: created`, `promoted` o `already-present`, sin
mostrar el email ni la contraseña:

- **`created`:** no había ningún ADMIN y el email no existía. Se crea un usuario `ACTIVE` con rol `ADMIN`.
- **`promoted`:** no había ningún ADMIN y el email es de un usuario `ACTIVE` con rol `STANDARD`. Pasa a `ADMIN`, su
  contraseña se reemplaza por la de bootstrap y se revocan sus sesiones y tokens de recuperación.
- **`already-present`:** ese email ya es ADMIN. No cambia nada, ni siquiera la contraseña; repetir es seguro.

Falla sin modificar datos si el email o la contraseña no son válidos, si el email es de una cuenta pendiente o
deshabilitada, o si ya existe otro ADMIN con un email distinto (los siguientes ADMIN se crean promoviendo usuarios
con la API). Cuando termine, **vuelva a dejar `STOCKFLOW_ADMIN_BOOTSTRAP_ENABLED` vacío o en `false`** y quite la
contraseña del entorno: con el bootstrap deshabilitado la aplicación arranca normalmente y no toca ningún usuario.
