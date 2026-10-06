# Guía de verificación — Práctica 1

Pasos para verificar StockFlow desde un checkout limpio, sin consultar al autor. Cada paso indica el resultado o el
código HTTP esperado. La relación requisito → prueba está en [matriz-verificacion.md](matriz-verificacion.md).

**Convenciones**

- Los valores entre `<...>` son marcadores. Sustitúyalos por los suyos y no los guarde en archivos versionados:
  `<EMAIL>`, `<PASSWORD>`, `<NEW_PASSWORD>`, `<ADMIN_EMAIL>`, `<ADMIN_PASSWORD>`, `<TOKEN>`, `<JWT>`, `<ADMIN_JWT>`,
  `<USER_ID>`, `<DB_HOST>`, `<DB_NAME>`, `<DB_USER>`.
- Los ejemplos HTTP usan `curl` en Bash o Git Bash (Git Bash viene con Git para Windows). En PowerShell, escriba
  `curl.exe` y tenga en cuenta que las comillas del JSON cambian; es más cómodo usar Git Bash para estos pasos.
- `API=http://localhost:8080` (en Bash: `API=http://localhost:8080`).
- Las consultas SQL se ejecutan con `psql -h <DB_HOST> -U <DB_USER> -d <DB_NAME>` y solo leen datos, salvo el
  `INSERT` del paso 9.2, que falla a propósito y no guarda nada.
- Todo lo que llega por correo trae un enlace con la forma `.../activate#token=<TOKEN>` o
  `.../reset-password#token=<TOKEN>`. El token es lo que sigue a `#token=`. No lo comparta ni lo pegue en informes.

## 1. Preparación

### 1.1 Checkout limpio

```bash
git clone https://github.com/josemiguelm12/stockflow.git
cd stockflow
git status            # esperado: "nothing to commit, working tree clean"
```

### 1.2 Prerrequisitos

- `java -version` muestra 21 o superior.
- PostgreSQL en marcha, con una base de datos vacía `<DB_NAME>` y un usuario `<DB_USER>` con permiso para crear
  tablas.
- Docker en marcha, solo para el paso 2.2.
- Un servidor SMTP con credenciales propias, para el correo real del paso 8.2.

### 1.3 Variables de entorno

Los nombres y su propósito están en `.env.example` y en la sección 5 del README. Spring Boot no lee archivos `.env`:
defina las variables en la terminal donde arrancará la aplicación. Genere la clave del outbox y el secreto JWT
**por separado** (README §5).

PowerShell:

```powershell
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://<DB_HOST>:5432/<DB_NAME>"
$env:SPRING_DATASOURCE_USERNAME = "<DB_USER>"
$env:SPRING_DATASOURCE_PASSWORD = "<DB_PASSWORD>"
$env:STOCKFLOW_CORS_ALLOWED_ORIGINS = "http://localhost:4200"
$env:STOCKFLOW_OUTBOX_ENCRYPTION_KEY = "<BASE64_32_BYTES_A>"
$env:STOCKFLOW_OUTBOX_ENCRYPTION_KEY_ID = "<KEY_ID>"
$env:STOCKFLOW_JWT_SECRET = "<BASE64_32_BYTES_B>"
$env:STOCKFLOW_PUBLIC_ACTIVATION_URL = "http://localhost:8080/activate"
$env:STOCKFLOW_ACTIVATION_TOKEN_TTL = "PT24H"
$env:STOCKFLOW_PUBLIC_PASSWORD_RESET_URL = "http://localhost:8080/reset-password"
```

Bash / Git Bash:

