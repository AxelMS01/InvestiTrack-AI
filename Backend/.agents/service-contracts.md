# Contratos entre servicios — InvescticTrack

Versión de diseño: 1.0. Estado: especificación propuesta para implementación; no representa APIs ya desplegadas.

Este documento complementa [la arquitectura](backend-architecture.md), [los endpoints](api-endpoints.md) y [el esquema de datos](database-schema.md). Define responsabilidades, mensajes, respuestas y comportamiento ante fallos. Las decisiones nuevas se identifican al final para incorporarlas a esos documentos antes de implementar.

## 1. Autoridad y dependencias

| Proveedor | Es autoridad sobre | Consumidores y contrato |
|---|---|---|
| Identity & Access | Cuenta activa, roles institucionales, política global | Servicios de dominio: estado y autorización |
| Proposal & Evaluation | Propuesta, evaluadores, decisión y snapshot aprobado | Project: datos de creación; Document/Identity: permisos de propuesta |
| Project Execution | Proyecto, membresías, permisos locales, actividades, hitos, avance y cierre | Document/Identity/AI/Reporting: contexto y autorización del proyecto |
| Document Management | Versiones, clasificación, asociaciones, escaneo y disponibilidad | Project: evidencia/informe; AI: texto y validación de fuentes |
| Dashboard & Reporting | Proyecciones y trabajos de exportación | Gateway: consultas; administración: reconstrucción |
| AI Assistant | Índice, recuperación, respuestas y referencias | Document: estado de indexación; Audit: metadatos de uso |
| Audit | Registro histórico de acciones | Todos los productores: eventos y entrada excepcional por REST |
| Notification | Preferencias, notificaciones y entregas | Consume eventos de dominio |

Gateway no coordina transacciones. Cada proveedor valida identidad del servicio, permisos y estado de sus recursos. Ningún consumidor accede directamente a tablas ajenas.

**Decisión de propiedad:** Project conserva las membresías, conforme al esquema de datos. Identity no mantiene una segunda fuente de permisos de proyecto. Puede resolver una decisión consultando contexto vigente del propietario. Estos endpoints de contexto son consultas internas y no vuelven a invocar Identity, evitando ciclos.

## 2. Convenciones comunes

- Base interna: `/internal/v1`; no se publica en Gateway.
- JSON con nombres `camelCase`; IDs de recursos y usuarios de aplicación: UUID; fecha/hora UTC y fechas `YYYY-MM-DD`. El `sub` del proveedor de identidad se mapea al UUID de usuario, no se supone que ambos coincidan.
- Autenticación mutua entre servicios y token con audiencia del proveedor. Para actuar por un usuario, usar un token firmado delegado; `subjectId` debe coincidir con su identidad. Los trabajos automáticos requieren permisos de servicio específicos.
- `Authorization`, `X-Correlation-ID` y, cuando esté disponible, `traceparent`. Nunca aceptar encabezados de usuario como prueba de identidad.
- Mutaciones reintentables: `Idempotency-Key`. Actualizaciones de recursos mutables: `If-Match`; versión obsoleta produce `412`, ausencia requerida produce `428`.
- Clave idempotente: ámbito `(servicio llamador, operación, recurso, clave)`. Guardar hash del cuerpo y respuesta 24 horas como mínimo. Repetición idéntica devuelve el resultado original; mismo identificador con cuerpo distinto devuelve `409 IDEMPOTENCY_CONFLICT`. Las restricciones de unicidad de negocio persisten sin límite de 24 horas.
- Campos indicados en las tablas son obligatorios salvo `?`; `?` permite omisión, no `null`, salvo indicación expresa. Listas acotadas a 100 elementos; rechazar duplicados. Campos nuevos opcionales no deben romper consumidores.
- Consultas: `200`; creación: `201`; trabajo aceptado: `202` con `Location`; actualización sin cuerpo: `204`.
- Errores: `application/problem+json`, con `type`, `title`, `status`, `detail`, `instance`, `code`, `correlationId` y `errors?`, siguiendo el formato ya definido en endpoints.
- `400`: estructura inválida; `401`: credenciales inválidas; `403`: servicio sin permiso; `404`: ausente/no visible; `409`: estado incompatible; `422`: datos de dominio inválidos; `429`: límite; `503`: dependencia necesaria no disponible.

