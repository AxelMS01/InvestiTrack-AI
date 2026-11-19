# Arquitectura modular de InvescticTrack

- `components/` y `layouts/` conservan los controles, la cabecera, la barra lateral y los estilos institucionales. `features/dashboard/navigation.ts` define los destinos por espacio de trabajo.
- `router/` separa rutas públicas y protegidas. `RequireAuth` verifica la sesión y capacidades de la ruta. Los módulos cargan sus páginas de forma diferida.
- `types/` contiene esquemas Zod para usuarios, proyectos, propuestas, evaluaciones, actividades y documentos. Los esquemas de cada formulario se mantienen junto al flujo correspondiente.
- `api/` contiene el transporte HTTP histórico, la validación de respuestas y las consultas y mutaciones de TanStack Query. El cliente usa cookies y errores traducidos a español.
- `demo/` mantiene los registros ficticios y comprueba identidad, permisos, versiones y transiciones. Persiste los agregados relacionados en una clave de localStorage y guarda archivos en IndexedDB. Las mutaciones de interfaz se serializan entre pestañas cuando Web Locks está disponible.
- `features/*/module.ts` declara pantallas y estado de implementación. [La matriz](../docs/frontend-progress.md) identifica el módulo, ruta, archivo, rol, adaptador y pruebas.

## Identidad y alcance

La sesión normaliza los identificadores institucionales `ADMIN`, `COORDINATOR`, `RESEARCHER`, `EVALUATOR` y `STUDENT`. El adaptador admite el campo histórico `role` y convierte los identificadores minúsculos al contrato institucional. Las capacidades recibidas del servidor pueden restringir las acciones habilitadas por roles.

El simulador demo cambia la identidad completa. El selector de espacio de trabajo usa únicamente roles ya asignados y cambia la perspectiva de navegación. Las consultas privadas usan claves por sujeto. Cambiar de cuenta o cerrar sesión descarta los datos en caché. Las consultas a archivos y recursos comprueban de nuevo los permisos en el adaptador.

La visibilidad de proyectos usa investigación principal, coordinación asignada o membresía con responsabilidad compatible. El evaluador consulta propuestas asignadas y puede recuperar sus evaluaciones enviadas según su asignación. El administrador gestiona cuentas, roles y auditoría. Los datos de la prueba están disponibles en el almacenamiento del navegador. En un despliegue institucional, la autorización corresponde al servidor.

## Flujos de estado

- Una propuesta en borrador admite guardado parcial. Enviar requiere una validación completa y registra una versión de entrega. Una decisión de corrección habilita campos específicos. Reenviar inicia una ronda de evaluación y revoca el acceso a la versión actual de las asignaciones anteriores.
- La aprobación precede a la conversión en un proyecto vinculado. La coordinación que aprobó queda como responsable del seguimiento. Se impide volver a convertir la misma propuesta.
- El equipo y las actividades se gestionan dentro del alcance del proyecto. Los avances se recalculan al actualizar actividades. Un informe final vigente debe presentarse antes de que la coordinación asignada pueda cerrar el proyecto.
- Los documentos se validan en el navegador y se abren mediante una URL de objeto temporal. El asistente de demostración presenta resúmenes deterministas de registros autorizados y referencias de catálogo a documentos. El historial se vuelve a filtrar cuando pierde vigencia un permiso.

## Extensión V2

La coordinación usa unidades autorizadas y supervisión del proyecto. La asignación de seguimiento habilita al evaluador solo sobre el proyecto asignado y se comprueba junto con los conflictos de interés. La aprobación de una solicitud activa la membresía y registra la decisión en la misma actualización del adaptador.

El agregado de evidencia tiene su propio código, título, estados y versiones académicas. Las versiones apuntan a archivos con metadatos de documento y contenido en IndexedDB. El visualizador usa URLs temporales y las revoca al salir. Las revisiones y calificaciones son registros independientes. El adaptador compara versiones antes de escribir y solo devuelve notas publicadas a su estudiante.

## Transporte y verificación

El documento [api-endpoints.md](../Backend/.agents/api-endpoints.md) describe los contratos institucionales esperados. El cliente histórico está aislado del adaptador demo. El servicio de propuestas existente usa JWT de OIDC, paginación y ETag. La extensión V2 sigue en contrato propuesto. La conexión institucional requiere un gateway operativo, DTO verificados y pruebas de integración. Consulta [el estado V2](../docs/frontend-v2-progress.md).

Ejecuta los comandos de calidad y el recorrido descritos en [README.md](./README.md).
