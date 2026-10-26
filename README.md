# StockFlow

StockFlow utiliza Spring Boot 4.1 y Java 21 para administrar inventario y compras. La Práctica 1 implementa el
control de acceso completo: registro con activación por correo, sesiones JWT con logout y bloqueo, recuperación y
cambio de contraseña, administración de usuarios por rol, cola de correo (outbox) con un worker SMTP separado, y la
máquina de estados de la Orden de Compra. La Asignación 2 añade la Gestión de Permisos (solicitar, aprobar o rechazar
`PURCHASE_ORDER_CREATE` y aplicarlo a la creación de órdenes): ver la
[sección 14](#14-gestión-de-permisos-asignación-2). La tabla de endpoints está en la sección 5.

Documentos de la Práctica 1:

- [docs/practica-1/guia-verificacion.md](docs/practica-1/guia-verificacion.md): pasos para verificar cada criterio.
- [docs/practica-1/matriz-verificacion.md](docs/practica-1/matriz-verificacion.md): requisito → código → prueba.
- [docs/maquina-de-estados.md](docs/maquina-de-estados.md): estados y transiciones de la Orden de Compra.

## Requisitos

- **JDK 21 o superior.** Comprueba tu versión con `java -version`.
- **Git.**
- **PostgreSQL** (probado con la versión 16) y una base de datos vacía para la aplicación. La API, el worker y el
  bootstrap del primer ADMIN la necesitan.
- **Docker**, solo para las pruebas de integración (`verify -Pintegration-test`), que levantan su propio PostgreSQL
  con Testcontainers. No hace falta para ejecutar la aplicación ni las pruebas unitarias.
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

Antes de arrancar, define las variables de entorno de la sección 5 en la misma terminal. Sin ellas, la aplicación
no arranca. Al iniciar, Flyway aplica las migraciones pendientes (V1, V2 y V3).

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
`outbound_emails`; `V2__purchase_order_state_machine.sql` crea `purchase_orders`;
`V3__permission_management.sql` crea `permission_requests` y `user_permissions`). Las migraciones solo avanzan; no
hay rollback automático. Para ver las aplicadas:
`SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;`.

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
| `STOCKFLOW_WORKER_ENABLED` | `true` solo al ejecutar el worker de correo (sección 7); vacío o sin definir en la API normal (no es un secreto) |
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

Los comandos del worker y del bootstrap usan además `SPRING_MAIN_WEB_APPLICATION_TYPE=none`. Es una variable
estándar de Spring Boot que impide abrir el puerto HTTP en esos procesos. No es de StockFlow ni va en `.env.example`.

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
`/api/v1/admin/users…` de la sección 10 exigen Bearer de un usuario con rol `ADMIN`; en Gestión de Permisos
(sección 14), crear y listar solicitudes y crear órdenes exigen Bearer, y aprobar o rechazar exige rol `ADMIN`;
cualquier otra ruta se deniega (401 sin credenciales, 403 con ellas).

### Endpoints y acceso

