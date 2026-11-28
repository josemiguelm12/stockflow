# Práctica 5 — Criterios de aceptación: notificaciones y reportes

**Estado:** decisiones F0 cerradas para revisión. Cada criterio es verificable por prueba HTTP/integración y consulta controlada de persistencia; la inspección SQL no debe mostrar secretos ni datos de otros usuarios.

## Convenciones temporales y de seguridad

- Los reportes usan fechas UTC e intervalos `[desde, hasta)`, máximo 366 días; sin intervalo explícito usan los últimos 30 días hasta ahora. Intervalos mal formados, invertidos o mayores de 366 días producen HTTP `400`.
- La solicitud/decisión, notificación interna y correo funcional encolado se confirman en una misma transacción con el evento de negocio. El proceso web no contacta SMTP. Fallos posteriores de transporte no revierten la notificación interna.
- La cola admite tres intentos totales: primero en la primera reclamación; segundo no antes de un minuto tras el primer fallo; tercero no antes de cinco minutos adicionales tras el segundo fallo. Tras el tercer fallo queda `FAILED` definitivo.
- Un correo SMTP pudo ser aceptado aunque el proceso caiga antes de persistir el resultado. Con SMTP estándar no se garantiza entrega exactamente una vez en esa ventana; se evitan duplicados cuando el resultado está registrado.
- El worker conserva el lote máximo de 50 y `FOR UPDATE SKIP LOCKED`; termina tras el lote y puede ejecutarse de nuevo.
- El actor, usuario destinatario y rol se derivan de identidad/sesión persistida. Las operaciones sobre recursos propios filtran en la consulta por el usuario autenticado y responden `404` para recurso ajeno o inexistente, evitando confirmar la existencia de datos ajenos. Operaciones sin sesión válida: `401`; rol insuficiente: `403`.
- Los filtros inválidos, fechas invertidas, paginación no válida, enum desconocido y cuerpos incompletos se rechazan con `400`; ningún error devuelve SQL, stack trace ni secreto.

## Motor de notificaciones

### RF-NOT-01 — Aviso a administradores activos

**Dado** un usuario autenticado que puede solicitar un permiso, y ADMIN activos e inactivos y cuentas STANDARD  
**Cuando** crea una solicitud válida  
**Entonces** se crea una notificación interna obligatoria y correo para cada ADMIN activo con preferencia funcional habilitada; ningún STANDARD, ADMIN inactivo ni ADMIN repetido recibe entrega. El correo funcional está habilitado por defecto; preferencia apagada suprime el correo, nunca la notificación interna.  
**Verificación:** integración con ADMIN activos/inactivos y STANDARD; comprobar registros internos y outbox, incluidos valores de preferencia por defecto y apagada, y que no hay destinatarios ajenos/duplicados.

### RF-NOT-02 — Decisión de solicitud

**Dado** una solicitud PENDING, un solicitante autenticado y sus preferencias funcionales  
**Cuando** un ADMIN la aprueba o la rechaza con motivo no blanco  
**Entonces** se crea notificación interna únicamente para el solicitante y se encola correo si su preferencia está habilitada (por defecto sí); el rechazo incluye el motivo literal. Rechazo inválido, decisión repetida o actor no autorizado no genera aviso. Preferencias funcionales no afectan activación ni recuperación de contraseña.  
**Verificación:** HTTP/PostgreSQL para aprobar, rechazar, preferencia apagada, rechazo sin motivo y actor no autorizado; comprobar que activación/recuperación siguen encolando correo.

### RF-NOT-03 — Evento del ciclo de órdenes

**Dado** una orden nueva existente en `SUBMITTED`, creada por un actor autenticado y con propietario persistido  
**Cuando** un ADMIN ejecuta la transición real `SUBMITTED → APPROVED`  
**Entonces** se notifica internamente al propietario y se encola correo si su preferencia funcional está habilitada (por defecto sí). El propietario es el actor autenticado que creó la orden. Órdenes históricas sin propietario permanecen sin asignación y no generan notificación a un destinatario arbitrario. Transiciones fallidas/repetidas no producen entrega.  
**Verificación:** ciclo HTTP con creador y aprobador distintos; verificar propietario y destinatario, preferencia y ausencia de aviso por transición inválida/repetida y orden histórica sin propietario.

### RF-NOT-04 — Consultar notificaciones propias

