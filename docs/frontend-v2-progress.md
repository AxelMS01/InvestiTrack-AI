# Progreso del frontend V2

El paquete `InvescticTrack_Entregable_Tecnico_v2.zip` se encontró en Descargas. Se revisaron el README, la matriz RBAC, la extensión OpenAPI y ambos SQL como especificación. Los archivos SQL se consultaron sin ejecutarlos.

El punto de partida ya admite cinco roles, navegación contextual y caché por sesión. La incorporación anterior permitía la membresía directa del estudiante, la evaluación existente se limita a propuestas y la evidencia se modelaba como archivo de actividad. El repositorio incluye ahora `Backend/proposal-service` con controladores para propuestas, evaluaciones de propuestas, catálogo y referencias internas. Su contrato usa JWT institucional, UUID, paginación y ETag. Las rutas de la extensión V2 siguen pendientes como servicios reales.

## Matriz de entrega

Se añadieron 13 rutas al catálogo. La suite verifica las 66 rutas catalogadas. La integración en vivo de la extensión V2 queda pendiente.

| Área                                | Rutas principales                                                                                | Estado          | Implementación de frontend                                                                                                              | API institucional                                                                                                                                                                                                  |
| ----------------------------------- | ------------------------------------------------------------------------------------------------ | --------------- | --------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Unidades y vínculos                 | `/administracion/unidades`, `/administracion/unidades/:id`                                       | `MOCK_COMPLETE` | Alta, edición, estado, pertenencia y coordinación.                                                                                      | El ZIP propone `GET /academic-units`. Las escrituras administrativas necesitan contrato confirmado.                                                                                                                |
| Coordinación                        | `/coordinacion`                                                                                  | `MOCK_COMPLETE` | Directorios e indicadores filtrados por unidades autorizadas. El selector cambia el contexto visual.                                    | Pendientes identidad y asignaciones vigentes de unidad.                                                                                                                                                            |
| Evaluadores de proyecto             | `/coordinacion/evaluadores`, `/proyectos/:id/evaluadores`                                        | `MOCK_COMPLETE` | Selección, revisión y confirmación. Se comprueban ámbito, duplicados y conflictos. Incluye cambio de responsable en proyectos abiertos. | `GET/POST /projects/{projectId}/evaluator-assignments`. El cambio de responsable necesita contrato confirmado.                                                                                                     |
| Incorporación de estudiantes        | `/proyectos/:id/proponer-estudiante`, `/solicitudes-estudiantes`, `/solicitudes-estudiantes/:id` | `MOCK_COMPLETE` | Envío, aprobación, rechazo y cancelación. La aprobación activa la membresía en la misma operación.                                      | `GET/POST /projects/{projectId}/student-requests` y `POST /student-requests/{requestId}/decision`. El contrato propuesto de decisión admite aprobación y rechazo con `If-Match`. La cancelación necesita contrato. |
| Entrega de evidencias               | `/evidencias`, `/evidencias/nueva`, `/evidencias/:id`, `/proyectos/:id/evidencias`               | `MOCK_COMPLETE` | Nombre y código propios, borrador, envío, filtros, versiones académicas y correcciones.                                                 | `GET/POST /activities/{activityId}/evidences`, `GET /evidences/{evidenceId}`, `GET/POST /versions` y `POST /transitions` bajo la evidencia.                                                                        |
| Visualizador                        | `/evidencias/:id?tab=documento`                                                                  | `MOCK_COMPLETE` | PDF e imágenes con URL de objeto temporal. DOCX, XLSX y PPTX tienen descarga autorizada.                                                | Pendientes carga, autorización de vista previa, descarga y versiones en Document Service.                                                                                                                          |
| Revisiones                          | `/evidencias/:id?tab=retroalimentacion`                                                          | `MOCK_COMPLETE` | Revisiones formativas u oficiales, comentarios, correcciones y solicitudes de nueva entrega.                                            | `GET/POST /evidences/{evidenceId}/reviews`. La gestión de una revisión oficial en curso necesita confirmación contractual.                                                                                         |
| Rúbrica, nota e historial           | `/evidencias/:id/evaluar`, `/evidencias?vista=calificaciones`                                    | `MOCK_COMPLETE` | Rúbrica configurada en los datos ficticios, nota inmutable por versión e historial filtrado por permisos.                               | `POST /evidence-versions/{versionId}/grades` exige `Idempotency-Key`. `GET /me/evidence-assignments` y `GET /evidences/{evidenceId}/grades` están pendientes.                                                      |
| Consulta de notas publicadas        | `/evidencias?vista=calificaciones`                                                               | `MOCK_COMPLETE` | Estudiantes consultan únicamente notas publicadas.                                                                                      | `GET /evidences/{evidenceId}/grades` pendiente.                                                                                                                                                                    |
| Acción de publicar notas            | Pendiente                                                                                        | `PENDING`       | Las notas nuevas se guardan reservadas. Hay ejemplos publicados para probar la visibilidad.                                             | Pendientes endpoint, autoridad de publicación y política de sustitución.                                                                                                                                           |
| Propuestas y rúbricas de propuestas | `/propuestas`, `/evaluaciones` y sus flujos existentes                                           | `MOCK_COMPLETE` | Se conservaron los flujos con decisión y conversión separadas. Coordinación por unidad y verificación de conflicto.                     | Controladores y DTO presentes en Spring. Falta conectar identidad, gateway y cliente al contrato real.                                                                                                             |
| Asistente IA                        | `/asistente`, historial y fuentes                                                                | `MOCK_COMPLETE` | El catálogo de fuentes aplica las reglas de documentos y versiones vigentes.                                                            | Pendiente el servicio RAG y la autorización institucional de fuentes.                                                                                                                                              |