```bash
export SPRING_DATASOURCE_URL="jdbc:postgresql://<DB_HOST>:5432/<DB_NAME>"
export SPRING_DATASOURCE_USERNAME="<DB_USER>"
export SPRING_DATASOURCE_PASSWORD="<DB_PASSWORD>"
export STOCKFLOW_CORS_ALLOWED_ORIGINS="http://localhost:4200"
export STOCKFLOW_OUTBOX_ENCRYPTION_KEY="<BASE64_32_BYTES_A>"
export STOCKFLOW_OUTBOX_ENCRYPTION_KEY_ID="<KEY_ID>"
export STOCKFLOW_JWT_SECRET="<BASE64_32_BYTES_B>"
export STOCKFLOW_PUBLIC_ACTIVATION_URL="http://localhost:8080/activate"
export STOCKFLOW_ACTIVATION_TOKEN_TTL="PT24H"
export STOCKFLOW_PUBLIC_PASSWORD_RESET_URL="http://localhost:8080/reset-password"
```

Las variables `STOCKFLOW_SMTP_*` solo hacen falta en la terminal del worker (paso 8). Los límites de rate limiting
tienen valores por defecto.

### 1.4 Arranque de la API

```powershell
.\mvnw.cmd spring-boot:run
```

```bash
./mvnw spring-boot:run
```

**Esperado:** en el log aparece `Migrating schema "public" to version "1 - identity foundation"` y después
`"2 - purchase order state machine"` (solo la primera vez), y la API queda escuchando en el puerto 8080. Si falta una
variable obligatoria, el arranque falla con un error que nombra la propiedad, sin mostrar valores secretos. Para
detener la API, pulse `Ctrl + C` en esa terminal.

## 2. Pruebas automáticas

### 2.1 Unitarias y web (no necesitan base de datos)

```powershell
.\mvnw.cmd test
```

```bash
./mvnw test
```

**Esperado:** `BUILD SUCCESS` y `Tests run: 143, Failures: 0, Errors: 0`.

### 2.2 Integración con PostgreSQL real (requiere Docker)

```powershell
.\mvnw.cmd verify -Pintegration-test
```

```bash
./mvnw verify -Pintegration-test
```

**Esperado:** `BUILD SUCCESS`, con 143 pruebas unitarias/web y 103 de integración, sin fallos. Testcontainers
levanta su propio PostgreSQL, así que no usa la base de datos del paso 1.

## 3. Health, Flyway y persistencia

### 3.1 Health y migraciones

```bash
curl -i $API/api/health
```

**Esperado:** `200` y `{"status":"UP","application":"StockFlow"}` (el orden de los campos puede variar).

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
```

**Esperado:** dos filas: `1 identity foundation true` y `2 purchase order state machine true`.

### 3.2 Persistencia tras reinicio (RD-09)

Hágalo después de tener un usuario activo (paso 4.3).

1. Detenga la API con `Ctrl + C` y vuelva a arrancarla (paso 1.4).
2. **Esperado en el log:** Flyway indica que el esquema ya está al día y no aplica ninguna migración.
3. Inicie sesión con ese usuario (paso 5.1). **Esperado:** `200`. El usuario y su estado siguen en la base de
   datos.

## 4. Registro y activación

### 4.1 Registro y validaciones

```bash
curl -i -X POST $API/api/v1/auth/register -H "Content-Type: application/json" \
  -d '{"email":"<EMAIL>","password":"<PASSWORD>"}'
```

**Esperado:** `201` con `{"id","email","accountStatus":"PENDING_ACTIVATION"}`, sin hash, token ni contraseña.

- Repetir el registro con el mismo correo en mayúsculas o con espacios: **`409`**.
- Una contraseña sin números o de menos de 8 caracteres (p. ej. `abcdefgh`): **`400`** `ProblemDetail`, sin eco del
  valor enviado y sin trazas ni SQL.
- Un correo mal formado o un JSON inválido: **`400`**.

### 4.2 Cuenta pendiente

```bash
curl -i -X POST $API/api/v1/auth/login -H "Content-Type: application/json" \
  -d '{"email":"<EMAIL>","password":"<PASSWORD>"}'
