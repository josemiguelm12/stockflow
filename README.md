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

Sin las variables `SPRING_DATASOURCE_*`, `STOCKFLOW_CORS_ALLOWED_ORIGINS`, `STOCKFLOW_PUBLIC_ACTIVATION_URL`,
`STOCKFLOW_ACTIVATION_TOKEN_TTL`, `STOCKFLOW_JWT_SECRET` y las dos del outbox, la aplicación no arranca. Una clave del outbox o un
secreto JWT que no sea Base64 de 32 bytes también impide el arranque. El worker de correo no necesita
`STOCKFLOW_JWT_SECRET`. La API arranca sin SMTP; solo el worker lo exige.

Para generar la clave del outbox y el secreto JWT (cada uno distinto):

```powershell
[Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

```bash
openssl rand -base64 32
```

Seguridad HTTP: solo `GET /api/health`, `GET /activate`, los tres `POST /api/v1/auth/*` de la sección 7 y
`POST /api/v1/auth/login` son públicos; `GET /api/v1/auth/me` y `POST /api/v1/auth/logout` exigen Bearer;
cualquier otra ruta se deniega (401 sin credenciales, 403 con ellas).

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
  120 por minuto y `POST /api/v1/auth/login` 10 por minuto adicionales. Al superarlo responde `429` con
  `Retry-After`. `X-Forwarded-For` se ignora a propósito (es falsificable). `/api/health` y `/activate` quedan fuera.
  **Limitación:** el estado vive en memoria de un solo proceso; con varias instancias o detrás de un
  proxy que oculte la IP real haría falta un almacén distribuido o configurar la IP del cliente, fuera de alcance.
  El estado está acotado a 50 000 IPs: si se llena (p. ej. una inundación de IPs distintas), se descartan las
  ventanas vencidas y, si no basta, las IPs *nuevas* reciben `429` hasta que venza la ventana; los límites de las IPs
  ya registradas nunca se reinician.