**Presupuesto inicial propuesto:** 300 ms para conexión y 1 s para una consulta interna; máximo 2.5 s para la cadena normal completa. Un reintento con espera aleatoria únicamente si cabe en ese presupuesto, para consultas o mutaciones idempotentes ante fallos de transporte, `429` o `503`, respetando `Retry-After`. No reintentar otros `4xx`. Procesamiento de archivos, reconstrucciones e IA se ejecutan como trabajos con presupuesto separado. Si falla autorización, devolver `503` y no entregar datos protegidos.

## 3. Contratos REST

Las rutas de esta sección llevan el prefijo `/internal/v1`. `version` es versión de concurrencia del agregado; `documentVersion` identifica el contenido binario; `accessVersion` cambia con permisos o clasificación; no son intercambiables.

### 3.1 Identidad y autorización

| Operación | Entrada | Salida y reglas |
|---|---|---|
| `GET /users/{userId}/status` | UUID de usuario | `userId, active:boolean, roles:string[], identityVersion:integer`. Lectura vigente, sin correo ni perfil completo |
| `POST /authorization/check` | `subjectId, action, resource:{type,id,projectId?}` | `allowed:boolean, policyVersion:string, reasonCode:string, identityVersion:integer, accessVersion?:integer`. Denegación de negocio: `200` con `allowed:false` |
| `POST /authorization/filter` | `subjectId, action, resources:[{type,id,projectId?}]` | `allowedResourceIds:UUID[], policyVersion, evaluatedAt`. Intersección únicamente; no descubre recursos fuera de la entrada |

`type`: `PROPOSAL`, `PROJECT`, `ACTIVITY`, `MILESTONE`, `DOCUMENT`. Catálogo inicial de acciones: `PROPOSAL_READ`, `PROPOSAL_WRITE`, `PROJECT_READ`, `PROJECT_WRITE`, `ACTIVITY_WRITE`, `DOCUMENT_READ`, `DOCUMENT_UPLOAD`, `DOCUMENT_DELETE`, `AI_SOURCE_READ`, `PROJECT_CLOSE`. Una acción desconocida nunca concede acceso.

Identity combina cuenta/roles vigentes con hechos del propietario. Para documentos, usa el contexto interno de Document; para proyectos, el de Project; para propuestas, el de Proposal. El propietario verifica que cualquier `projectId` enviado corresponda al recurso; no confía en asociaciones aportadas por el consumidor. `filter` resuelve el lote de forma completa o devuelve `503`; no trata fallos como permisos concedidos.

### 3.2 Propuesta → proyecto

| Operación | Entrada | Salida y reglas |
|---|---|---|
| `GET /proposals/{proposalId}/project-creation-data` | `approvalId` como parámetro de consulta obligatorio | Snapshot inmutable descrito abajo. `409 PROPOSAL_NOT_APPROVED` o `409 APPROVAL_MISMATCH` si no corresponde |
| `PUT /proposals/{proposalId}/project-reference` | `approvalId, projectId` | `204` si se registra o ya coincide; `409 PROJECT_REFERENCE_CONFLICT` si existe otro proyecto. Solo Project |
| `GET /projects/{projectId}/access-context` | `subjectId` obligatorio en consulta | `projectId, status, classification, accessVersion, subject:{userId,membershipStatus,projectRole?,permissions:string[]}`. Solo hechos locales, sin decisión global |
| `POST /projects/access-contexts` | `subjectId, projectIds:UUID[]` | `items:[contexto anterior], missingIds:UUID[]`. Nunca devuelve todos los miembros |

El snapshot aprobado contiene `proposalId, approvalId, approvedAt, proposalVersion, title, abstract?, researchAreaCode, principalResearcherId, collaborators:[{userId,projectRole}], creationPlan:{coordinatorId,startDate?,plannedEndDate}`. Título máximo 500 caracteres; fecha final igual o posterior al inicio si existe. La aprobación requiere plan de creación válido; no se inventan fechas a partir de la duración estimada.

**Flujo y recuperación:**

1. Proposal guarda decisión, snapshot y `ProposalApproved` en la misma transacción local.
2. Project consume el evento, obtiene ese snapshot y crea el proyecto en estado `APPROVED`, con avance 0. Guarda proyecto, membresías, inbox y `ProjectCreated` en una sola transacción.
3. `sourceProposalId` es único. Un duplicado devuelve/usa el proyecto existente; una aprobación incompatible se registra como conflicto para revisión.
4. Proposal consume `ProjectCreated` y registra la referencia. El `PUT` permite reconciliar una referencia faltante, sin bloquear la creación.
5. Un fallo transitorio se reintenta. Un fallo permanente pasa a revisión; la propuesta sigue aprobada con creación pendiente. No se revierte una decisión académica automáticamente.

