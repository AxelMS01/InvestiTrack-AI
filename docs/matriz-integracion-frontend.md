# Matriz de integración frontend/backend

El navegador usa el prefijo `/api/v1` en el mismo origen que React. Las rutas del Gateway conservan método y ruta. Las colecciones institucionales usan `{ items, page: { limit, nextCursor, hasMore } }`. El cliente procesa el cursor y valida los DTO al recibir JSON. Identity provee los roles, el Gateway y cada propietario validan el Bearer y el acceso al recurso.

| Funcionalidad | Método | Endpoint del frontend | Gateway | Backend | DTO y estado |
| --- | --- | --- | --- | --- | --- |
| Iniciar sesión | GET | `/login` → `/realms/investitrack/protocol/openid-connect/auth` | Nginx → Keycloak | OIDC Authorization | Código, estado y PKCE S256. Integrado |
| Intercambio y renovación | POST | `/auth/callback` → `/realms/investitrack/protocol/openid-connect/token` | Nginx → Keycloak | OIDC Token | Formulario URL-encoded y respuesta estándar. Integrado |
| Cerrar sesión | GET | `/realms/investitrack/protocol/openid-connect/logout` | Nginx → Keycloak | OIDC End Session | `id_token_hint` y retorno registrado. Integrado |
| Perfil | GET | `/me` | `/api/v1/me` | IdentityController | `UserView` con UUID, nombre, estado y roles. Integrado |
| Usuarios y candidatos | GET | `/users`, `/users?role=EVALUATOR&status=ACTIVE` | `/api/v1/users` | IdentityController | Página de `UserView` para ADMIN o `LookupUser` para COORDINATOR. Integrado |
| Áreas | GET | `/reference/research-areas` | `/api/v1/reference/research-areas` | ProposalCatalogController | Página de código, nombre y descripción. Integrado |
| Propuestas | GET | `/proposals`, `/proposals/{id}` | `/api/v1/proposals[/{id}]` | ProposalController | Página o vista de propuesta con UUID, estado y versión. Integrado |
| Crear y editar propuesta | POST, PATCH | `/proposals`, `/proposals/{id}` | `/api/v1/proposals[/{id}]` | ProposalController | `ProposalInput` → vista. Clave de idempotencia al crear e `If-Match` al editar. Integrado |
| Enviar, cancelar, iniciar y aprobar | POST | `/proposals/{id}/transitions` | `/api/v1/proposals/{id}/transitions` | ProposalController | `Transition` con `creationPlan` para aprobación y `If-Match`. Integrado |
| Historial de propuesta | GET | `/proposals/{id}/history` | `/api/v1/proposals/{id}/history` | ProposalController | Página de entradas de evento. Integrado |
| Asignación | GET, POST | `/proposals/{id}/evaluator-assignments` | `/api/v1/proposals/{id}/evaluator-assignments` | ProposalController | Página o vista de asignación, entrada `AssignmentInput` e `If-Match`. Integrado |
| Criterios | GET | `/evaluation-criteria` | `/api/v1/evaluation-criteria` | ProposalCatalogController | Página con código, rango y peso. Integrado |
| Dictamen | GET, POST, PATCH | `/proposals/{id}/evaluations[/{evaluationId}]` | `/api/v1/proposals/{id}/evaluations[/{evaluationId}]` | ProposalController | Página o vista de evaluación, entrada `EvaluationInput`, UUID y versión. Integrado |
| Enviar dictamen | POST | `/proposals/{id}/evaluations/{evaluationId}/submission` | `/api/v1/proposals/{id}/evaluations/{evaluationId}/submission` | ProposalController | Evaluación con estado `SUBMITTED` e `If-Match`. Integrado |
| Proyectos y detalle | GET | `/projects`, `/projects/{id}` | `/api/v1/projects[/{id}]` | ProjectController | Página o vista con `sourceProposalId`, estado, fechas y `progressPercent`. Integrado |
| Conversión manual | POST | Pantalla demo | `/api/v1/projects` | ProjectController | `CreateProject`. En el perfil institucional se usa el evento. |
| Gestión de miembros, actividades, hitos, evidencias y cierre | GET, POST, PATCH, DELETE | Pantallas demo | `/api/v1/projects/{id}/...` | ProjectController | DTO por subrecurso. Integración UI pendiente |
| Documentos | GET y operaciones de carga, descarga y baja | Adaptador preliminar `/documents[/{id}]`; pantallas demo | `/api/v1/documents[/{id}]` y subrutas | DocumentController | Página y metadatos, URL S3 prefirmada según operación. Integración UI pendiente |
| Adaptadores preliminares de evaluaciones y actividades | GET | `/evaluations[/{id}]`, `/activities[/{id}]` | — | Recursos anidados en Proposal o Project | Sin consumidores institucionales. Usar las rutas del propietario |
| Administración de cuentas, auditoría y reportes | GET, POST, PATCH, PUT, DELETE | Pantallas demo | `/api/v1/users/...`, `/api/v1/audit-events/...`, `/api/v1/report-definitions`, `/api/v1/report-jobs/...` | Identity, Audit, Report | Contratos en los OpenAPI de propietario. UI pendiente salvo la lista de usuarios |
| IA y notificaciones | GET, POST, PATCH, PUT, DELETE según contrato | Pantallas demo y mensaje de indisponibilidad | `/api/v1/ai/...`, `/api/v1/notifications/...`, `/api/v1/me/notification-preferences` | Planificados | `503 FEATURE_UNAVAILABLE`. Sin reenvío |

Los códigos de backend siguen el formato Problem Details. La interfaz traduce los errores HTTP habituales y conserva su estado para mostrar inicio de sesión, autorización denegada, conflicto o indisponibilidad. El navegador usa cookies de Keycloak para su sesión con el proveedor y envía sólo Bearer a la API.
