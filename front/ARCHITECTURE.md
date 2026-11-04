# Arquitectura modular de InvescticTrack

## Responsabilidades

- `components/`: controles visuales reutilizables. `Button`, `Input`, `Card`, `Badge`, `Table` y `Modal` aceptan contenido y propiedades nativas tipadas. Las etiquetas y validaciones pertenecen al formulario que los utiliza. `Modal` usa el diálogo nativo para foco, Escape y bloqueo del fondo. `ui.tsx` reúne marca, encabezados, progreso, estado de proyecto y estados de consulta.
- `layouts/`: `AuthLayout` comparte la composición de login y sesión expirada. `DashboardLayout` aloja navegación, contenido y pie; permite abrir/cerrar el menú móvil y colapsar/expandir la barra de escritorio. `InstitutionalHeader` recibe usuario y acciones por propiedades.
- `router/`: `public-routes.tsx` contiene login y 401; `protected-routes.tsx` contiene pantallas autenticadas y restricciones por rol. `RequireAuth` distingue sesión ausente, HTTP 401, fallo de conexión y rol insuficiente. La página 403 pertenece a `features/auth/`. `index.tsx` compone el router.
- `types/`: contratos compartidos del dominio y validación Zod. Exporta `User`, `Project`, `Proposal`, `Evaluation`, `Activity` y `Document` desde `index.ts`. Se infieren los tipos de sus esquemas para evitar duplicar interfaces que puedan divergir de la validación en ejecución. Importar `Document` explícitamente evita confundirlo con el tipo DOM del navegador.
- `api/`: transporte, validación de respuestas, APIs por recurso, claves de caché, queries y mutaciones. `http-client.ts` es el único punto de acceso HTTP. `auth.*`, `projects.*` y `proposals.*` implementan contratos real/demo; `assistant.*` simula respuestas con alcance autorizado. `resources.*` prepara consultas para evaluaciones, actividades y documentos.
- `features/`: páginas, componentes específicos, reglas locales y catálogo de vistas de cada módulo. La sesión se consulta mediante `api/auth.queries.ts`; los formularios no realizan `fetch` directamente.
- `app/`: configuración de infraestructura de la aplicación, actualmente `QueryClient`.
- `lib/`: configuración del entorno y formatos reutilizables sin lógica de transporte.

Los contratos compartidos no importan módulos visuales. Los componentes base no importan APIs. Los hooks de consulta administran el estado del servidor; el estado de menús y formularios permanece local. Los tipos globales no son un contenedor para todas las propiedades de componentes.

## Diez módulos

| Módulo         | Vistas previstas                                               | Estado actual                                                                      |
| -------------- | -------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| `auth`         | Login, 403, 401                                                | Implementadas                                                                      |
| `dashboard`    | General, investigador, coordinador, notificaciones, perfil     | Panel general implementado; demás vistas planificadas                              |
| `proposals`    | Lista, crear, detalle, editar, historial                       | Lista, formulario complejo y detalle implementados; edición e historial pendientes |
| `evaluations`  | Pendientes, asignar, rúbrica, resultados, decisión             | Catálogo y contratos                                                               |
| `projects`     | Lista, detalle, configuración, colaboradores, seguimiento      | Lista, registro y detalle implementados; demás vistas planificadas                 |
| `activities`   | Lista, crear/editar, detalle, hitos, calendario                | Catálogo y contratos                                                               |
| `documents`    | Repositorio, subir, detalle, evidencias, versiones             | Catálogo y contratos                                                               |
| `ai-assistant` | Chat, fuentes verificadas, historial                           | Pantallas 34, 35 y 36 implementadas con simulación y aislamiento por perfil        |
| `reports`      | Indicadores, exportación, informe final, publicaciones, cierre | Catálogo; contratos de reportes pendientes                                         |
| `admin`        | Usuarios, roles, auditoría, configuración                      | Consulta de usuarios implementada; demás vistas planificadas                       |

`features/modules.ts` reúne los diez descriptores tipados. Cada `module.ts` incluye identificador, etiqueta, rutas propuestas y estado por vista. Este catálogo documenta el alcance; no registra rutas automáticamente ni concede permisos. Las restricciones de las vistas futuras son propuestas que se deben concretar al implementar el módulo. Los módulos sin pantallas implementadas contienen su catálogo, sin páginas vacías ni operaciones simuladas.