El `POST /api/v1/projects` existente utiliza el mismo caso de uso y la misma unicidad. Si el evento ya creó el proyecto, devuelve `200` con el existente cuando los datos coinciden; si difieren, `409`. Cambiar después fechas o coordinador requiere la actualización normal autorizada.

### 3.3 Documentos y trabajadores

| Operación | Llamador y entrada | Salida / invariantes |
|---|---|---|
| `POST /documents/{id}/scan-results` | Escáner: `jobId, documentVersion, sha256, result: CLEAN|INFECTED|ERROR, engine, signatureVersion, scannedAt` | `204`. Identidad autenticada y firma del resultado verificables; hash/versión/trabajo deben coincidir. `ERROR` no habilita el archivo |
| `POST /documents/{id}/extractions` | Extractor: `jobId, documentVersion, sha256, status: SUCCEEDED|FAILED, contentRef?, textSha256?, segmentCount?, errorCode?` | `204`. Éxito exige referencia privada, hash y cantidad; fallo exige código. Solo después de escaneo limpio |
| `GET /documents/{id}/indexing-content` | Indexador: consulta `documentVersion` | `documentId, documentVersion, extractionId, classification, accessVersion, context, segments:[{position,page?,section?,text}], nextCursor?`. Paginación por `cursor`, hasta 100 segmentos y 1 MiB por página; segmentar durante extracción |
| `POST /documents/{id}/indexing-status` | AI: `documentVersion, extractionId, indexVersion, status: INDEXED|FAILED, indexedAt?, errorCode?` | `204`. Éxito exige fecha; fallo exige código. No habilita versiones nuevas por error |
| `POST /documents/{id}/source-validation` | AI con usuario delegado: `subjectId, documentVersion, expectedAccessVersion` | `allowed, reasonCode, documentId, documentVersion, accessVersion, checkedAt`. Comprueba cuenta, permisos, clasificación, disponibilidad y versión actual |

`context` contiene `proposalId?`, `projectId?`, `activityId?`, `milestoneId?`; debe pertenecer a propuesta o proyecto, no a ambos, y la actividad/hito debe pertenecer al proyecto indicado. `contentRef` es un identificador privado del almacén administrado de extracción; no una URL arbitraria que Document deba descargar.

Los callbacks se deduplican por `(jobId, documentVersion, operación)`, además de la clave HTTP. Duplicados iguales: `204`; resultado contradictorio: `409 PROCESSING_RESULT_CONFLICT`. Un callback tardío de una versión eliminada no la resucita. Un archivo infectado se mantiene inaccesible. Solo texto extraído de una versión limpia, disponible y vigente puede indexarse; los formatos sin extracción quedan descargables si son seguros, pero fuera de RAG.

**Regla RAG:** filtrar antes de recuperar y revalidar las fuentes antes de enviar texto al modelo. Revalidar de nuevo antes de entregar respuesta/citas; si cambió el acceso, descartar la respuesta y regenerar con fuentes permitidas o informar insuficiencia. Un evento de revocación invalida el índice, pero nunca sustituye la consulta vigente. La validación es puntual: no puede retirar texto ya enviado; el instante de envío al proveedor debe quedar auditado.

### 3.4 Contratos adicionales necesarios

Estas rutas completan dependencias que los endpoints anteriores todavía no especificaban:

| Proveedor / operación | Entrada | Resultado / uso |
|---|---|---|
| Proposal: `GET /proposals/{id}/access-context` | `subjectId` en consulta | `proposalId,status,accessVersion,subject:{userId,isOwner,isCollaborator,isAssignedEvaluator}`; solo Identity y Document autorizados |
| Document: `GET /documents/{id}/access-context` | ID | `documentId,classification,status,documentVersion,accessVersion,context`; solo Identity. No invoca Identity de vuelta |
| Project: `POST /projects/{id}/association-validation` | `activityId?,milestoneId?,purpose: EVIDENCE|FINAL_REPORT|DOCUMENT` | `valid,reasonCode,projectVersion`; valida existencia, pertenencia y estado local. No otorga permiso de usuario |
| Document: `POST /documents/{id}/reference-validation` | `projectId,documentVersion,purpose: EVIDENCE|FINAL_REPORT` | `valid,reasonCode,documentVersion,sha256`; solo Project con usuario delegado, exige versión limpia/disponible y permiso |