Resumen de todas las rutas. El detalle de cada una está en las secciones 7 a 10 y, para Gestión de Permisos, en la
[sección 14](#14-gestión-de-permisos-asignación-2). Los errores responden con
`ProblemDetail` (JSON), sin trazas ni SQL.

| Método | Ruta | Acceso | Cuerpo mínimo | Resultado esperado |
|---|---|---|---|---|
| GET | `/api/health` | Público | — | `200 {"status":"UP","application":"StockFlow"}` |
| POST | `/api/v1/auth/register` | Público | `{"email","password"}` | `201`; email repetido `409`; datos inválidos `400` |
| POST | `/api/v1/auth/activate` | Público | `{"token"}` | `204`; token inválido, vencido o usado `400` |
| POST | `/api/v1/auth/resend-activation` | Público | `{"email"}` | Siempre `202` con el mismo cuerpo |
| GET | `/activate` | Público | — | `200` página HTML de activación |
| POST | `/api/v1/auth/login` | Público | `{"email","password"}` | `200 {"accessToken","tokenType","expiresIn"}`; credenciales incorrectas o cuenta bloqueada `401`; cuenta no activa o reset obligatorio `403` |
| GET | `/api/v1/auth/me` | Bearer | — | `200 {"id","email","role"}`; sin Bearer válido `401` |
| POST | `/api/v1/auth/logout` | Bearer | — | `204`; el token deja de servir |
| POST | `/api/v1/auth/password/forgot` | Público | `{"email"}` | Siempre `202` con el mismo cuerpo; email mal formado `400` |
| POST | `/api/v1/auth/password/reset` | Público | `{"token","newPassword"}` | `204`; token no utilizable o contraseña débil `400` |
| POST | `/api/v1/auth/password/change` | Bearer | `{"currentPassword","newPassword"}` | `204`; contraseña actual incorrecta o nueva débil `400` |
| GET | `/reset-password` | Público | — | `200` página HTML de recuperación |
| GET | `/api/v1/admin/users?page=0&size=20` | Bearer ADMIN | — | `200` página de usuarios; STANDARD `403` |
| PATCH | `/api/v1/admin/users/{id}/role` | Bearer ADMIN | `{"role"}` | `204`; conflicto `409`; inexistente `404` |
| PATCH | `/api/v1/admin/users/{id}/status` | Bearer ADMIN | `{"accountStatus"}` | `204`; conflicto `409`; inexistente `404` |
| POST | `/api/v1/admin/users/{id}/force-password-reset` | Bearer ADMIN | Sin cuerpo | `202`; cualquier cuerpo `400`; cuenta no activa `409` |
| POST | `/api/v1/permission-requests` | Bearer | `{"permissionCode":"PURCHASE_ORDER_CREATE"}` | `201 {"id","status":"PENDING"}`; código ausente o desconocido `400` |
| GET | `/api/v1/permission-requests/pending` | Bearer | — | `200` lista (`id`, `requestedBy`, `permissionCode`, `requestedAt`): todas para ADMIN, solo las propias para STANDARD |
| POST | `/api/v1/permission-requests/{id}/approve` | Bearer ADMIN | Sin cuerpo | `204` (queda `APPLIED` y se concede el permiso); STANDARD `403`; inexistente `404`; ya resuelta o propia `409` |
| POST | `/api/v1/permission-requests/{id}/reject` | Bearer ADMIN | `{"reason"}` | `204`; motivo ausente o en blanco `400`; STANDARD `403`; inexistente `404`; ya resuelta o propia `409` |
| POST | `/api/v1/purchase-orders` | Bearer | `{"supplierId"}` opcional | `201 {"id"}` con el permiso `PURCHASE_ORDER_CREATE` concedido; sin él `403`, también para un ADMIN |

Cualquier ruta o método que no esté en la tabla responde `401` sin credenciales o `403` con ellas. Además, el rate
limiting de la sección 8 puede responder `429` en las rutas `/api/v1/**`.

## 6. Pruebas

Un solo comando ejecuta la suite completa: primero las pruebas unitarias y MVC (Surefire, `*Test`, incluido un
servidor SMTP de prueba en memoria) y después las de integración (Failsafe, `*IT`), que usan Testcontainers con
PostgreSQL real. No hace falta levantar la aplicación ni una base de datos propia, pero Docker debe estar en
ejecución.

En Linux, macOS o Git Bash:

```bash
./mvnw verify -Pintegration-test
```

En Windows (PowerShell):

```powershell
.\mvnw.cmd verify -Pintegration-test
```

Debe terminar con `BUILD SUCCESS`, sin fallos ni errores en ninguna de las dos fases. Si Docker no está en ejecución,
las pruebas de integración fallan al arrancar el contenedor; eso no indica un error del código. `./mvnw test` ejecuta
solo la primera fase (unitarias y MVC) y no basta para validar la Gestión de Permisos.

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

## 11. Máquina de estados de la Orden de Compra

Los estados y transiciones de `PurchaseOrder` (migración `V2__purchase_order_state_machine.sql`) están documentados
en [docs/maquina-de-estados.md](docs/maquina-de-estados.md). El único endpoint es `POST /api/v1/purchase-orders`
(sección 14), que crea una orden en `DRAFT` solo si el usuario tiene concedido `PURCHASE_ORDER_CREATE`; las
transiciones todavía no tienen endpoints.

## 12. Verificación de la Práctica 1

- [docs/practica-1/guia-verificacion.md](docs/practica-1/guia-verificacion.md): guía paso a paso, desde un checkout
  limpio, con el resultado o código HTTP esperado en cada paso (correo real, token doble o vencido, cinco fallos,
  logout, requests manuales de ADMIN, reset, SMTP apagado, doble worker, reinicio y máquina de estados).
- [docs/practica-1/matriz-verificacion.md](docs/practica-1/matriz-verificacion.md): matriz de cada requisito (RF-CA,
  RF-NOT, RF-NEG, RD, Git) con la clase que lo implementa, la prueba automática que lo cubre y cómo reproducirlo.

## 13. Entrega

Cada funcionalidad se desarrolló en su propia rama y se fusionó en `main` mediante un pull request:

| PR | Rama | Contenido |
|---|---|---|
| #6 | `chore/p1-foundation` | PostgreSQL, Flyway (V1), seguridad base, CORS |
| #7 | `feature/p1-registration-activation` | Registro, activación, reenvío, outbox y worker SMTP |
| #8 | `feature/p1-session-auth` | Login, `/me`, logout, bloqueo y rate limiting |
| #9 | `feature/p1-password-recovery` | Recuperación y cambio de contraseña |
| #10 | `feature/p1-user-administration` | Administración de usuarios y bootstrap del primer ADMIN |
| #11 | `feature/p1-business-states` | Máquina de estados de la Orden de Compra (V2) |

Esta documentación final llega en la rama `docs/p1-readme-qa`.

La versión evaluada se marca con el tag **`practica-1`** sobre `main`. Se crea **solo después** de que el PR de
documentación esté aprobado y fusionado, y con `main` actualizado:

```bash
git checkout main
git pull origin main
git tag practica-1
git push origin practica-1
```

En Moodle se entregan la URL del repositorio y el nombre del tag, `practica-1`. El tag anterior, `asignacion-1`,
pertenece a otra entrega y no se mueve ni se borra.

## 14. Gestión de Permisos (Asignación 2)

Un usuario solicita el permiso de negocio `PURCHASE_ORDER_CREATE`; un ADMIN distinto lo aprueba o lo rechaza con un
motivo. Al aprobar, en la misma transacción la solicitud pasa a `APPLIED` y se concede el permiso; si algo falla, no
cambia nada. Crear una orden de compra exige ese permiso concedido: los roles `ADMIN`/`STANDARD` no lo sustituyen.

| Método | Ruta | Acceso | Cuerpo | Resultado |
|---|---|---|---|---|
| POST | `/api/v1/permission-requests` | Bearer | `{"permissionCode":"PURCHASE_ORDER_CREATE"}` | `201 {"id","status":"PENDING"}` |
| GET | `/api/v1/permission-requests/pending` | Bearer | — | `200` con `id`, `requestedBy`, `permissionCode`, `requestedAt`; ADMIN ve todas, STANDARD solo las suyas |
| POST | `/api/v1/permission-requests/{id}/approve` | Bearer ADMIN | Sin cuerpo | `204` |
| POST | `/api/v1/permission-requests/{id}/reject` | Bearer ADMIN | `{"reason":"<texto>"}` | `204` |
| POST | `/api/v1/purchase-orders` | Bearer | `{"supplierId":"<uuid>"}` opcional | `201 {"id"}` |

- **Identidad:** el solicitante, el decisor y su rol salen siempre de la sesión del Bearer, y el rol y el estado se
  vuelven a leer de la base de datos en cada operación. Cualquier `userId`, `requestedBy`, `role`, `status` o fecha
  enviados en el cuerpo o en la query se ignoran.
- **Errores** (`application/problem+json`, sin motivos, estados internos ni SQL): `400` JSON, UUID o código
  inválidos y motivo ausente o en blanco; `401` sin Bearer válido; `403` STANDARD en aprobar/rechazar, actor que ya no
  es ADMIN activo u orden sin permiso concedido; `404` solicitud inexistente; `409` solicitud ya resuelta o resolución
  de una solicitud propia.
- **Concurrencia:** las decisiones sobre una misma solicitud se serializan con un bloqueo de fila; dos aprobaciones
  simultáneas conceden el permiso una sola vez.
- **Límites conocidos:** el listado no está paginado. Un usuario que ya tiene el permiso puede volver a solicitarlo,
  pero aprobar esa segunda solicitud falla (`500` genérico, sin cambios en la base), porque solo puede existir una
  concesión por usuario y permiso.

### Demostración reproducible (RF-GP-01 a RF-GP-07)

**Requisitos:** los de la sección «Requisitos» (JDK 21, Git, PostgreSQL 16; Docker solo para las pruebas de
integración), las variables de la sección 5 y la aplicación levantada (sección 3), que aplica V1–V3 al arrancar.
Comprueba `GET /api/health` como en la sección 4. No subas credenciales al repositorio: los valores entre `<...>` son
tuyos y solo existen en tu terminal.

**Usuarios.**

1. Crea el primer ADMIN con el bootstrap de la sección 10 usando `<admin-email>` y `<admin-password>`. Al terminar,
   deja `STOCKFLOW_ADMIN_BOOTSTRAP_ENABLED` vacío o en `false` y elimina la contraseña del entorno
   (`Remove-Item Env:STOCKFLOW_ADMIN_BOOTSTRAP_PASSWORD` en PowerShell o `unset STOCKFLOW_ADMIN_BOOTSTRAP_PASSWORD` en
   Bash).
2. Registra y activa un usuario STANDARD (`<standard-email>`, `<standard-password>`) con el flujo de la sección 7:
   `POST /api/v1/auth/register`, ejecuta el worker y abre el enlace de activación del correo.

**Sesiones.** Cada Bearer dura 15 minutos; si una petición responde `401`, repite el login.

Bash (Linux, macOS o Git Bash):

```bash
API=http://localhost:8080/api/v1
login() { curl -s -X POST "$API/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$1\",\"password\":\"$2\"}" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/'; }
ADMIN_TOKEN=$(login '<admin-email>' '<admin-password>')
STANDARD_TOKEN=$(login '<standard-email>' '<standard-password>')
curl -s "$API/auth/me" -H "Authorization: Bearer $ADMIN_TOKEN"      # anota el id del ADMIN
curl -s "$API/auth/me" -H "Authorization: Bearer $STANDARD_TOKEN"   # anota el id del STANDARD
```

PowerShell (los cuerpos JSON se pasan a `curl.exe` por la entrada estándar con `--data-binary '@-'`, para que
PowerShell no altere las comillas):

```powershell
$api = "http://localhost:8080/api/v1"
$env:ADMIN_TOKEN = (Invoke-RestMethod -Method Post "$api/auth/login" -ContentType "application/json" `
  -Body '{"email":"<admin-email>","password":"<admin-password>"}').accessToken
