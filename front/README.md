# InvescticTrack · Frontend

Base institucional en React 19, TypeScript estricto, Vite, Tailwind CSS 4, React Router 7 (API declarativa de rutas compatible con v6), React Hook Form, Zod y TanStack Query 5. Interfaz en español, adaptable a móviles y escritorio.

## Desarrollo

Requiere Node.js 22.12+ (o una versión LTS posterior compatible).

```powershell
cd front
npm install
Copy-Item .env.example .env
npm run dev
```

Abre la dirección indicada por Vite. Con `.env.example`, el modo de demostración permite elegir un rol en el login sin credenciales reales. La sesión vive en memoria y se cierra al recargar; los proyectos ficticios se guardan en `localStorage` bajo `invesctictrack.demo.projects.v1`. Eliminar esa clave restablece los datos iniciales. No almacenes información institucional real en este modo.

```powershell
npm run typecheck
npm run lint
npm test
npm run build
npm run preview
```

## Organización

```text
src/
  app/                   # QueryClient y configuración de la aplicación
  components/            # Button, Input, Modal, Table, Badge, Card y estados UI
  layouts/               # DashboardLayout, AuthLayout, InstitutionalHeader
  router/                # Rutas públicas/protegidas, guard, errores y pruebas
  types/                 # User, Project, Proposal, Evaluation, Activity, Document
  api/                   # Cliente HTTP, APIs tipadas y hooks de TanStack Query
  features/
    auth/                # Login, 401, 403 y permisos
    dashboard/           # Panel general y catálogo de vistas por perfil
    proposals/           # Lista, crear, detalle, editar e historial
    evaluations/         # Pendientes, asignar, rúbrica, resultados y decisión
    projects/            # Catálogo, registro, detalle y catálogo de seguimiento
    activities/          # Lista, crear/editar, detalle, hitos y calendario
    documents/           # Repositorio, subir, detalle, evidencias y versiones
    ai-assistant/        # Chat, fuentes verificadas e historial
    reports/             # Indicadores, exportación, informe, publicaciones y cierre
    admin/               # Usuarios, roles, auditoría y configuración
  lib/                   # Variables de entorno y formatos es-MX
  test/                  # Configuración de pruebas
```

Los esquemas Zod son la fuente del tipado del dominio. Los componentes consumen hooks de consultas/mutaciones; las funciones de API encapsulan el transporte y validan las respuestas en tiempo de ejecución. Los indicadores se calculan a partir de los proyectos recibidos, sin métricas simuladas independientes. Las páginas se cargan bajo demanda.

Cada módulo contiene un `module.ts` con sus vistas y el estado `implemented` o `planned`. El catálogo se reúne en `features/modules.ts`; únicamente las pantallas implementadas tienen rutas activas. Los módulos pendientes cuentan con estructura y contratos según su estado documentado, no con flujos terminados. Consulta [ARCHITECTURE.md](./ARCHITECTURE.md) para responsabilidades, convenciones y extensión.

## Rutas y permisos iniciales

| Ruta               | Acceso                                                   |
| ------------------ | -------------------------------------------------------- |
| `/sesion-expirada` | Público: página 401 tras expirar la sesión               |
| `/login`           | Público                                                  |
| `/`                | Cualquier sesión: panel general                          |
| `/proyectos`       | Cualquier sesión: catálogo con búsqueda y filtros en URL |
| `/proyectos/:id`   | Cualquier sesión: ficha del proyecto                     |
| `/proyectos/nuevo` | Administración, coordinación e investigación             |
| `/usuarios`        | Administración: consulta de directorio                   |
| `/sin-acceso`      | Cualquier sesión                                         |

El simulador global `AuthContext` permite alternar entre Investigador, Coordinador, Evaluador y Administrador desde el header en modo demo. Los permisos de acciones están centralizados en `features/auth/model.ts` y el alcance de proyectos en `features/auth/access-policy.ts`. Investigador y coordinador consultan proyectos de su departamento; el evaluador consulta proyectos asignados; administración tiene acceso institucional. Registros sin departamento no son visibles mediante la política departamental. La navegación y los guards se actualizan al cambiar de perfil y se descarta la caché de recursos del perfil anterior. En producción, el selector está deshabilitado y el backend debe aplicar las mismas políticas.

## Probar los nuevos flujos