Para evitar carreras al cerrar, se añade un contrato de reserva de referencias: `PUT /documents/{id}/holds/{referenceId}` con `projectId,documentVersion,purpose` crea un bloqueo durable de borrado/reclasificación incompatible y responde `204`; `DELETE` libera solo una reserva del mismo llamador, también idempotente. Solo Project puede reservar tras autorización de la operación. Document rechaza cambios incompatibles con `409 DOCUMENT_IN_USE`.

Project reserva el informe antes de confirmar el cierre; guarda el identificador en su transacción. Si la transacción falla, libera la reserva; si cae el proceso, un reconciliador consulta Project y libera únicamente reservas confirmadas como no utilizadas. La indisponibilidad no equivale a referencia inexistente. El informe de un proyecto cerrado mantiene su versión fijada; la retención institucional se define aparte. Para evidencia y asociaciones, guardar la referencia validada, procesar bajas mediante eventos y reconciliar; no usar borrado físico de entidades referenciadas.

### 3.5 Proyecciones y auditoría

| Operación | Entrada | Salida |
|---|---|---|
| Reporting: `POST /projections/rebuilds` | `projection: PROJECTS|DEADLINES|EVALUATIONS, scope:{projectIds?:UUID[]}, reason` | `202`, `jobId,status:QUEUED,requestedAt`; ámbito vacío significa reconstrucción total, solo administración |
| Reporting: `GET /projections/rebuilds/{jobId}` | ID | `jobId,status:QUEUED|RUNNING|SUCCEEDED|FAILED,processedCount,startedAt?,completedAt?,errorCode?` |
| Audit: `POST /audit-events` | `eventId,occurredAt,actor:{type,id},action,resource:{type,id},outcome,correlationId` | `201` primera aceptación; `200` duplicado igual; `409` contenido contradictorio. Productor derivado de credenciales |

Reconstruir requiere un contrato adicional de exportación interna en cada propietario: `GET /internal/v1/projection-snapshots?projection=...&cursor=...&limit=100`, accesible solo a Reporting. Devuelve `snapshotId,asOf,items:[{aggregateType,aggregateId,aggregateVersion,state}],nextCursor?`; cada cursor queda ligado al snapshot consistente y al ámbito autorizado. `state` contiene solo campos de la proyección correspondiente del esquema, sin documentos ni evaluaciones confidenciales completas. Cursor caducado: `409 SNAPSHOT_EXPIRED`, reiniciar el trabajo.

Reporting empieza a almacenar eventos concurrentes antes del snapshot, construye tablas nuevas y aplica eventos posteriores por versión de agregado antes de intercambiar la proyección. RabbitMQ por sí solo no se considera archivo histórico. Consultas exponen `asOf`; exportaciones y descargas revalidan permisos actuales.

Audit consume todos los eventos necesarios, además de autenticación desde el IdP mediante un adaptador autorizado. No guarda contraseñas, tokens, URLs firmadas ni textos completos de preguntas/documentos. El fallback REST se usa solo cuando la política de la acción exige confirmación durable antes de responder; si falla devuelve `503`. No sustituye la atomicidad local de dominio y outbox, ni garantiza por sí solo una transacción distribuida.

## 4. Contrato de eventos

Transporte propuesto para MVP: RabbitMQ, exchange durable `invesctictrack.domain`, tipo topic; routing key `<dominio>.<eventType>.v1`. Cada consumidor tiene su propia cola durable. Los permisos del broker limitan qué productor puede publicar cada dominio.

Envelope común, compatible por adición con el ejemplo de endpoints:

```json
{
  "eventId": "c68a54e7-9b85-47ee-8a0f-66cfaf9d9c73",
  "eventType": "ProjectProgressChanged",
  "eventVersion": 1,
  "occurredAt": "2026-10-13T18:00:00Z",
  "producer": "project-execution-service",
  "correlationId": "flow-20261013-001",
  "actor": {"type": "USER", "id": "ca62b387-45fd-4e97-8940-1b061dcac234"},
  "aggregate": {"type": "PROJECT", "id": "141dff33-5f73-4eec-8f33-8a577f239204", "version": 7},
  "data": {"projectId": "141dff33-5f73-4eec-8f33-8a577f239204", "progress": 42.5}
}
```