$env:STANDARD_TOKEN = (Invoke-RestMethod -Method Post "$api/auth/login" -ContentType "application/json" `
  -Body '{"email":"<standard-email>","password":"<standard-password>"}').accessToken
curl.exe -s "$api/auth/me" -H "Authorization: Bearer $env:ADMIN_TOKEN"
curl.exe -s "$api/auth/me" -H "Authorization: Bearer $env:STANDARD_TOKEN"
```

En cada paso va primero el bloque Bash y después el de PowerShell. `-i` imprime el código HTTP en la primera línea.

**RF-GP-01 — el STANDARD solicita el permiso.** Respuesta `201 {"id":"...","status":"PENDING"}`; se guarda el `id`.

```bash
REQUEST_ID=$(curl -s -X POST "$API/permission-requests" -H "Authorization: Bearer $STANDARD_TOKEN" \
  -H 'Content-Type: application/json' -d '{"permissionCode":"PURCHASE_ORDER_CREATE"}' \
  | sed -E 's/.*"id":"([^"]+)".*/\1/'); echo "$REQUEST_ID"
```

```powershell
$env:REQUEST_ID = (Invoke-RestMethod -Method Post "$api/permission-requests" -ContentType "application/json" `
  -Headers @{ Authorization = "Bearer $env:STANDARD_TOKEN" } -Body '{"permissionCode":"PURCHASE_ORDER_CREATE"}').id