**Dado** dos usuarios con notificaciones leídas y no leídas  
**Cuando** cualquiera consulta su colección  
**Entonces** recibe solo sus notificaciones, cada una con indicador de lectura y fechas, y puede filtrar leídas/no leídas con filtros validados; omitir el filtro devuelve ambas. Un intento de pasar un userId ajeno no amplía el alcance.  
**Verificación:** pruebas HTTP con dos sesiones y recuentos por estado; enviar filtro ajeno y comprobar que el resultado no contiene elementos de la otra cuenta.

### RF-NOT-05 — Marcar como leída

**Dado** una notificación no leída que pertenece al usuario autenticado  
**Cuando** la marca como leída  
**Entonces** el servidor guarda `readAt` una sola vez y posteriores lecturas muestran estado leído. La operación repetida es idempotente. Notificación ajena/inexistente no cambia datos y devuelve `404`; solicitud anónima devuelve `401`.  
**Verificación:** prueba HTTP con dos usuarios y consulta DB antes/después; verificar `readAt` estable en repetición.

### RF-NOT-06 — Canal interno y correo

**Dado** un evento funcional elegible y correo funcional habilitado por preferencia (por defecto)  
**Cuando** se confirma la acción de negocio  
**Entonces** la notificación interna y la fila outbox se confirman atómicamente junto al evento; la API no llama SMTP. Un fallo SMTP posterior no revierte el evento ni la notificación. La preferencia funcional no afecta correo esencial de activación/recuperación.  
**Verificación:** forzar fallo de persistencia outbox y comprobar rollback conjunto; fallar SMTP luego del commit y comprobar que el evento/notificación persisten; apagar preferencia y comprobar que activación/recuperación siguen encolándose.

### RF-NOT-07 — Correo desactivado

**Dado** que el usuario desactiva su preferencia de correo funcional  
**Cuando** ocurre un evento notificable  
**Entonces** se persiste la notificación interna, no se encola correo funcional ni se invoca SMTP para ella. Correo funcional está habilitado por defecto. La preferencia no desactiva activación ni recuperación de contraseña.  
**Verificación:** comprobar preferencia inicial habilitada, desactivación, presencia interna, ausencia en outbox funcional y que los flujos de activación/recuperación sí generan sus filas.

## Cola de correos

### RF-NOT-08 — Encolamiento independiente de SMTP

**Dado** SMTP apagado, inaccesible o lento  
**Cuando** se confirma un evento que requiere correo  
**Entonces** la operación persiste el correo cifrado en el outbox y no abre conexión SMTP. Fallo de base de datos sí revierte conjuntamente evento interno y fila de outbox.  
**Verificación:** integración con SMTP desconectado y sender espía; confirmar cero conexiones, fila encolada y atomicidad tras fallo de persistencia.

### RF-NOT-09 — Worker independiente

**Dado** la API y PostgreSQL disponibles, con el worker desactivado en el proceso web  
**Cuando** se inicia API y luego un proceso worker habilitado  
**Entonces** API arranca/atiende sin SMTP; el worker es proceso sin servidor HTTP, reclama mensajes pendientes con concurrencia segura y termina cada ejecución tras un máximo de 50 elementos.  
**Verificación:** prueba de configuración/proceso y prueba de concurrencia con dos workers; ningún mismo registro queda reclamado simultáneamente.

### RF-NOT-10 — Reintentos acotados y fallo final

**Dado** un correo pendiente cuyo envío falla  
**Cuando** el worker procesa y vuelve a ejecutarse tras los plazos de 1 minuto y 5 minutos adicionales  
**Entonces** hay tres intentos totales como máximo: inicial, segundo tras ≥1 minuto y tercero tras ≥5 minutos adicionales desde el segundo fallo. Tras el tercero queda `FAILED` terminal; se registran intentos, fechas y último error sanitizado. Un éxito queda `SENT` y no se reintenta.  
**Verificación:** reloj controlable y SMTP fallido; ejecutar antes/después de los límites, comprobar llamadas, fechas, estado final y no elegibilidad posterior; el error no contiene payload, token ni credencial.

### RF-NOT-11 — Consulta administrativa del outbox

**Dado** un ADMIN autenticado y filas en estados distintos  
**Cuando** consulta el estado de entregas  
**Entonces** ve estado, intentos, fechas de encolamiento/intento/próximo intento/envío/fallo y último error sanitizado; STANDARD recibe `403`, anónimo `401`. La salida nunca incluye payload descifrado, tokens ni credenciales SMTP.  
**Verificación:** HTTP como ADMIN/STANDARD/anónimo; comprobar campos, autorización y ausencia de payload/token/credenciales sintéticos en respuesta y logs.

### RF-NOT-12 — Evitar duplicados al repetir procesador

