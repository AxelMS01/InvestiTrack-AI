# Resolución del feedback del profesor

25 de noviembre de 2026 · Validación local de InvestiTrack AI

El PDF original reconoce la separación de los servicios, sus bases, OpenAPI y comunicación por REST y eventos. La demostración completa era el problema prioritario. La tabla relaciona las seis brechas del PDF y el apartado de demostración con los diez FIX de esta revisión.

| FIX | Observación original y causa | Corrección y archivos principales | Verificación y estado |
| --- | --- | --- | --- |
| 01 | Brecha 1, 11 rutas hacia IA y 8 hacia notificaciones. El contrato anticipaba propietarios ausentes. | `routes.tsv`, `RoutingConfiguration.java` y `DISABLED_ROUTE_OWNERS` conservan las 19 rutas futuras con respuesta `503 FEATURE_UNAVAILABLE`. Contratos en `Backend/.agents/`. | **Resuelto.** Pruebas del Gateway y respuestas HTTP `503` para ambos propietarios. |
| 02 | Brecha 2, faltaban arranque raíz, RabbitMQ y orquestación de Identity. Los Compose existentes dependían de una red externa. | `docker-compose.yml`, Dockerfile de frontend, Nginx, límites, dependencias de salud, redes y volúmenes de Compose. MinIO y su CLI se construyen en `infra/minio/Dockerfile` desde etiquetas oficiales. | **Resuelto.** Validación de Compose, build, arranque y salud de la infraestructura. Se recuperó el primer fallo de descarga construyendo MinIO Community desde código. |
| 03 | El PDF daba por cumplida la propiedad de los datos. El perfil integrado debía preservarla al usar un servidor PostgreSQL. | `infra/postgres/init/001-databases.sh`, seis bases de aplicación y `keycloak_db`, roles y credenciales propios. Flyway de cada servicio y catálogo local en `db/local` de Proposal. | **Resuelto.** Se contaron migraciones de seis aplicaciones. PostgreSQL confirmó el aislamiento de los siete roles. |
| 04 | Brecha 3, Project desactivaba mensajería e Identity desactivaba outbox fuera del perfil integrado. Las llamadas internas del flujo necesitaban credenciales de workload. | Compose activa los publicadores y consumidores existentes. Proposal y Project obtienen JWT con `client_credentials` según audiencia y alcance. Se conservan confirmaciones, DLQ, outbox, inbox persistente y unicidad por propuesta. | **Resuelto para el flujo.** Se comprobaron `published_at`, `processed_event` y un único proyecto tras reenviar el mensaje real. |
| 05 | Brecha 4, modo demo predeterminado y rutas/DTO incompatibles para sesión, propuestas y proyectos. | `front/src/api/oidc.ts`, `institutional-workflow.api.ts`, pantallas institucionales, realm de Keycloak, proxy del mismo origen y [matriz HTTP](./docs/matriz-integracion-frontend.md). | **Parcialmente resuelto.** El camino institucional y PKCE S256 pasaron en Edge. Build, lint y 134 pruebas de React. Las pantallas de módulos secundarios siguen pendientes. |
| 06 | Brecha 5, archivos `.env` y `.idea` en Git. Se encontró también un entorno HTTP con tokens y cachés de compilación. | `git rm --cached`, `.gitignore` global y locales y ejemplos con marcadores. Las copias locales se conservan. | **Resuelto en el índice.** Se verificó la exclusión de 52 archivos y la presencia de las copias sensibles. La rotación corresponde al equipo. |
| 07 | Brecha 2, README raíz sin instrucciones de arranque. | `README.md` con requisitos, inventario, configuración, arquitectura, pruebas y pasos por rol. | **Resuelto.** Comandos de configuración, arranque y verificación recogidos en este informe. |
| 08 | Brechas 1 y 6 y criterio de registro. Arquitectura futura presentada como implementada, coexistencia de Maven y Gradle, nombres de paquetes y clon superficial en la revisión original. | `README.md`, arquitectura y contratos actualizados. DNS de Compose y URLs configurables. Se conservan Maven, Gradle y los paquetes existentes. | **Parcialmente resuelto.** `git rev-parse --is-shallow-repository` devuelve `false`. El descubrimiento dinámico y la unificación de paquetes quedan pendientes. |
| 09 | Apartado «Pendiente a demostrar y documentar». La revisión requería preparar a mano red, broker y datos. | `infra/verify-workflow.py` y `front/e2e/workflow.e2e.ts` ejercitan servicios, roles, eventos, persistencia y navegador. | **Resuelto.** Verificador HTTP y prueba en Edge pasaron en el Compose local. |
| 10 | Apartado «Pendiente a demostrar y documentar». Faltaba trazabilidad de las observaciones. | Este informe y `INTEGRACION_DOCKER_PROGRESS.md`. | **Resuelto.** Informe y reproducción disponibles en el repositorio. |