1. Inicia la demostración y usa **Rol institucional activo** en el header para cambiar de perfil.
2. Abre **Asistente IA** (`/asistente`, pantalla 34). El investigador de ejemplo pertenece a Ciencias ambientales y puede consultar `INV-2026-001` y `INV-2026-005`. El coordinador pertenece a Salud; el evaluador tiene asignado `INV-2026-003`. Administración puede consultar todo el portafolio.
3. Selecciona un proyecto o consulta el ámbito autorizado. Cada respuesta muestra tarjetas desplegables con extractos de respaldo y enlaces a la ficha. `/asistente/respuestas/:id/fuentes` corresponde a la pantalla 35 y `/asistente/historial` a la 36. Las URLs e historial se verifican de nuevo con el perfil activo. No se comparten conversaciones entre perfiles.
4. Como investigador, coordinador o administrador, entra a **Propuestas → Crear propuesta** (`/propuestas/nueva`). El formulario incluye protocolo, equipo dinámico, presupuesto por partidas, financiamiento, fechas e integridad ética. Valida correos únicos, límite total de un millón de MXN, financiamiento menor o igual al desglose, importes a dos decimales y aprobación ética si participan personas. Tras enviarlo puedes consultar la ficha registrada.

El chat es una simulación determinista con fuentes ficticias del propio portafolio, sin conexión a un modelo ni consultas externas. Sus referencias son instantáneas de fichas autorizadas, no publicaciones científicas verificadas. Conserva las últimas 50 respuestas por perfil en memoria hasta recargar. Las propuestas de demostración se conservan en `localStorage` bajo `invesctictrack.demo.proposals.v1`.

Los proyectos antiguos guardados sin `departmentId` permanecen visibles para administración y se ocultan a los perfiles departamentales. Para recuperar las semillas demo con departamentos, elimina únicamente la clave de proyectos `invesctictrack.demo.projects.v1` del navegador; esto también descarta los proyectos demo guardados en esa clave.

## Conectar el backend

Configura `.env` y reinicia Vite:

```dotenv
VITE_DEMO_MODE=false
VITE_API_URL=/api
```

Sin `VITE_DEMO_MODE=true` la aplicación utiliza la API real. Las variables `VITE_*` son públicas: nunca contienen secretos. El repositorio no incluye todavía backend funcional; los siguientes contratos son la propuesta de integración, no endpoints implementados por este frontend.

| Método | Endpoint         | Respuesta JSON                                        |
| ------ | ---------------- | ----------------------------------------------------- |
| GET    | `/auth/me`       | `User`, `null` o HTTP 401 sin sesión                  |
| POST   | `/auth/login`    | `User`; recibe `{ email, password }`                  |
| POST   | `/auth/logout`   | HTTP 204 sin cuerpo                                   |
| GET    | `/projects`      | `Project[]` visibles para la sesión                   |
| GET    | `/projects/:id`  | `Project` o HTTP 404                                  |
| POST   | `/projects`      | `Project` creado; recibe `ProjectInput`               |
| GET    | `/users`         | `User[]`; solo administración                         |
| GET    | `/proposals`     | Propuestas autorizadas con `details` y `departmentId` |
| GET    | `/proposals/:id` | Propuesta autorizada o HTTP 404                       |
| POST   | `/proposals`     | Propuesta creada; recibe `ProposalInput`              |

Se esperan objetos y listas directos, sin envoltorio `data`. Consulta los esquemas en `types/user.ts` y `types/project.ts` para los campos exactos y enumeraciones. Fechas: `YYYY-MM-DD`; moneda: MXN; avance: 0–100. El backend asigna ID, código, responsable, estado inicial y avance a partir de la sesión, sin confiar en valores enviados por el cliente.

El cliente usa `fetch` con `credentials: 'include'`, pensado para sesiones por cookie HttpOnly, Secure y SameSite. En producción, el servidor debe comprobar autenticación, permisos por rol y alcance de cada registro, además de aplicar protección CSRF (validación de Origin y política de cookies adecuada; si exige token CSRF, añadir el encabezado en `api/http-client.ts`). La protección del router solo controla la interfaz. Para orígenes distintos, configura CORS con origen explícito y credenciales. Un proxy de desarrollo o reverse proxy debe dirigir `/api` al backend.

Para desplegar, publica `dist/` con una regla de fallback a `index.html` para las rutas del frontend, excluyendo `/api`. El modo demo es exclusivamente para exploración; no representa autenticación de producción.

## Componentes y calidad

Incluye menú lateral adaptable, cabecera, indicadores, distribución por área, fechas de cierre, tabla de proyectos, búsqueda y filtros, ficha, formulario validado, selector de perfiles demo, directorio, estados de carga/error/vacío y recuperación de errores. Controles con etiquetas, errores asociados, foco visible, enlace para saltar al contenido y respeto a movimiento reducido. Las tablas permiten desplazamiento horizontal en pantallas pequeñas.

Pruebas: acceso anónimo y por rol a rutas, validación de fechas/presupuesto/textos, y flujo de creación/persistencia/consulta en modo demo. La evaluación de proyectos, edición, adjuntos, auditoría, paginación del servidor y administración de altas/bajas quedan como módulos futuros, no controles simulados.

Referencias de integración: [Tailwind con Vite](https://tailwindcss.com/docs/installation/using-vite) y [consultas de TanStack Query](https://tanstack.com/query/latest/docs/framework/react/guides/queries).