**Dado** un evento con clave de idempotencia estable y correo ya `SENT` o `FAILED`  
**Cuando** la operación de negocio se repite o el worker vuelve a ejecutarse  
**Entonces** no se crea una segunda entrega para el mismo evento/destinatario/canal; estados terminales no se envían nuevamente. Registros pendientes no se reclaman en paralelo.  
**Verificación:** repetir comando/evento y ejecutar dos workers concurrentes; comparar clave lógica, filas y envíos SMTP. `FOR UPDATE SKIP LOCKED` impide reclamar una fila simultáneamente. Una caída tras aceptación SMTP y antes del commit conserva la limitación documentada.

### RF-NOT-13 — Credenciales SMTP por entorno

**Dado** un worker configurado para SMTP  
**Cuando** se inicia con configuración válida o faltante  
**Entonces** host, puerto, remitente y credenciales provienen solo de variables de entorno/configuración externa; al faltar configuración requerida el worker falla al iniciar con error genérico seguro. Ninguna credencial aparece en logs, respuestas, repositorio o configuración versionada.  
**Verificación:** prueba de configuración usando valores sintéticos no secretos y búsqueda estática de nombres de variables/propiedades; comprobar que logs redactan y no interpolan valores.

## Reportes

### RF-REP-01 — Solicitudes por estado y período

**Dado** solicitudes de varios estados con fechas dentro, fuera y en los límites  
**Cuando** se consulta el reporte con `[desde, hasta)` válido  
**Entonces** devuelve agregaciones por estado y período, con conteo por grupo; omite grupos sin filas. `desde` se incluye y `hasta` se excluye. No devuelve filas individuales.  
**Verificación:** fixture PostgreSQL con fechas límite y estados conocidos; comparar cada conteo con suma esperada y confirmar ausencia de IDs/detalles.

### RF-REP-02 — Eficiencia de aprobación de órdenes

**Dado** órdenes que completan `DRAFT → SUBMITTED → APPROVED` dentro del período  
**Cuando** se consulta el reporte de eficiencia agrupado por período  
**Entonces** devuelve cantidad de órdenes aprobadas y duración `approved_at - submitted_at` en promedio, mínimo y máximo. Solo agrega registros con ambas fechas válidas. No se inventan fechas históricas: filas incompletas quedan excluidas. La agregación se ejecuta en backend/SQL y aplica alcance por rol.  
**Verificación:** PostgreSQL con duraciones conocidas, períodos múltiples y una orden histórica con fechas nulas; cotejar count/avg/min/max, grupos y exclusión. La migración nueva agrega timestamps nullable y deja V1-V4 intactas.

### RF-REP-03 — Agregaciones reales

**Dado** solicitudes y órdenes con datos suficientes  
**Cuando** se solicita cualquiera de los reportes  
**Entonces** el backend/motor de datos ejecuta agrupación y agregaciones (`COUNT`, `AVG`, `MIN`, `MAX`); la respuesta no contiene listados ni calcula resultados recorriendo entidades en memoria.  
**Verificación:** PostgreSQL con fixture conocido; inspección de consulta/repository y DTO agregado sin IDs individuales.

### RF-REP-04 — Alcance por rol

**Dado** datos de dos usuarios y una sesión ADMIN o STANDARD  
**Cuando** consulta cualquiera de los reportes  
**Entonces** ADMIN obtiene agregados globales; STANDARD solo datos de solicitudes/órdenes propias, filtrados en backend por actor autenticado. Órdenes históricas sin propietario no se atribuyen a STANDARD. No se acepta userId para ampliar alcance. Anónimo recibe `401`.  
**Verificación:** HTTP/SQL con datos cruzados, ambos roles, userId manipulado y órdenes sin propietario; cotejar conteos/tiempos.

### RF-REP-05 — Filtro temporal en ambos reportes

**Dado** filtros ausentes o un intervalo válido/inválido  
**Cuando** se consulta cualquiera de los dos reportes  
**Entonces** ambos aceptan `desde` y `hasta` en UTC `[desde, hasta)`. Formato incorrecto, rango invertido o mayor de 366 días devuelve `400`; omitir ambos usa últimos 30 días. Solicitudes filtra por `requested_at`; eficiencia, por `approved_at`, excluyendo filas sin ambas fechas.  
**Verificación:** reloj fijo; probar extremos, intervalo vacío, formato inválido, invertido, 366/367 días y rango predeterminado.

## Cobertura de requisitos

