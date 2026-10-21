# Bitácora — Asignación 2 de StockFlow

Esta bitácora recoge únicamente hechos respaldados por los reportes de implementación y verificación disponibles. No implica que A2-T05 o la entrega final estén aprobadas.

## Delegación A2-T04 — HTTP, seguridad y README

- **Pedido a Claude Code:** implementar el contrato A2-T04 para exponer los casos de uso de Gestión de Permisos mediante HTTP, aplicar reglas server-side, proteger `POST /api/v1/purchase-orders`, añadir pruebas MVC e integración HTTP y documentar en README una demostración RF-GP-01…RF-GP-07. Se excluyeron cambios de dominio, aplicación, persistencia, migraciones, Angular, commit y push.
- **Resultado informado por Claude Code:** controladores/DTOs HTTP de permisos y órdenes, cambios de `SecurityConfig` y `ApiExceptionHandler`, pruebas MVC e `PermissionHttpIT`, y sección 14 del README. El informe lista 11 archivos creados y 3 modificados; la revisión de Codex verificó que esos cambios correspondían al alcance A2-T04.
- **Límite de evidencia:** Claude dejó el smoke manual del README como `NOT_RUN` porque el proceso de aplicación no arrancó por falta de memoria. Las pruebas automatizadas no sustituyen esa comprobación manual.

## Error real detectado y corrección

- **Error:** en la primera ejecución dirigida de `PermissionRequestWebTest` y `PurchaseOrderWebTest`, dos pruebas fallaron por una aserción escrita por el implementador que esperaba que el cuerpo del `ProblemDetail` no contuviera la ruta. Spring incluye la ruta solicitada en `ProblemDetail.instance`, de acuerdo con el comportamiento existente de la API.
- **Detección:** el resultado de la primera ejecución fue `BUILD FAILURE`, con 18 pruebas ejecutadas y 2 fallos de aserción. El informe identifica la expectativa incorrecta como causa; no reporta un fallo de autorización o de respuesta del producto.
- **Corrección:** Claude ajustó las aserciones para excluir `instance` al comprobar que el resto del cuerpo no filtrara datos sensibles.
- **Evidencia posterior:** el informe de implementación registra la segunda ejecución dirigida como `BUILD SUCCESS` (18/18). Codex volvió a ejecutar independientemente `PermissionRequestWebTest` y `PurchaseOrderWebTest` durante la revisión A2-T04 y obtuvo 18/18; también obtuvo `BUILD SUCCESS` en `verify -Pintegration-test` (256 pruebas Surefire y 168 Failsafe, sin fallos ni errores). La revisión está registrada en `.agent/verification_report.md`.
- **Clasificación:** error de prueba/expectativa del implementador, corregido antes de integrar; no se presenta como defecto funcional de la API.

## Nota de trazabilidad A2-T01

La verificación histórica de A2-T01 registra que el contrato v1 omitió el motivo de rechazo requerido por RF-GP-04 y que se emitió v2. Sin embargo, el `implementation_report.md` vigente corresponde a A2-T04 y no contiene corroboración explícita de ese episodio. Por esa razón, esta bitácora no lo usa como el error documentado requerido ni atribuye detalles históricos adicionales.

## Smoke A2-T05 — sección 14 del README

Se reintentó con recursos desechables locales (PostgreSQL 16 en Docker, base `stockflow` y SMTP Mailpit); no se usaron credenciales ni servicios externos. La aplicación aplicó V1–V3 y el comando de bootstrap de prueba terminó `BUILD SUCCESS` con `Admin bootstrap finished: created`.

| Paso/comando literal o prerrequisito | Resultado | Evidencia |
|---|---|---|
| Sección 10: bootstrap del ADMIN de prueba con variables de la sesión y `spring-boot:run` en Git Bash | PASS | PostgreSQL 16.15, Flyway aplicó 3 migraciones; bootstrap informó `created`; salida depurada sin credenciales. |
| Sección 3: arranque PowerShell `.\mvnw.cmd spring-boot:run` | ENVIRONMENT_LIMITATION | Wrapper y aplicación iniciaron; al comenzar Tomcat falló dos veces con `Unable to establish loopback connection`, causa `java.net.SocketException: Invalid argument: connect` en `PipeImpl`/`UnixDomainSockets`. No fue falta de memoria ni fallo funcional reproducido. |
| Sección 4: `curl.exe http://localhost:8080/api/health` | ENVIRONMENT_LIMITATION | No pudo conectar a localhost:8080 porque la app no quedó escuchando tras el fallo de Tomcat. |
| Sección 14: login, registro/activación del STANDARD, captura de tokens/IDs, RF-GP-01…07 y SQL de lectura | ENVIRONMENT_LIMITATION | No ejecutados: dependen del HTTP server no disponible. No se declaran PASS ni se corrigieron comandos. |

La cobertura equivalente del comportamiento de Gestión de Permisos está en `PermissionHttpIT` (7/7 en la suite de integración): inicia usuarios persistidos y obtiene Bearers mediante login real, ejercita creación/listado/approve/reject/grant y orden protegida, errores controlados, auto-resolución y estado intacto. `PermissionManagementIT` (11/11), `PermissionPersistenceIT` (44/44) y `PermissionResolutionConcurrencyIT` (3/3) cubren aplicación, persistencia/rollback y concurrencia. La verificación independiente registró `verify -Pintegration-test` con 168/168, cero fallos, errores u omitidas.

## Estado de A2-T05

La rama `feature/a2-finalization` se creó desde `bab3fa4`. El Contract Owner aclaró que `x_plan.md` es planificación interna y su ausencia está **RESOLVED / NON-BLOCKING**; no se reconstruyó. El smoke HTTP literal quedó impedido únicamente por el error loopback objetivo del entorno Windows/JDK; el flujo equivalente sí está cubierto por integración. La bitácora no afirma que los comandos HTTP del README se hayan ejecutado ni que el smoke manual haya pasado.
