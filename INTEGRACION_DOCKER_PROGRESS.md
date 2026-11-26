# Progreso de integración Docker

| Fase | Cambios y verificación |
| --- | --- |
| Inventario | Se revisó el PDF del profesor y se identificaron seis servicios de dominio, Gateway, frontend y dos propietarios de rutas planificados. |
| Infraestructura | Se conservó el Compose raíz en curso y se corrigieron redes, health checks, MinIO, PostgreSQL y el realm dedicado. `docker compose config --quiet` pasó. |
| Mensajería y acceso | Se mantuvo `ProposalApproved` con outbox, confirmación, inbox y unicidad. Se configuraron clientes confidenciales y se implementó el login institucional con PKCE S256. |
| Frontend | Se conectaron las pantallas del recorrido principal a las APIs reales. Build y 134 pruebas de React aprobados. |
| Higiene | Se retiraron del índice los entornos sensibles, IDE y artefactos. Se conservaron las copias locales. |
| Validación | Compose sano, verificador HTTP con entrega duplicada y persistencia, y recorrido con PKCE en Edge completados. Consulta la [evidencia de ejecución](./RESOLUCION_FEEDBACK_PROFESOR.md#evidencia). |

La [matriz de integración](./docs/matriz-integracion-frontend.md) identifica las operaciones del frontend que tienen contrato institucional y las que están pendientes.
