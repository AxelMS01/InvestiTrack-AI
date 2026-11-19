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

## Recorrido de demostración V2

- **Andrea, administración.** Abre **Unidades académicas** para registrar o editar unidades y gestionar vínculos. El directorio, los roles y la auditoría se encuentran en el mismo panel.
- **Carlos, coordinación ambiental y social.** En **Mi coordinación** puedes elegir unidad. Revisa la solicitud de Mateo y autorízala. En un proyecto supervisado, abre **Asignar evaluador**, elige a Lucía y confirma. **Mariana** coordina salud e ingeniería y permite probar el aislamiento entre unidades.
- **Elena, investigación.** En **Mis proyectos → Participantes** consulta el equipo y propón estudiantes. El proyecto de monitoreo tiene una solicitud pendiente de ejemplo. Para generar otra, completa primero el flujo de una propuesta y conviértela. En una evidencia entregada, abre **Retroalimentación** y solicita correcciones.
- **Sofía, estudiante.** En **Mis actividades** abre «Registrar mediciones de campo». Crea una evidencia con su propio nombre, adjunta archivos, guarda y confirma la entrega. **Mis entregas** muestra documentos e historial por versión. **Mis calificaciones** incluye únicamente las publicadas. **Mateo** pasa a ser miembro cuando coordinación confirma su solicitud y requiere una actividad asignada para entregar.
- **Daniel, evaluación.** En **Evidencias pendientes** abre la entrega. Inicia la evaluación, consulta los archivos, completa la rúbrica de demostración y registra la nota. **Lucía** parte sin asignación y el proyecto aparece al confirmar su designación. Elena puede cambiar al espacio de evaluación desde la misma cuenta, con los mismos permisos y restricciones de conflicto de interés.

El catálogo inicial incluye entregas con nota publicada, con nota reservada y pendiente de evaluación. Las nuevas calificaciones demo se registran sin publicar. El simulador y la cabecera identifican el uso de datos ficticios.

La demostración conserva registros en `invesctictrack.demo.workspace.v2` y archivos en la base IndexedDB `invesctictrack.demo.files`. El historial de chat vive en memoria. Las demostraciones previas se conservan y los archivos históricos se consultan en el repositorio. Una demostración nueva carga los datos de ejemplo V2.

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

Este ajuste habilita el transporte histórico del cliente para consultas y sesión por cookie. El repositorio contiene el servicio Spring de propuestas con contrato JWT, paginación y ETag. Su conexión requiere el flujo de identidad y el gateway institucionales, además del mapeo de sus DTO. Las extensiones V2 todavía requieren servicios de identidad, proyectos y documentos. El frontend indica la integración pendiente al acceder a operaciones exclusivas de demostración. Las rutas directas requieren una regla de fallback a `index.html` al desplegar archivos estáticos.

Consulta [la matriz V2 y los contratos pendientes](../docs/frontend-v2-progress.md) y [la arquitectura del frontend](./ARCHITECTURE.md).