## Convenciones para ampliar un módulo

1. Añadir la página `.tsx` en su carpeta de `features/` siguiendo los nombres existentes (`proposals-page.tsx`, `proposal-create-page.tsx`, etc.). Al crecer el módulo, agrupar sus páginas bajo `pages/`, sus componentes específicos bajo `components/` y validaciones exclusivas de formularios bajo `schemas/`.
2. Definir o ampliar el esquema del dominio en `types/`; mantener los esquemas de formularios exclusivos dentro del módulo cuando no se comparten.
3. Definir la operación de transporte en `api/<recurso>.api.ts` y el hook en `api/<recurso>.queries.ts`. Usar claves por recurso e identificador; invalidar las consultas afectadas tras una mutación.
4. Acordar los endpoints con el backend antes de activar un hook nuevo. `resources.api.ts` propone `GET /evaluations`, `/activities`, `/documents` y sus variantes `/:id`, con objetos/listas directos. Los hooks de estos recursos están desactivados por defecto (`enabled = false`). Propuestas utiliza `proposals.api.ts`: lista y detalle requieren `details` validado con `proposalInputSchema`, y el POST recibe ese formulario sin confiar en propietario o departamento enviados por el cliente.
5. Registrar la ruta en `router/protected-routes.tsx` con carga diferida y el guard de roles que corresponda. Las rutas públicas se declaran en `public-routes.tsx`. Actualizar el descriptor a `implemented` y agregar navegación cuando el flujo esté completo.
6. Probar el comportamiento relevante, incluidos errores y acceso por rol. El catálogo se verifica contra las rutas activas para detectar vistas declaradas implementadas sin ruta.

## Autenticación y permisos

`AuthProvider` envuelve el router y expone `AuthContext`/`useAuth`. TanStack Query conserva la sesión como fuente de datos; el contexto coordina el simulador y su transición. Cambiar de perfil cancela consultas, elimina recursos de la caché anterior, conserva el observador de sesión y vuelve a montar las rutas protegidas con una clave por identidad, rol, departamento y asignaciones. La elevación de roles se permite únicamente en demostración; `authApi.demoLogin` la rechaza fuera de ese modo.

`canReadProject` centraliza el alcance: administración institucional, coordinación/investigación departamental y evaluador por asignaciones explícitas. Un departamento ausente deniega acceso departamental. En demo, se aplica tanto a catálogo y detalle como a la recuperación del asistente. En producción estas comprobaciones deben ejecutarse en el backend antes de devolver registros.

El asistente no invoca un LLM. Construye un resumen determinista solo a partir de fichas autorizadas y conserva las fuentes exactas utilizadas. Revalida el perfil antes de almacenar una respuesta, separa historial por ámbito y verifica la autorización de las fuentes al recuperar una conversación o URL de respuesta. Las comprobaciones textuales permiten rechazar consultas fuera del ámbito, pero la frontera de acceso está en la selección de datos, independientemente del texto del usuario.

Crear Propuesta usa React Hook Form, `useFieldArray`, `useWatch` y Zod. El mismo esquema se valida otra vez en la API simulada. Incluye correos únicos, partidas a dos decimales, límite de presupuesto, financiamiento coherente con el desglose, fechas ordenadas y declaración ética condicional. Responsable y departamento se asignan desde la sesión. Los colaboradores declarados no reciben permisos ni invitaciones automáticamente.

Una respuesta `null` de sesión redirige a login conservando la ruta solicitada. HTTP 401 abre `/sesion-expirada`; esa página permite volver a login y descartar caché de la sesión anterior. Un rol insuficiente redirige a `/sin-acceso`. Otros errores muestran una opción para reintentar, evitando presentarlos como cierre de sesión.

El backend debe comprobar permisos y visibilidad de cada registro. El guard y la navegación son mecanismos de interfaz. Las operaciones futuras de decisiones, colaboradores, cambios de roles, documentos y cierre deben tener políticas explícitas en el servidor antes de habilitarse.

## Verificación

Ejecutar `npm run typecheck`, `npm run lint`, `npm test`, `npm run build` y `npm run format:check`. Las pruebas cubren permisos, 401, sincronización del catálogo con rutas activas, colapso de la barra, contratos HTTP, validaciones y registro completo de proyectos en modo demo.