## Comandos desde un clon limpio

```sh
python infra/init-env.py
docker compose config --quiet
docker compose up --build -d --wait
docker compose ps -a
python infra/verify-workflow.py
npm --prefix front ci
npm --prefix front exec -- playwright install chromium
npm --prefix front run test:e2e
```

El recorrido manual por rol está en el [README](./README.md). La prueba de navegador puede usar un canal ya instalado, por ejemplo `PLAYWRIGHT_CHANNEL=msedge`. El proyecto Docker de esta verificación se llama `investitrack-validation` y el comando equivalente del verificador es `python infra/verify-workflow.py --project investitrack-validation`.

## Credenciales que requieren revisión o rotación

Rota contraseñas activas de PostgreSQL y RabbitMQ, secretos para intercambio u obtención de tokens y tokens de workload que se hayan usado en los `.env` retirados. Revoca las sesiones y rota la contraseña de las cuentas que se usaron para generar los JWT registrados en `Backend/API-gateway/http/http-client.env.json`. Revisa el alcance de reutilización antes de rotar fuera de local. El retiro del índice no elimina los valores de commits previos. Este trabajo conserva el historial y no hizo commits, pushes ni despliegues públicos.

## Evidencia

La validación usa datos ficticios. Los logs y las comprobaciones de HTTP omiten contraseñas y JWT.

- **Infraestructura.** `docker compose config --quiet` y `docker compose --progress plain -p investitrack-validation up --build -d --wait` terminaron con código 0. `docker compose ps -a` mostró 13 contenedores sanos y dos inicializadores en `Exited (0)`. Keycloak registró `Realm 'investitrack' imported`, Identity registró el aprovisionamiento y MinIO confirmó la creación de `local/documents`.
- **Migraciones y acceso a bases.** Flyway aplicó 3 migraciones en Identity, 7 en Proposal, 2 en Project y 1 en cada uno de Document, Report y Audit. La matriz de `has_database_privilege` confirmó conexión exclusiva a la base propia para los siete roles, incluido Keycloak.
- **API y seguridad.** El verificador recibió `200` en el login y `/me`, `201` al crear la propuesta, la asignación y la evaluación, y `200` al enviar y aprobar. Registró `401` en perfil e interno sin Bearer, `403` en operaciones fuera del rol y `404` al consultar un proyecto ajeno y al llamar la ruta interna desde Nginx. IA y notificaciones devolvieron `503`.
- **RabbitMQ y persistencia.** El verificador encontró la cola durable `project.domain-events`, el enlace `proposal.ProposalApproved.v1` y `project.invalid-events` enlazada a `project.dead-letters`. La propuesta `2ac7385c-07c8-419c-abbe-4036553789f5` publicó el evento `0e121f56-21a0-4340-9257-d6cde9e47316`. Project registró `SUCCEEDED` y persistió el proyecto `ff102ebe-c6b6-4170-8a4b-cb305c64edda`. Tras reenviar el evento se mantuvieron un proyecto y un registro de consumo. El mismo proyecto respondió `200` después de reiniciar Proposal y Project.
- **Pruebas de código.** Build de React, ESLint, 134 pruebas Vitest con dos workers y pruebas Maven del Gateway en JDK 25 finalizaron correctamente.
- **Navegador.** Playwright con Edge terminó con `1 passed`. Comprobó `response_type=code` y PKCE S256 en la solicitud a Keycloak, el intercambio de código, `/me`, el cierre de sesión entre roles, la creación y envío de una propuesta, la asignación, el dictamen y la aprobación. En el frontend se abrió el proyecto `20d5c353-bfb5-4cc2-a131-bfd0d802cd42`, generado a partir de la propuesta `a41adcca-2060-4b34-afdc-0a50d650c561`. El observador autenticado recibió la pantalla de acceso restringido al abrir ese mismo proyecto.

## Pendientes

- Implementar servicios de IA y notificaciones, y completar las pantallas y credenciales de trabajo de las operaciones secundarias identificadas en la matriz.
- Evaluar el registro y descubrimiento dinámico si la rúbrica lo requiere. Unificar `infraestructure.persistence` cuando se pueda validar la suite Java completa del servicio Proposal.
- Probar por separado el vencimiento forzado y la renovación del token del navegador. La prueba de Edge cubrió el inicio y cierre de sesión entre perfiles.
- Rotar cualquier credencial reutilizada y acordar por separado el tratamiento del historial si corresponde.