| ID | Criterio cubierto | Verificación principal |
|---|---|---|
| RF-NOT-01 | Notificar exclusivamente ADMIN activos al crear solicitud | Integración PostgreSQL |
| RF-NOT-02 | Decisión a solicitante; motivo literal en rechazo | HTTP + persistencia |
| RF-NOT-03 | Evento `SUBMITTED → APPROVED` | Ciclo de vida HTTP |
| RF-NOT-04 | Solo propias; leídas/no leídas | HTTP aislamiento |
| RF-NOT-05 | Marcar propia, idempotente; ajena rechazada | HTTP + persistencia |
| RF-NOT-06 | Canal interno y correo | Integración SMTP de prueba |
| RF-NOT-07 | Desactivar email conserva interno, sin cola | Prueba con sender espía |
| RF-NOT-08 | Encolar sin SMTP | Prueba de fallo/latencia |
| RF-NOT-09 | Worker independiente y lote/concurrencia | Integración configuración/concurrencia |
| RF-NOT-10 | Reintentos limitados y `FAILED` | Reloj controlable + SMTP fallido |
| RF-NOT-11 | Consulta ADMIN de estados/metadatos | HTTP por roles |
| RF-NOT-12 | Idempotencia en negocio/procesador | Reejecución + conteo SMTP |
| RF-NOT-13 | SMTP exclusivamente por entorno | Configuración + revisión estática |
| RF-REP-01 | Conteo por estado/período | PostgreSQL límites |
| RF-REP-02 | Eficiencia de aprobación: cantidad/promedio/mínimo/máximo | PostgreSQL; históricos incompletos excluidos |
| RF-REP-03 | Consulta agregada sin listado | Repositorio + DTO |
| RF-REP-04 | ADMIN global, STANDARD propio | HTTP aislamiento |
| RF-REP-05 | Rango UTC en ambos reportes | Reloj fijo/límites |

## Decisiones cerradas y contrato de persistencia para F1-F3

La V2 actual almacena únicamente `created_at`; no persiste propietario ni instantes de `SUBMITTED`/`APPROVED`. Añadir una migración Flyway nueva (sin modificar V1-V4) que incorpore `owner_user_id uuid NULL REFERENCES users(id) ON DELETE RESTRICT`, `submitted_at timestamptz NULL` y `approved_at timestamptz NULL`, con índices adecuados para consultas por propietario/estado/fechas.

En cada orden nueva, la aplicación asigna `owner_user_id` desde el actor autenticado que la crea; la capa de aplicación/dominio exige propietario para nuevas altas. Las filas históricas mantienen propietario `NULL`, sin atribución arbitraria, y no se notifican a STANDARD ni generan aviso de aprobación a un destinatario inventado. Al confirmar `DRAFT → SUBMITTED`, se fija `submitted_at`; en `SUBMITTED → APPROVED`, `approved_at`. Actualizar timestamps y estado bajo el bloqueo/transacción de la orden existente. No hacer backfill: filas históricas sin fechas suficientes se excluyen del reporte de eficiencia.

Las notificaciones internas y su correo funcional se escriben junto al evento en una misma transacción. El canal interno es obligatorio; correo funcional está habilitado por defecto y una preferencia explícitamente apagada suprime únicamente ese correo. Persistir la preferencia en una tabla nueva `user_notification_preferences` con `user_id` como PK/FK y `functional_email_enabled boolean NOT NULL DEFAULT true`; ausencia de fila se interpreta habilitada para usuarios existentes. Activación y recuperación de contraseña siguen usando la cola esencial independientemente de esa preferencia.

Se reutiliza `outbound_emails`, su cifrado y worker separado. Una migración nueva añade `attempt_count` (default 0), `last_attempt_at`, `next_attempt_at`, `failed_at` y `last_error` sanitizado; conservar `sent_at`, cifrado y `FOR UPDATE SKIP LOCKED`. `last_error` contiene clase/código y mensaje filtrado con límite de longitud, sin payload ni datos sensibles. La consulta administrativa expone esos metadatos pero nunca payload descifrado, tokens o credenciales. SMTP conserva la limitación de entrega exactamente una vez descrita arriba.

La zona horaria, intervalos, máximo de 366 días y valor predeterminado de 30 días quedan decididos. No quedan ambigüedades de requisitos para F1-F3; el límite técnico de SMTP está explícitamente aceptado y documentado.

Los comandos de `AGENTS.md` se cotejaron con los comandos de Windows PowerShell y Docker Compose del README. `.env.example` se inspeccionó únicamente para nombres de variables; no se consultaron ni se divulgaron valores.
