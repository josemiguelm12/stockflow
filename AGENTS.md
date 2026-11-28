# StockFlow — Guía de ingeniería para agentes

## Propósito y stack

StockFlow es una API para gestión de inventario, órdenes de compra, identidad y solicitudes de permisos. El stack es Java 21, Spring Boot 4.1, Maven Wrapper, PostgreSQL 16, JDBC y Flyway; Docker Compose ofrece servicios locales y las pruebas de integración usan Testcontainers.

## Estructura

El código está bajo `src/main/java/com/stockflow` como monolito modular:

- `identity`: cuentas, autenticación, sesiones y administración.
- `permission`: solicitudes y concesiones de permisos.
- `inventory`: órdenes de compra y sus transiciones.
- `notification`: outbox de correo, plantillas y worker SMTP.
- `document`: documentos y extracción financiera opcional.
- `shared`: configuración, seguridad y componentes compartidos.

Los módulos organizan responsabilidades en `domain`, `application` y `adapter/in` o `adapter/out`. Las migraciones viven en `src/main/resources/db/migration`.

## Comandos

En Windows PowerShell:

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify -Pintegration-test
.\mvnw.cmd spring-boot:run
docker compose up --build
```

`test` ejecuta pruebas unitarias y MVC. `verify -Pintegration-test` también ejecuta las pruebas de integración y requiere Docker. La API requiere PostgreSQL y las variables de entorno documentadas en `README.md`/`.env.example`; la API normal no necesita SMTP. No imprimir valores de variables al consultar configuración.

## Arquitectura y responsabilidades

- Mantener Clean Architecture y SOLID, favoreciendo dependencias hacia dominio y aplicación.
- Los controladores HTTP son delgados: extraen el actor autenticado, validan el DTO, invocan un caso de uso y traducen su resultado a HTTP.
- Mantener reglas de negocio en dominio/casos de uso y persistencia/transporte en adaptadores.
- Usar DTOs explícitos para entrada y salida; validar entradas en el límite HTTP y mantener invariantes en dominio. No aceptar del cliente actor, rol, estado o fechas que el servidor debe determinar.
- Usar JDBC con parámetros; no concatenar entradas en SQL.
- Revalidar en el servidor autenticación, autorización y propiedad de recursos. Mantener seguridad deny-by-default y errores sin detalles internos.
- No duplicar infraestructura existente sin necesidad. Reutilizar el outbox y conservar el worker como proceso independiente.

## Compatibilidad y alcance

Las funcionalidades de P1 a P4 son contratos existentes y deben seguir funcionando: autenticación por sesión Bearer, roles `ADMIN`/`STANDARD`, gestión de permisos, estados de órdenes, documentos y el outbox de correo. Las nuevas funciones no deben cambiar rutas, permisos, estados ni respuestas existentes salvo que un requisito explícito lo autorice. No corregir ni ampliar Mindee como parte de P5.

Para P5, las notificaciones internas son obligatorias; el correo funcional está habilitado por defecto y su preferencia no desactiva activación ni recuperación de contraseña. Reutilizar el outbox/worker, conservar `FOR UPDATE SKIP LOCKED` y limitar a tres intentos con estado terminal `FAILED`. Propietario de orden nueva = actor autenticado creador. Las marcas de envío/aprobación y el propietario se añaden mediante migración nueva; no rellenar datos históricos inventados ni atribuir órdenes antiguas a usuarios.

## Secretos

Está prohibido leer `.env`, imprimir, copiar, almacenar o versionar credenciales, contraseñas, tokens, claves o cadenas de conexión. Consultar solo nombres y propósitos en `.env.example` cuando haga falta. SMTP y demás secretos se configuran exclusivamente mediante variables de entorno o mecanismos seguros equivalentes; nunca en código, fixtures, documentación con valores reales, argumentos registrados ni historial Git.

## Flyway y persistencia

Las migraciones aplicadas son inmutables. Todo cambio de esquema se añade en una nueva migración Flyway versionada, con restricciones e índices adecuados y pruebas de integración de persistencia. No editar migraciones existentes, no depender de `ddl-auto` para cambios de producción y no ejecutar operaciones destructivas sobre bases compartidas. Documentar la compatibilidad y recuperación operativa de cambios persistentes.

## Pruebas y documentación

Añadir pruebas de dominio/aplicación para reglas y fallos, pruebas HTTP para contratos, autenticación, autorización y validación, y pruebas PostgreSQL/Testcontainers para persistencia, concurrencia y aislamiento. No afirmar que se ejecutaron pruebas sin evidencia. Actualizar README y documentación de API/configuración cuando cambien comportamientos observables, límites, worker o variables; nunca documentar secretos reales.