$env:REQUEST_ID
```

**Preparación de RF-GP-07 — el ADMIN también solicita el permiso para sí mismo.** Repite el comando anterior con
`$ADMIN_TOKEN` (o `$env:ADMIN_TOKEN`) y guarda el `id` como `ADMIN_REQUEST_ID`.

**RF-GP-02 — consultar pendientes.** El ADMIN ve las dos solicitudes. El STANDARD, en la misma ruta, solo ve la suya:
no aparece `ADMIN_REQUEST_ID` aunque añada `?userId=...`.

```bash
curl -s "$API/permission-requests/pending" -H "Authorization: Bearer $ADMIN_TOKEN"
curl -s "$API/permission-requests/pending" -H "Authorization: Bearer $STANDARD_TOKEN"
```

```powershell
curl.exe -s "$api/permission-requests/pending" -H "Authorization: Bearer $env:ADMIN_TOKEN"
curl.exe -s "$api/permission-requests/pending" -H "Authorization: Bearer $env:STANDARD_TOKEN"
```

**RF-GP-05 (antes) — sin permiso no hay orden.** El STANDARD recibe `403`, y el ADMIN también, porque ningún rol
sustituye al permiso.

```bash
curl -s -i -X POST "$API/purchase-orders" -H "Authorization: Bearer $STANDARD_TOKEN"
curl -s -i -X POST "$API/purchase-orders" -H "Authorization: Bearer $ADMIN_TOKEN"
```

```powershell
curl.exe -s -i -X POST "$api/purchase-orders" -H "Authorization: Bearer $env:STANDARD_TOKEN"
curl.exe -s -i -X POST "$api/purchase-orders" -H "Authorization: Bearer $env:ADMIN_TOKEN"
```

**RF-GP-03 — el ADMIN aprueba y el permiso se aplica.** El STANDARD no puede aprobar (`403`); el ADMIN recibe `204`.

```bash
curl -s -i -X POST "$API/permission-requests/$REQUEST_ID/approve" -H "Authorization: Bearer $STANDARD_TOKEN"
curl -s -i -X POST "$API/permission-requests/$REQUEST_ID/approve" -H "Authorization: Bearer $ADMIN_TOKEN"
```

```powershell
curl.exe -s -i -X POST "$api/permission-requests/$env:REQUEST_ID/approve" -H "Authorization: Bearer $env:STANDARD_TOKEN"
curl.exe -s -i -X POST "$api/permission-requests/$env:REQUEST_ID/approve" -H "Authorization: Bearer $env:ADMIN_TOKEN"
```

Compruébalo con SQL de solo lectura (por ejemplo, en `psql`). Debe verse `status = APPLIED`; `decided_by` y
`applied_by` iguales al id del ADMIN; `decided_at` y `applied_at` con la fecha de la aprobación; y exactamente una
concesión cuyo origen es la solicitud.

```sql
SELECT status, requested_by, decided_by, decided_at, applied_by, applied_at, rejection_reason
FROM permission_requests WHERE id = '<REQUEST_ID>';
SELECT user_id, permission_code, source_request_id, granted_at
FROM user_permissions WHERE source_request_id = '<REQUEST_ID>';
```

**RF-GP-05 (después) — con el permiso aplicado, la orden se crea.** Respuesta `201 {"id":"..."}`. La orden queda en
`DRAFT`; se comprueba con `SELECT id, state FROM purchase_orders WHERE id = '<id>';`.

```bash
curl -s -i -X POST "$API/purchase-orders" -H "Authorization: Bearer $STANDARD_TOKEN"
```

```powershell
curl.exe -s -i -X POST "$api/purchase-orders" -H "Authorization: Bearer $env:STANDARD_TOKEN"
```

**RF-GP-06 — una solicitud resuelta no se vuelve a resolver.** Aprobar o rechazar de nuevo `REQUEST_ID` responde
`409`. Repite las dos consultas SQL anteriores: la fila no cambió y sigue habiendo una sola concesión.

```bash
curl -s -i -X POST "$API/permission-requests/$REQUEST_ID/approve" -H "Authorization: Bearer $ADMIN_TOKEN"
```

```powershell
curl.exe -s -i -X POST "$api/permission-requests/$env:REQUEST_ID/approve" -H "Authorization: Bearer $env:ADMIN_TOKEN"
```

**RF-GP-04 — rechazar exige un motivo.** El STANDARD crea una segunda solicitud con el mismo comando de RF-GP-01;
guarda su `id` como `SECOND_ID`. Si el ADMIN la rechaza sin motivo o con un motivo en blanco, recibe `400` y la
solicitud sigue `PENDING`. Con motivo recibe `204`, y el SQL muestra `status = REJECTED`, `decided_by`, `decided_at`
y `rejection_reason` tal como se escribió.

```bash
curl -s -i -X POST "$API/permission-requests/$SECOND_ID/reject" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"reason":"   "}'
curl -s -i -X POST "$API/permission-requests/$SECOND_ID/reject" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"reason":"No business justification"}'
```

```powershell
'{"reason":"   "}' | curl.exe -s -i -X POST "$api/permission-requests/$env:SECOND_ID/reject" `
  -H "Authorization: Bearer $env:ADMIN_TOKEN" -H "Content-Type: application/json" --data-binary '@-'
'{"reason":"No business justification"}' | curl.exe -s -i -X POST "$api/permission-requests/$env:SECOND_ID/reject" `
  -H "Authorization: Bearer $env:ADMIN_TOKEN" -H "Content-Type: application/json" --data-binary '@-'