Todos esos campos son obligatorios; `causationId` es opcional e identifica el evento/comando causante. Actor `USER` usa UUID de aplicación; `SERVICE` usa identidad del servicio. `aggregate.version` es entero positivo monotónico local; varios hechos de la misma transacción pueden compartir versión y tienen `eventId` distintos. Tamaño máximo propuesto: 256 KiB. No incluir binarios, texto extraído, tokens o listas de miembros.

### Catálogo y payload mínimo `data`

Audit consume todos los siguientes; la columna consumidores indica los adicionales. Los nombres separados por `/` representan tipos distintos con la misma forma base.

| Productor / dominio | Eventos | Campos obligatorios adicionales a IDs | Consumidores |
|---|---|---|---|
| Identity / identity | `UserCreated`, `UserDeactivated`, `UserReactivated` | `userId, active, identityVersion` | AI, servicios con caché de identidad |
| Identity / identity | `UserRolesChanged` | `userId, roles:string[], identityVersion` | AI, servicios con caché de identidad |
| Proposal / proposal | `ProposalCreated`, `ProposalSubmitted`, `ProposalChangesRequested`, `ProposalRejected` | `proposalId, status, ownerUserId` | Reporting; Notification excepto creación |
| Proposal / proposal | `EvaluatorAssigned` | `proposalId, assignmentId, evaluatorId, dueDate` | Reporting, Notification |
| Proposal / proposal | `EvaluationSubmitted` | `proposalId, assignmentId, evaluationId, evaluatorId, submittedAt` | Reporting, Notification |
| Proposal / proposal | `ProposalApproved` | `proposalId, approvalId, proposalVersion, approvedAt` | Project, Reporting, Notification |
| Project / project | `ProjectCreated` | `projectId, proposalId, approvalId, status, principalResearcherId, coordinatorId, plannedEndDate` | Proposal, Reporting, Notification |
| Project / project | `ProjectMemberChanged` | `projectId, userId, change:ADDED|UPDATED|REMOVED, accessVersion` | Document, AI, Reporting |
| Project / project | `ActivityChanged` / `MilestoneChanged` | `projectId, activityId` / `milestoneId`, más `operation:CREATED|UPDATED|DELETED` | Reporting, Notification |
| Project / project | `DeadlineOverdue` | `projectId, resourceType:ACTIVITY|MILESTONE, resourceId, dueDate, detectedAt` | Reporting, Notification |
| Project / project | `ProjectProgressChanged` | `projectId, progress` (número 0–100) | Reporting |
| Project / project | `FinalReportSubmitted` | `projectId, documentId, documentVersion, submittedBy, submittedAt` | Reporting, Notification |
| Project / project | `PublicationRegistered` | `projectId, publicationId` | Reporting |
| Project / project | `ProjectClosed` | `projectId, closedAt, closedBy, finalReportDocumentId, finalReportVersion` | Reporting, Notification |
| Document / document | `DocumentUploadCompleted`, `DocumentVersionCreated` | `documentId, documentVersion, sha256` | Trabajador de escaneo |
| Document / document | `DocumentScanPassed` / `DocumentScanFailed` | `documentId, documentVersion, jobId`, más `reasonCode` en fallo | Extractor solo éxito; Notification en fallo |
| Document / document | `DocumentTextExtracted` | `documentId, documentVersion, extractionId, accessVersion` | AI |
| Document / document | `DocumentAccessChanged` | `documentId, accessVersion` | AI, Reporting |
| Document / document | `DocumentDeleted` | `documentId, deletedAt, accessVersion` | AI, Project, Proposal, Reporting |
| AI / ai | `AiQuestionProcessed`, `AiInsufficientInformation` | `interactionId, subjectId, outcome, sourceCount, durationMs, modelConfigId` | Métricas autorizadas |
| AI / ai | `AiFeedbackSubmitted` | `interactionId, feedbackId, rating` (`POSITIVE` o `NEGATIVE`) | Métricas autorizadas |

Los eventos pequeños son avisos, no necesariamente snapshots completos. Para Reporting se añade `GET /internal/v1/projection-state/{aggregateType}/{aggregateId}` al propietario; devuelve `{aggregateType,aggregateId,aggregateVersion,state,deleted}` con los mismos campos permitidos que los snapshots. Una baja devuelve tombstone versionado, no un `404` ambiguo. Una consulta puede devolver un estado más reciente que el evento; aplicar siempre el más reciente.