## Contratos y decisiones pendientes

- El transporte de compatibilidad existente sigue disponible para autenticación y consultas cuando `VITE_DEMO_MODE=false`. El servicio Spring existente requiere un JWT con `user_id`, roles y audiencia. Sus colecciones son paginadas y sus mutaciones esperan el ETag devuelto en `If-Match`. Comprobé estos requisitos en sus controladores, DTO y README.
- El OpenAPI V2 separa `documentId` y `documentVersionId` y propone un par por llamada de asociación. El adaptador demo soporta hasta cinco archivos por versión académica. Se debe confirmar la representación de adjuntos múltiples antes de conectar Document Service.
- La simulación usa una rúbrica configurada de ejemplo y calcula el total en el adaptador con sus criterios y pesos. Las pantallas envían puntuaciones y la decisión por separado. La institución debe suministrar rúbrica, escala, pesos y política de aceptación.
- La solicitud, las versiones y la calificación comprueban revisiones optimistas. Una operación obsoleta recibe un conflicto `412` en demo. Se conserva la nota anterior por cada versión. La publicación futura debe especificar qué notas permanecen visibles.
- Las entregas anteriores basadas únicamente en archivos siguen consultables desde **Documentos**. Su vinculación a evidencias académicas requiere un mapeo explícito de título, autor y actividad.
- La regla de revisión independiente de propuestas sigue la política demo existente. El alcance de la obligatoriedad, la reasignación y la sustitución durante ausencias queda pendiente de definición institucional.
- Los datos, archivos, notas y auditoría locales son de demostración. Los permisos y la integridad institucional requieren validación en los servicios reales.

## Archivos creados y modificados