```

**Esperado:** `403` con `"The account is not active."`. No se crea sesión.

```sql
SELECT account_status, left(password_hash, 4) AS prefijo FROM users WHERE email_normalized = lower('<EMAIL>');
SELECT template_key, state FROM outbound_emails ORDER BY enqueued_at DESC LIMIT 1;
```

**Esperado:** `PENDING_ACTIVATION` y un prefijo BCrypt (`$2a$` o `$2b$`), nunca la contraseña. Hay un correo de
activación en estado `PENDING`. Su contenido está cifrado: el token solo existe en el correo.

### 4.3 Activación de un solo uso

Ejecute el worker (paso 8.2) para recibir el correo, abra el enlace y pulse **Activar cuenta**. Si lo prefiere,
puede hacerlo por API:

```bash
curl -i -X POST $API/api/v1/auth/activate -H "Content-Type: application/json" -d '{"token":"<TOKEN>"}'
```

**Esperado:** la primera vez, `204` (en la landing: "Cuenta activada"), y el login del paso 4.2 pasa a `200`. Repetir
con el mismo token: **`400`** ("invalid, expired or already used"), sin cambiar la cuenta.

### 4.4 Expiración

1. Detenga la API, defina `STOCKFLOW_ACTIVATION_TOKEN_TTL=PT1M` y vuelva a arrancarla.
2. Registre otro correo y ejecute el worker para recibir el enlace.
3. Espere más de un minuto y active con ese token.

**Esperado:** `400`, y la cuenta sigue `PENDING_ACTIVATION`. Al terminar, vuelva a poner `PT24H` y reinicie la API.

### 4.5 Reenvío

```bash
curl -i -X POST $API/api/v1/auth/resend-activation -H "Content-Type: application/json" -d '{"email":"<EMAIL>"}'
curl -i -X POST $API/api/v1/auth/resend-activation -H "Content-Type: application/json" -d '{"email":"<UNKNOWN_EMAIL>"}'
```

**Esperado:** las dos respuestas son `202` con el mismo cuerpo. Para una cuenta pendiente se encola un correo nuevo,
y el token anterior deja de servir: activar con él da `400` y con el nuevo `204`. Una cuenta ya activa o un correo
desconocido no generan correo.

## 5. Sesión

### 5.1 Login

```bash
curl -i -X POST $API/api/v1/auth/login -H "Content-Type: application/json" \
  -d '{"email":"<EMAIL>","password":"<PASSWORD>"}'
```

**Esperado:** `200 {"accessToken":"<JWT>","tokenType":"Bearer","expiresIn":900}`. Con una contraseña incorrecta o
un correo desconocido, la respuesta es **`401`** con el mismo cuerpo exacto (`"Invalid credentials."`) en los dos
casos.

### 5.2 Usuario autenticado

```bash
curl -i $API/api/v1/auth/me -H "Authorization: Bearer <JWT>"
curl -i $API/api/v1/auth/me
```

**Esperado:** `200 {"id","email","role":"STANDARD"}` con el Bearer, y **`401`** sin él o con un token alterado.

### 5.3 Logout

```bash
curl -i -X POST $API/api/v1/auth/logout -H "Authorization: Bearer <JWT>"
curl -i $API/api/v1/auth/me -H "Authorization: Bearer <JWT>"
```

**Esperado:** `204` y después **`401`**: el mismo token ya no sirve.

### 5.4 Bloqueo tras cinco fallos

1. Envíe 5 logins con una contraseña incorrecta para `<EMAIL>`. **Esperado:** `401` en los cinco.
2. Sexto intento con la contraseña **correcta**. **Esperado:** `401` (cuenta bloqueada) y ninguna sesión nueva.
3. Pasados 15 minutos, login correcto. **Esperado:** `200`, y el contador vuelve a 0.

Consulta del estado del bloqueo:

```sql
SELECT failed_login_attempts, locked_until FROM users WHERE email_normalized = lower('<EMAIL>');
```

El login admite 10 solicitudes por minuto y por IP. Si se supera, la respuesta es `429` con `Retry-After`, que no
cuenta como intento de login.

## 6. Contraseñas

### 6.1 Solicitud uniforme

```bash
curl -i -X POST $API/api/v1/auth/password/forgot -H "Content-Type: application/json" -d '{"email":"<EMAIL>"}'
curl -i -X POST $API/api/v1/auth/password/forgot -H "Content-Type: application/json" -d '{"email":"<UNKNOWN_EMAIL>"}'
```

**Esperado:** `202` con el mismo cuerpo en los dos casos. Solo la cuenta `ACTIVE` recibe un correo
(`outbound_emails` en `PENDING`).

### 6.2 Reset con el código

Abra la sesión con `<JWT>` (paso 5.1) **antes** del reset. Ejecute el worker, abra el enlace recibido y envíe el
formulario con `<NEW_PASSWORD>`. También puede hacerlo por API:

```bash
curl -i -X POST $API/api/v1/auth/password/reset -H "Content-Type: application/json" \
  -d '{"token":"<TOKEN>","newPassword":"<NEW_PASSWORD>"}'