**Nombres canónicos:** usar `DocumentUploadCompleted`, no el alias arquitectónico `DocumentUploaded`. `ProjectAccessChanged` listado bajo Identity se sustituye en esta propuesta por `ProjectMemberChanged`, publicado por Project para cualquier cambio de rol o permiso local. Cambios de política global incrementan `policyVersion` y publican el nuevo `AuthorizationPolicyChanged` con `{policyVersion}` desde Identity; invalidan cachés y obligan a recalcular decisiones. Añadir `ProjectChanged` con `{projectId}` para cambios de fechas, estado o clasificación, y `ProposalChanged` con `{proposalId}` para cambios no cubiertos; sus consumidores son Reporting y, si cambia acceso, Document/AI. La clasificación del proyecto incrementa también `accessVersion`.

## 5. Entrega, orden y recuperación

- Entrega al menos una vez. Productor guarda negocio + outbox en la misma transacción, publica con confirmación y conserva el mismo `eventId` en reintentos.
- Consumidor guarda cambio local + inbox en la misma transacción y confirma después. Unicidad `(consumerName,eventId)`; efectos externos usan además su propia clave idempotente.
- No suponer orden global. Particionar el procesamiento lógico por agregado. Para proyecciones, consultar estado completo y comparar versiones; no descartar todos los eventos de igual versión porque pueden representar hechos diferentes. Para comandos como crear proyecto, usar unicidad de negocio.
- Recuperación transitoria propuesta: reintentos a 1 s, 5 s, 30 s, 2 min y 10 min; después, cola de errores del consumidor y alerta. Esquema inválido o versión no soportada: cola de errores inmediata, sin bucle.
- Reprocesar con el `eventId` original tras corregir la causa; no insertar inbox de éxito cuando el efecto falló. Reconciliación periódica detecta propuestas sin proyecto, referencias faltantes y documentos no indexados.
- Document eliminado o acceso revocado invalida fuentes y cachés. La entrega retrasada no puede volver a activar una versión antigua: el indexador consulta estado vigente antes de publicar el índice.
- Notification deduplica por `(eventId,recipientId,channel)` y conserva estado de entrega. Una caída tras enviar correo puede ocasionar duplicación si el proveedor no admite idempotencia; no prometer entrega exactamente una vez.

## 6. Compatibilidad y aceptación

Cambios aditivos opcionales mantienen `/v1` y `eventVersion:1`. Quitar campos, cambiar significado, tipo o conjunto cerrado de estados exige nueva versión y convivencia hasta migrar consumidores. Un estado desconocido nunca habilita acceso o transición por defecto. Cada equipo proveedor mantiene la especificación y ejemplos; cada consumidor valida los campos que utiliza.

Pruebas de contrato requeridas antes de implementar una integración:

1. Token de servicio incorrecto o usuario delegado distinto: rechazo; permiso revocado: sin datos ni citas.
2. Aprobar y entregar dos veces el evento, incluso junto a creación manual: exactamente un proyecto y referencia coherente.
3. Caída después de commit y antes de publicar/confirmar: recuperación sin pérdida ni efecto duplicado.
4. Eventos fuera de orden o dos eventos con misma versión: proyección final correcta; huecos recuperables.
5. Callback con hash/versión/trabajo incorrectos: rechazo; escaneo fallido: archivo fuera de descarga e índice.
6. Revocación o borrado durante RAG: respuesta descartada; dependencia de permisos caída: `503` sin filtración.
7. Cierre simultáneo con borrado de informe: reserva preserva la versión o cierre falla; reservas huérfanas reconciliables.
8. Reconstrucción con escrituras concurrentes: snapshot más eventos sin perder cambios.
9. Cambio aditivo compatible y nueva versión desconocida: comportamiento previsto y alerta accionable.

## 7. Cambios que esta propuesta requiere en los documentos base

- Precisar propiedad de membresías en arquitectura y corregir nombres/productores de eventos.
- Añadir a endpoints los DTO, consultas de contexto, validación, reservas y exportación de proyecciones definidos aquí.
- Añadir al esquema snapshots de aprobación y plan de creación, referencia de proyecto en Proposal, versiones de acceso/clasificación de proyecto, reservas documentales, tombstones y persistencia de claves idempotentes. Mantener `sourceProposalId` único.
- Formalizar después los contratos REST en OpenAPI y los eventos en AsyncAPI/JSON Schema, con validación automatizada en cada proveedor. Este entregable define el diseño; no incluye implementación ni especificaciones ejecutables.

Límites, tiempos y calendario de reintentos son valores iniciales de diseño que deben verificarse con carga real. No cambian los requisitos institucionales de retención o clasificación.