```

**RF-GP-07 — nadie resuelve su propia solicitud.** Si el ADMIN intenta aprobar o rechazar `ADMIN_REQUEST_ID`, ambas
peticiones responden `409` y la fila sigue `PENDING`, sin concesión. Otro ADMIN sí podría resolverla (por ejemplo, un
usuario promovido con `PATCH /api/v1/admin/users/{id}/role`).

```bash
curl -s -i -X POST "$API/permission-requests/$ADMIN_REQUEST_ID/approve" -H "Authorization: Bearer $ADMIN_TOKEN"
curl -s -i -X POST "$API/permission-requests/$ADMIN_REQUEST_ID/reject" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"reason":"Self review"}'
```

```powershell
curl.exe -s -i -X POST "$api/permission-requests/$env:ADMIN_REQUEST_ID/approve" -H "Authorization: Bearer $env:ADMIN_TOKEN"
'{"reason":"Self review"}' | curl.exe -s -i -X POST "$api/permission-requests/$env:ADMIN_REQUEST_ID/reject" `
  -H "Authorization: Bearer $env:ADMIN_TOKEN" -H "Content-Type: application/json" --data-binary '@-'
```

Todas las comprobaciones usan IDs capturados y SQL de solo lectura: ningún criterio exige modificar tablas a mano.