```

**Esperado:**

- El reset responde `204`.
- Login con `<PASSWORD>`: **`401`**. Login con `<NEW_PASSWORD>`: `200`.
- `GET /api/v1/auth/me` con el `<JWT>` anterior: **`401`** (todas las sesiones se revocaron).

### 6.3 Código usado o vencido

- Repetir el reset con el mismo `<TOKEN>`: **`400`** genérico, y la contraseña no cambia.
- Vencido: arranque la API con `STOCKFLOW_PASSWORD_RESET_TOKEN_TTL=PT1M`, pida un código y espere más de un minuto.
  El reset responde **`400`**. Al terminar, quite la variable (vacía = `PT30M`).
- Una contraseña nueva débil con un token válido: **`400`**, y el token sigue sin consumirse.

### 6.4 Cambio autenticado

```bash
curl -i -X POST $API/api/v1/auth/password/change -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" -d '{"currentPassword":"<PASSWORD>","newPassword":"<NEW_PASSWORD>"}'
```

**Esperado:**

- `204`, y el mismo `<JWT>` pasa a dar **`401`**: hay que iniciar sesión de nuevo.
- Con una contraseña actual incorrecta: **`400`** genérico, sin cambios.
- Sin Bearer: **`401`**.

## 7. Administración

### 7.1 Primer ADMIN (bootstrap)

En **otra** terminal, con solo `SPRING_DATASOURCE_*` definidas:

```powershell
$env:STOCKFLOW_ADMIN_BOOTSTRAP_ENABLED = "true"
$env:STOCKFLOW_ADMIN_BOOTSTRAP_EMAIL = "<ADMIN_EMAIL>"
$env:STOCKFLOW_ADMIN_BOOTSTRAP_PASSWORD = "<ADMIN_PASSWORD>"
$env:SPRING_MAIN_WEB_APPLICATION_TYPE = "none"
.\mvnw.cmd spring-boot:run
```

```bash
STOCKFLOW_ADMIN_BOOTSTRAP_ENABLED=true STOCKFLOW_ADMIN_BOOTSTRAP_EMAIL="<ADMIN_EMAIL>" \
STOCKFLOW_ADMIN_BOOTSTRAP_PASSWORD="<ADMIN_PASSWORD>" SPRING_MAIN_WEB_APPLICATION_TYPE=none ./mvnw spring-boot:run
```

**Esperado:** el proceso termina con `Admin bootstrap finished: created`, sin mostrar el correo ni la contraseña.
Repetirlo con el mismo correo da `already-present`; con otro correo falla porque ya existe un ADMIN. Después, cierre
esa terminal o deje `STOCKFLOW_ADMIN_BOOTSTRAP_ENABLED` vacío y borre la contraseña del entorno.

Inicie sesión como ADMIN (paso 5.1) para obtener `<ADMIN_JWT>`. Use también un usuario STANDARD activo con su
`<JWT>`.

### 7.2 Autorización con requests manuales

```bash
curl -i $API/api/v1/admin/users
curl -i $API/api/v1/admin/users -H "Authorization: Bearer <JWT>"
curl -i -X PATCH $API/api/v1/admin/users/<USER_ID>/role -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" -d '{"role":"ADMIN"}'
```

**Esperado:** **`401`** sin Bearer y **`403`** con el Bearer de un STANDARD, incluido el intento de hacerse ADMIN.
El rol no cambia. Lo mismo ocurre con `status` y `force-password-reset`.

### 7.3 Listado

```bash
curl -i "$API/api/v1/admin/users?page=0&size=20" -H "Authorization: Bearer <ADMIN_JWT>"
```

**Esperado:** `200` con `items` (`id`, `email`, `role`, `accountStatus`, `passwordResetRequired`, `createdAt`,
`updatedAt`) y `page`, `size`, `totalElements`, `totalPages`. Nunca incluye el hash ni tokens. Con `page=-1` o
`size=101`, la respuesta es **`400`**. Anote el `id` del usuario STANDARD como `<USER_ID>`.

### 7.4 Rol

```bash
curl -i -X PATCH $API/api/v1/admin/users/<USER_ID>/role -H "Authorization: Bearer <ADMIN_JWT>" \
  -H "Content-Type: application/json" -d '{"role":"ADMIN"}'
