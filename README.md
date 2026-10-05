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
| `STOCKFLOW_OUTBOX_ENCRYPTION_KEY` | Base64 de 32 bytes; se usará en una tarea posterior |
| `STOCKFLOW_OUTBOX_ENCRYPTION_KEY_ID` | Identificador de esa clave; se usará en una tarea posterior |

Sin `SPRING_DATASOURCE_*` ni `STOCKFLOW_CORS_ALLOWED_ORIGINS` la aplicación no arranca.

Seguridad HTTP: solo `GET /api/health` es público; cualquier otra ruta se deniega y, sin
credenciales, responde 401. Esta etapa es solo la base técnica: aún no existen registro,
login ni otros flujos de acceso.

## 6. Pruebas

`./mvnw test` (o `.\mvnw.cmd test`) ejecuta las pruebas unitarias y MVC, que no necesitan
base de datos. Las pruebas de integración usan Testcontainers con PostgreSQL real y requieren
Docker en ejecución:

```bash
./mvnw verify -Pintegration-test
```
