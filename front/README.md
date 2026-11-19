# InvescticTrack · Frontend

Interfaz institucional en español con React 19, TypeScript, React Router, TanStack Query, React Hook Form, Zod, Vite y Tailwind CSS.

## Desarrollo

Requiere Node.js 22.12 o posterior. Desde la raíz del repositorio:

```powershell
cd front
npm ci
Copy-Item .env.example .env
npm run dev
```

Si las dependencias ya están instaladas, basta con levantar Vite. Copia `.env.example` cuando necesites crear la configuración local. Abre la dirección que imprime Vite.

`.env.example` habilita la demostración. En el login selecciona uno de los cinco perfiles. El **Simulador de perfiles** cambia la cuenta ficticia y limpia su caché privada. En el perfil de Elena, **Espacio de trabajo** alterna investigación y evaluación dentro de la misma cuenta. Volver a cargar la pestaña cierra la sesión demo.

## Recorrido de demostración

1. Entra como **Investigador**. Crea una propuesta, guarda un borrador, complétalo y confirma el envío desde la revisión. Desde un borrador puedes adjuntar el protocolo.
2. Cambia a **Coordinador**. Abre **Propuestas recibidas**, asigna la propuesta a Daniel y configura una fecha límite.
3. Cambia a **Evaluador**. Abre la rúbrica desde **Evaluaciones pendientes**, califica los criterios, guarda un borrador si deseas volver después y envía la evaluación. El catálogo inicial incluye una asignación disponible para practicar.
4. Vuelve a **Coordinador**. Consulta los resultados. Registra una decisión, por ejemplo aprobar. Si solicitas modificaciones, selecciona los campos que podrá cambiar el investigador.
5. Convierte la propuesta aprobada desde **Proyectos supervisados → Convertir propuesta**. Como investigador responsable, abre el proyecto, agrega a Sofía como estudiante y crea un hito y una actividad asociada. Usa fechas dentro del periodo aprobado.
6. Entra como **Estudiante**. Abre **Mis actividades**, entrega una evidencia y actualiza el estado de la tarea. Los archivos autorizados se pueden consultar y descargar desde el detalle.
7. Como **Investigador**, abre **Informe final**, carga un PDF y confirma su presentación. Como **Coordinador** asignado, abre **Reportes y cierre** y confirma el cierre cuando el trabajo esté resuelto.
8. Como **Administrador**, usa **Usuarios**, **Asignación de roles** y **Auditoría** para probar la administración de accesos.
9. Desde **Asistente IA**, consulta el estado de un proyecto o propuesta autorizados. Si el recurso tiene archivos, el chat permite abrirlos desde sus referencias de catálogo.

La demostración guarda sus registros en `invesctictrack.demo.workspace.v2` y sus archivos en la base IndexedDB `invesctictrack.demo.files`. El historial de chat vive en memoria. Para reiniciar datos ficticios usa los controles de almacenamiento del navegador sobre el origen local. Las claves antiguas de proyectos y propuestas se importan al iniciar una demostración sin estado nuevo.

## Comprobaciones

```powershell
npm run typecheck
npm run lint
npm run format:check
npm run test
npm run build
```

## Modo institucional

```dotenv
VITE_DEMO_MODE=false
VITE_API_URL=/api
```

Este ajuste habilita el transporte histórico del cliente para consultas y sesión por cookie. El servidor con controladores REST, identidad institucional y autorización de recursos es un requisito para el modo institucional. Las nuevas operaciones muestran su estado de integración pendiente. Las rutas directas requieren una regla de fallback a `index.html` al desplegar archivos estáticos.

Consulta [la matriz y los contratos pendientes](../docs/frontend-progress.md) y [la arquitectura del frontend](./ARCHITECTURE.md).