```

**Esperado:**

- `204`. Con el **mismo** `<JWT>` del usuario, `GET /api/v1/admin/users` pasa de `403` a `200`. Al devolverle
  `"STANDARD"`, vuelve a `403`.
- `{"role":"SUPERADMIN"}`: **`400`**.
- Que el ADMIN cambie su propio rol: **`409`**.
- Un cuerpo con propiedades extra (p. ej. `"userId"`): **`400`**.

### 7.5 Estado

```bash
curl -i -X PATCH $API/api/v1/admin/users/<USER_ID>/status -H "Authorization: Bearer <ADMIN_JWT>" \
  -H "Content-Type: application/json" -d '{"accountStatus":"DISABLED"}'
```

**Esperado:**

- `204`. El `<JWT>` del usuario pasa a dar **`401`**, y su login, **`403`**.
- Reactivar con `"ACTIVE"`: `204`. El token anterior sigue sin servir y el login vuelve a dar `200`.
- Que el ADMIN se desactive a sí mismo: **`409`**.

### 7.6 Reset forzado

```bash
curl -i -X POST $API/api/v1/admin/users/<USER_ID>/force-password-reset -H "Authorization: Bearer <ADMIN_JWT>"
```

**Esperado:**

- `202` sin cuerpo.
- Las sesiones del usuario se revocan: su `<JWT>` da `401`.
- Login con la contraseña anterior: **`403`** `Password reset required`.
- Se encola un correo de recuperación `PENDING`. Al completar el reset con ese enlace (paso 6.2), el login con la
  contraseña nueva da `200`.
- Enviar cualquier cuerpo (p. ej. `{}`): **`400`**, sin efectos.

## 8. Correo: outbox y worker

El worker es un proceso aparte: envía los correos `PENDING`, los marca `SENT` y termina. Se ejecuta en otra terminal
con las variables del paso 1.3 (no necesita `STOCKFLOW_JWT_SECRET`) más las `STOCKFLOW_SMTP_*`.

### 8.1 SMTP apagado

1. Con la API en marcha, registre un correo nuevo o pida un `forgot`. **Esperado:** `201` o `202`, igual que
   siempre.
2. Ejecute el worker con `STOCKFLOW_SMTP_HOST=localhost` y un `STOCKFLOW_SMTP_PORT` en el que no escuche nada.

```powershell
$env:STOCKFLOW_WORKER_ENABLED = "true"
$env:SPRING_MAIN_WEB_APPLICATION_TYPE = "none"
.\mvnw.cmd spring-boot:run
```

```bash
STOCKFLOW_WORKER_ENABLED=true SPRING_MAIN_WEB_APPLICATION_TYPE=none ./mvnw spring-boot:run
```

**Esperado:** el log muestra `Outbox worker finished: sent=0, failed=1`, y el correo sigue `PENDING`:

```sql
SELECT template_key, state, sent_at FROM outbound_emails ORDER BY enqueued_at DESC LIMIT 5;
```

### 8.2 Worker con SMTP real

Defina `STOCKFLOW_SMTP_HOST`, `STOCKFLOW_SMTP_PORT`, `STOCKFLOW_SMTP_USERNAME`, `STOCKFLOW_SMTP_PASSWORD` y
`STOCKFLOW_SMTP_FROM` con sus credenciales, y ejecute el mismo comando.

**Esperado:**

- El log muestra `sent=N, failed=0`.
- Los correos pasan a `SENT`, con `sent_at`, y su contenido cifrado se borra.
- El mensaje llega al buzón real y su enlace funciona (pasos 4.3 y 6.2).
- Ni el log ni la consola muestran tokens, enlaces ni contraseñas.

### 8.3 Segunda ejecución

Vuelva a ejecutar el worker sin crear correos nuevos.

**Esperado:** `sent=0, failed=0`. Ningún correo `SENT` se reenvía.

## 9. Máquina de estados (V2)

La Orden de Compra no tiene endpoints en la Práctica 1. La verificación se hace con el documento, las pruebas y la
base de datos.

### 9.1 Documento y pruebas

- Lea [docs/maquina-de-estados.md](../maquina-de-estados.md). Contiene 5 estados, 2 terminales (`RECEIVED` y
  `CANCELLED`), la tabla Desde / Hacia / Quién ejecuta / Condición y las transiciones prohibidas, como
  `SUBMITTED → RECEIVED`.
- Ejecute las pruebas de la máquina de estados:

```powershell
.\mvnw.cmd test "-Dtest=PurchaseOrder*Test"
```

```bash
./mvnw test -Dtest='PurchaseOrder*Test'
```

**Esperado:** `Tests run: 44, Failures: 0`.

### 9.2 Tabla y restricción en PostgreSQL

```sql
\d purchase_orders
INSERT INTO purchase_orders (id, supplier_id, state, created_at)
VALUES (gen_random_uuid(), NULL, 'SHIPPED', now());
```

**Esperado:** la tabla tiene `id`, `supplier_id` (nullable), `state` y `created_at`, con la restricción
`ck_purchase_orders_state`. El `INSERT` falla con `violates check constraint "ck_purchase_orders_state"` y no guarda
nada. La prueba `PurchaseOrderPersistenceIT` (paso 2.2) cubre el guardado y la lectura con PostgreSQL real.

## 10. Archivos versionados y secretos

```bash
git ls-files | grep -E '(^|/)\.env$|(^|/)target/|\.log$'
git ls-files .env.example
git grep -nE '^[A-Z_]+=[^[:space:]]' -- .env.example
git grep -nE '(PASSWORD|SECRET|KEY)=[^$ ]' -- src/main
```

**Esperado:**

- La primera orden no muestra nada: no hay `.env`, `target/` ni logs versionados.
- La segunda muestra `.env.example`.
- La tercera no muestra nada: todas las variables de `.env.example` están vacías.
- La cuarta no muestra nada: `src/main` no contiene secretos. Las claves de `src/test` son solo de prueba y no sirven
  en ningún entorno real.

## 11. Verificación final desde el tag

Cuando exista el tag `practica-1` (se crea después de fusionar la documentación final; ver README §13):

```bash
git clone https://github.com/josemiguelm12/stockflow.git stockflow-practica-1
cd stockflow-practica-1
git checkout practica-1
git log -1 --oneline        # esperado: el merge de la documentación final en main
```

Repita los pasos 1 a 10 sobre ese checkout. **Esperado:** los mismos resultados.