### Pruebas automáticas de la Asignación 2

Se ejecutan con el comando único de la [sección 6](#6-pruebas) (requiere Docker):

```bash
./mvnw verify -Pintegration-test
```

```powershell
.\mvnw.cmd verify -Pintegration-test
```

Debe terminar con `BUILD SUCCESS`. Las pruebas de la asignación son:

- **Unitarias y MVC:** `PermissionRequestTest`, `PermissionRequestTransitionPolicyTest`, `PermissionApplicationTest`,
  `CreatePurchaseOrderTest`, `PermissionRequestWebTest` y `PurchaseOrderWebTest`.
- **Integración:** `PermissionPersistenceIT`, `PermissionManagementIT`, `PermissionResolutionConcurrencyIT` y
  `PermissionHttpIT`.

## Defectos

Defectos de la Práctica 2 (Variante B) sobre la Gestión de Permisos y la prueba existente que detecta cada uno. En la
versión sana todas estas pruebas pasan; con el defecto plantado, la prueba indicada falla. Se ejecutan con el comando
único de la [sección 6](#6-pruebas).

| Defecto | RF | Prueba que lo detecta | Qué comprueba |
|---|---|---|---|
| B1 — el rechazo acepta un motivo vacío (`""`) | RF-GP-04 | `PermissionApplicationTest.aRejectionWithoutAWrittenReasonChangesNothing` | Con motivo `null`, `""`, `" "` y `"\t\n "`, el rechazo lanza `InvalidPermissionRequestException` y la solicitud queda intacta (`PENDING`, sin `decidedBy`, `decidedAt` ni motivo) y sin guardarse. |
| B2 — se permite `APPROVED -> REJECTED` | RF-GP-06 | `PermissionRequestTest.rejectingAnApprovedRequestIsRejectedAndKeepsTheApproval`; `PermissionRequestTransitionPolicyTest.anApprovedRequestCannotBeRejected` | Rechazar una solicitud `APPROVED` lanza `InvalidPermissionRequestTransitionException` y conserva el estado y los datos de la aprobación; la política declara el par `APPROVED -> REJECTED` como no permitido. |
| B3 — un STANDARD puede aprobar | RF-GP-03 | `PermissionApplicationTest.standardDisabledOrUnknownActorsCannotResolve` | Un actor `STANDARD` (y uno deshabilitado o desconocido) que intenta aprobar o rechazar recibe `PermissionActorNotAuthorizedException`; la solicitud no cambia y no se concede el permiso. |
| B4 — se invierte la regla de auto-resolución | RF-GP-07 | `PermissionApplicationTest.anAdminCannotApproveOrRejectItsOwnRequest`; `PermissionApplicationTest.anotherAdminCanResolveAnAdminsRequest` | Un ADMIN que aprueba o rechaza su propia solicitud recibe `PermissionSelfResolutionException` y nada cambia; otro ADMIN sí la aprueba y queda `APPLIED` con la concesión para el solicitante. |
| B5 — se permite `APPLIED -> REJECTED` | RF-GP-06 | `PermissionApplicationTest.anAppliedRequestCannotBeRejectedAfterwards`; `PermissionRequestTest.anAppliedRequestCannotBeApprovedRejectedOrAppliedAgain` | Rechazar una solicitud `APPLIED` lanza `InvalidPermissionRequestTransitionException`; la solicitud sigue `APPLIED` con su decisión y aplicación, y la concesión se mantiene. |
| B6 — la aprobación no guarda `decidedBy` ni `decidedAt` | RF-GP-03 | `PermissionApplicationTest.anAdminApprovalAppliesThePermissionAndRecordsActorAndInstant` | Tras una aprobación válida, la solicitud guardada tiene `decidedBy` igual al ADMIN y `decidedAt` igual al instante de la aprobación, además de `APPLIED` y la concesión. |

Detectores adicionales en integración (PostgreSQL real), según la auditoría de la suite:
`PermissionManagementIT.aRejectionWithoutAWrittenReasonLeavesTheRowUntouched` y
`PermissionHttpIT.aRejectionNeedsAWrittenReasonAndKeepsItLiteral` (B1);
`PermissionManagementIT.aSecondApprovalOrALateRejectionChangesNoRowAndAddsNoGrant` (B5);
`PermissionManagementIT.resolutionAndGlobalListingFollowThePersistedRoleAndStatus` y
`PermissionHttpIT.unknownMalformedAndUnauthorizedResolutionsAreControlled` (B3);
`PermissionHttpIT.anAdminCannotResolveItsOwnRequestButAnotherAdminCan` (B4);
`PermissionManagementIT.approvalPersistsAppliedRequestAndExactlyOneGrantThatUnlocksPurchaseOrders` (B6).