- `docs/frontend-v2-progress.md`
- `front/src/demo/academic-seed.ts`
- `front/src/demo/coordination.ts`
- `front/src/demo/evidence-policy.ts`
- `front/src/demo/evidences.ts`
- `front/src/demo/v2-workflow.test.ts`
- `front/src/features/admin/academic-units-page.tsx`
- `front/src/features/coordination/coordination-page.tsx`
- `front/src/features/coordination/module.ts`
- `front/src/features/evidences/evidence-create-page.tsx`
- `front/src/features/evidences/evidence-detail-page.tsx`
- `front/src/features/evidences/evidence-evaluation-page.tsx`
- `front/src/features/evidences/evidence-file.tsx`
- `front/src/features/evidences/evidences-page.tsx`
- `front/src/features/evidences/module.ts`
- `front/src/types/academic.ts`
- `front/src/types/evidence.ts`
- `front/ARCHITECTURE.md`
- `front/README.md`
- `front/src/api/assistant.api.test.ts`
- `front/src/api/auth.api.ts`
- `front/src/demo/access.ts`
- `front/src/demo/activities.ts`
- `front/src/demo/database.ts`
- `front/src/demo/documents.ts`
- `front/src/demo/evaluations.ts`
- `front/src/demo/files.ts`
- `front/src/demo/projects.ts`
- `front/src/demo/proposals.ts`
- `front/src/demo/workflow.test.ts`
- `front/src/features/activities/activities-page.tsx`
- `front/src/features/admin/module.ts`
- `front/src/features/auth/access-policy.ts`
- `front/src/features/auth/auth-context.ts`
- `front/src/features/auth/auth-provider.tsx`
- `front/src/features/auth/login-page.tsx`
- `front/src/features/auth/model.ts`
- `front/src/features/auth/role-switcher.tsx`
- `front/src/features/dashboard/dashboard-page.tsx`
- `front/src/features/dashboard/navigation.ts`
- `front/src/features/documents/documents-page.tsx`
- `front/src/features/evaluations/evaluations-page.tsx`
- `front/src/features/module.types.ts`
- `front/src/features/modules.ts`
- `front/src/features/projects/project-detail-page.tsx`
- `front/src/features/projects/project-workspace.tsx`
- `front/src/features/projects/projects-page.tsx`
- `front/src/features/proposals/proposal-detail-page.tsx`
- `front/src/features/proposals/proposals-page.tsx`
- `front/src/features/reports/reports-page.tsx`
- `front/src/router/modules.test.ts`
- `front/src/router/protected-routes.tsx`
- `front/src/router/workspaces.test.tsx`
- `front/src/types/document.ts`
- `front/src/types/project.ts`
- `front/src/types/proposal.ts`
- `front/src/types/user.ts`

## Pruebas y ejecución

| Comprobación           | Resultado observado                                                                                                                                                                                                                                                                     |
| ---------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `npm run typecheck`    | Aprobado.                                                                                                                                                                                                                                                                               |
| `npm run lint`         | Aprobado.                                                                                                                                                                                                                                                                               |
| `npm run format:check` | Aprobado.                                                                                                                                                                                                                                                                               |
| `npm run test`         | 134 pruebas aprobadas en 15 suites.                                                                                                                                                                                                                                                     |
| `npm run build`        | Aprobado. Rollup informó dos avisos de anotaciones de Zod y un bloque principal minificado de 511 kB.                                                                                                                                                                                   |
| Chrome contra `dist/`  | Probados el borrador nombrado, la vista previa PDF, el envío, la rúbrica, el aislamiento de la nota reservada y la descarga tras recargar. La administración recibió acceso denegado al cambiar de perfil. Se revisaron capturas de 390 y 1365 px y se comprobó el ancho del documento. |

`front/src/demo/v2-workflow.test.ts` cubre la aprobación con alcance de unidad, duplicados, conflictos de interés, acceso a fuentes, versiones inmutables, asignación de evaluador y concurrencia. `front/src/router/workspaces.test.tsx` prueba los paneles, el cambio de espacio y los recorridos V2 desde las rutas reales con React Testing Library. Las pruebas del catálogo verifican el registro de pantallas.

La guía describe cómo probar los cinco roles y las cuentas adicionales. [Guía V2](../front/README.md)
