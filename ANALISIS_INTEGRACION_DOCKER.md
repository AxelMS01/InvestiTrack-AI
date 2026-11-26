# Análisis de integración Docker y Cloudflare Tunnel

Este documento conserva el diagnóstico previo a las correcciones. El perfil integrado y sus resultados se describen en el [README raíz](./README.md) y el [informe de resolución](./RESOLUCION_FEEDBACK_PROFESOR.md).

**Fecha:** 2026-11-25  
**Alcance:** auditoría estática y pruebas no destructivas. No se modificó código fuente, no se creó un Compose operativo, no se iniciaron contenedores y no se descargaron imágenes.

## 1. Estado real

El repositorio contiene siete aplicaciones backend Java: API-gateway, identity-service, proposal-service, project-service, document-service, report-service y audit-service. También contiene Keycloak local, frontend React/Vite y configuraciones AMQP/S3. ai-service está vacío y notification-service no existe.

Los Dockerfiles backend son multi-stage con Temurin 25, Maven 3.9.11 o Gradle Wrapper, usuario no root y puertos internos distintos. No existe Dockerfile frontend ni Compose raíz. Los Compose existentes son parciales: cada uno crea su PostgreSQL y usa la red externa investitrack. RabbitMQ y MinIO no son creados por ellos.

**Dictamen:** integración local completa viable como trabajo posterior; exposición temporal de $0 viable con frontend demo; plataforma institucional completa todavía no demostrable end-to-end.

## 2. Pruebas realmente ejecutadas

| Prueba | Resultado |
|---|---|
| Node.js/npm | v24.19.0 / 11.10.0 |
| front/node_modules | Existente |
| Cachés Maven/Gradle | Existentes |
| Docker CLI/daemon | No disponible en PATH |
| docker compose config | Pendiente por ausencia de Docker |
| npm run typecheck | **Pasó** |
| npm test | No inició Vitest: spawn EPERM al iniciar esbuild |
| Builds Java/Docker | Pendientes; no se forzó descarga |
| Despliegue público | No realizado |

El EPERM de Vitest es una limitación del entorno de ejecución, no un diagnóstico funcional concluyente.

## 3. Diagrama de dependencias

    Browser -> Nginx -> frontend
                    -> API-gateway:8080
                    -> Keycloak realm público

    API-gateway -> identity:8081 -> PostgreSQL identity_db
                 -> proposal:8082 -> PostgreSQL proposal_db
                 -> project:8083 -> PostgreSQL project_db
                 -> document:8084 -> PostgreSQL document_db + MinIO
                 -> report:8085 -> PostgreSQL reporting_db
                 -> audit:8087 -> PostgreSQL audit_db
                 -> AI:8086 (inexistente)
                 -> Notification:8088 (inexistente)

    Servicios -> Keycloak/OIDC
    Servicios con eventos -> RabbitMQ
    cloudflared -> Nginx

Hechos verificados:

- application.properties del gateway define Identity, Proposal, Project, Document, Reporting, AI, Audit y Notification en las líneas 18-25.
- routes.tsv declara AI y Notification en las líneas 119-143 aunque sus servicios no existen.
- Proposal depende de Identity y Document; Project de Identity/Proposal/Document; Reporting de Identity/Project/Proposal; Document de Identity/Project.
- Todos los servicios de dominio dependen de PostgreSQL y JWT/OIDC.
- Document depende adicionalmente de S3-compatible.

## 4. Inventario y exposición

| Contenedor | Puerto interno | Exposición propuesta |
|---|---:|---|
| frontend | 80 | Solo Nginx |
| api-gateway | 8080 | Solo Nginx |
| identity/proposal/project/document/report/audit | 8081/8082/8083/8084/8085/8087 | Red privada |
| keycloak | 8080 | Solo realm mediante Nginx |
| postgres | 5432 | Privado |
| rabbitmq | 5672/15672 | Privado |
| minio | 9000/9001 | S3 privado; consola localhost |
| nginx | 80 | Único origen del túnel |
| cloudflared | salida HTTPS | Sin entrada directa |

No hay conflicto de puertos internos entre las aplicaciones. Los riesgos actuales son PostgreSQL duplicado, red externa ausente, nombres explícitos que pueden colisionar, defaults localhost y healthchecks incompletos.

## 5. Revisión Docker/Compose

Audit, Document, Project, Proposal y Report crean PostgreSQL, volumen, aplicación y red externa investitrack. Solo esperan la salud de su DB. No esperan Keycloak, RabbitMQ, MinIO ni llamadas HTTP de otros servicios.

Keycloak usa Keycloak 26.8.2, start-dev, KC_HOSTNAME=http://localhost:8180, publica 127.0.0.1:8180:8080 y monta el realm. Es local, no configuración de túnel.

El Compose raíz propuesto debe centralizar:

- una PostgreSQL 16 con seis bases/usuarios;
- RabbitMQ con exchange/vhost/ACL;
- MinIO con bucket privado;
- Keycloak;
- los siete servicios;
- frontend, Nginx y cloudflared;
- redes backend y edge;
- volúmenes y healthchecks.

No se implementa en esta etapa.

## 6. Orden de arranque

1. Red y volúmenes.
2. PostgreSQL; esperar pg_isready y crear seis bases/usuarios.
3. Keycloak; esperar discovery/JWKS.
4. RabbitMQ; esperar AMQP.
5. MinIO; crear bucket privado documents.
6. Identity; esperar Flyway/liveness.
7. Proposal, Project y Document.
8. Reporting y Audit.
9. Seeds y rebuilds de Reporting.
10. Gateway.
11. Frontend/Nginx.
12. Cloudflare Tunnel.

depends_on no sustituye healthchecks HTTP. Reporting requiere bootstrap/rebuild y RabbitMQ no es histórico.

## 7. Keycloak y autenticación

El realm investitrack y el cliente investitrack-web están en Backend/keycloak/investitrack-realm.json. El cliente web usa Authorization Code + PKCE S256 y redirects/origen http://localhost:3000. Hay roles ADMIN, COORDINATOR, RESEARCHER, EVALUATOR, STUDENT y AUDITOR, con claim roles.

Los servicios validan issuer, firma, expiración, audiencia y scopes. Identity mantiene roles de aplicación en PostgreSQL; Keycloak no sincroniza automáticamente esas asignaciones.

**Bloqueo:** auth.api.ts del frontend institucional consulta /auth/me, mientras gateway/routes.tsv declara /api/v1/me. El frontend demo simula login y no se observó un cliente OIDC browser completo. Deben validarse tokens/cookies, CORS, SameSite, prefijos y redirects. No se debe desactivar autenticación.

Para Cloudflare: KC_HOSTNAME debe ser HTTPS público; configurar headers forwarded; cambiar redirectUris/webOrigins al dominio real; mantener JWK interno para servicios cuando sea compatible; publicar solo realm necesario y bloquear /admin, /metrics y master; usar modo producción, no start-dev.

## 8. Contratos frontend-backend

| Área | Estado |
|---|---|
| Propuestas, proyectos, reporting y auditoría | Controladores/rutas declarados; DTO/JWT requieren prueba |
| Documentos | Servicio existe, pero UI usa adaptadores demo; falta prueba institucional |
| Auth | /auth/me del frontend no coincide directamente con /api/v1/me |
| AI | Gateway apunta a 8086, servicio inexistente |
| Notifications | Gateway apunta a 8088, servicio inexistente |
| Demo | Funciona con adaptadores locales, localStorage, IndexedDB y memoria |

La integración es estructural parcial, no funcionalmente certificada. Solo TypeScript pasó.

## 9. PostgreSQL único

Es viable localmente usar una instancia PostgreSQL 16 con:

- identity_db, proposal_db, project_db, document_db, reporting_db y audit_db;
- un usuario de runtime por servicio;
- usuarios de migración separados opcionalmente;
- JDBC URL y credenciales propias por servicio;
- prohibición de lectura cruzada de tablas.

Los Compose actuales crean seis instancias separadas; consolidarlas requiere un init SQL o init container y es una propuesta de infraestructura.

## 10. RabbitMQ y MinIO

RabbitMQ debe usar host interno rabbitmq, exchange durable invesctictrack.domain, vhost dedicado, ACL por productor y colas durables. Activar MESSAGING_ENABLED después de validar outbox, bindings e idempotencia. No publicar 5672 ni 15672.

MinIO debe usar endpoint interno http://minio:9000, volumen, bucket privado documents y credenciales de servicio. La consola 9001 debe quedar local. S3_PUBLIC_ENDPOINT y signed URLs requieren validación real de Host, HTTPS y CORS; no usar bucket público.

## 11. Propuesta de Compose raíz no implementada

Esqueleto conceptual:

    services:
      postgres:
        image: postgres:16
        environment:
          POSTGRES_PASSWORD: <POSTGRES_ADMIN_PASSWORD>
        volumes:
          - postgres-data:/var/lib/postgresql/data
          - ./infra/postgres/init:/docker-entrypoint-initdb.d:ro
        healthcheck:
          test: ["CMD-SHELL", "pg_isready -U postgres"]
        networks: [backend]

      rabbitmq:
        image: rabbitmq:<pinned-version>
        networks: [backend]

      minio:
        image: minio/minio:<pinned-release>
        command: server /data --console-address :9001
        networks: [backend]

      keycloak:
        image: quay.io/keycloak/keycloak:26.8.2
        command: start-dev --import-realm
        networks: [backend]

      identity-service:
        build: ./Backend/identity-service
        networks: [backend]

      proposal-service:
        build: ./Backend/proposal-service
        networks: [backend]

      project-service:
        build: ./Backend/project-service
        networks: [backend]

      document-service:
        build: ./Backend/document-service
        networks: [backend]

      report-service:
        build: ./Backend/report-service
        networks: [backend]

      audit-service:
        build: ./Backend/audit-service
        networks: [backend]

      api-gateway:
        build: ./Backend/API-gateway
        networks: [backend, edge]

      frontend:
        build: ./front
        networks: [edge]

      nginx:
        image: nginx:<pinned-version>
        networks: [backend, edge]

      cloudflared:
        image: cloudflare/cloudflared:<pinned-version>
        command: tunnel --no-autoupdate run --token <CLOUDFLARE_TUNNEL_TOKEN>
        networks: [edge]

    networks:
      backend: {}
      edge: {}
    volumes:
      postgres-data: {}
      rabbitmq-data: {}
      minio-data: {}
      keycloak-data: {}

Debe agregarse un Dockerfile frontend, env files privados, init SQL y healthchecks HTTP. El YAML no fue creado ni validado por Docker.

## 12. Reverse proxy propuesto

Nginx debe ser el único origen:

    server {
      listen 80;
      server_name _;

      location /api/ {
        proxy_pass http://api-gateway:8080/api/;
        proxy_set_header Host $http_host;
        proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;
        proxy_set_header Authorization $http_authorization;
        proxy_set_header Cookie $http_cookie;
      }

      location /realms/investitrack/ {
        proxy_pass http://keycloak:8080/realms/investitrack/;
        proxy_set_header Host $http_host;
        proxy_set_header X-Forwarded-Proto https;
      }

      location ~ ^/(admin|metrics|health|realms/master) { return 404; }
      location / { try_files $uri $uri/ /index.html; }
    }

Es una propuesta: debe verificarse el prefijo real de VITE_API_URL y no debe crear bypass de autorización.

## 13. Red privada y Cloudflare Tunnel

Privados: PostgreSQL, RabbitMQ, MinIO, consola Keycloak, servicios de dominio, /internal/**, Actuator detallado, OpenAPI administrativo y secretos.

Topología: cloudflared -> Nginx -> frontend, /api -> gateway y realm OIDC restringido. Nunca túnel directo a DB, RabbitMQ, MinIO administrativo, servicios internos o Keycloak admin.

Para MinIO, las signed URLs públicas deben conservar firma, host, HTTPS y CORS. Para Keycloak, issuer, redirects, cookies y headers proxy deben coincidir con el dominio público.

## 14. Recursos y medición

Estimación, no medición: 8–12 GB de RAM disponibles y 4 núcleos para compilar/arrancar todo localmente. El pico ocurre durante Maven/Gradle, Keycloak, Flyway y arranque concurrente.

Comandos posteriores:

    docker stats --no-stream
    docker compose ps
    docker system df
    Get-Counter '\Processor(_Total)\% Processor Time'
    Get-Counter '\Memory\Available MBytes'

Registrar RSS máximo, CPU, arranque, primer request y latencia posterior a suspensión. No usar docker system prune durante la auditoría.

## 15. Asistente simulado y backend real

assistant.api.ts usa demoAssistant cuando config.demo es true. Pero el flag global también controla login, proyectos y propuestas. Para mezclar modos se propone posteriormente:

- VITE_ASSISTANT_MODE=demo;
- VITE_DATA_MODE=api;
- etiqueta visible “Asistente simulado — respuestas de demostración”;
- ningún envío de preguntas/documentos/tokens a proveedores;
- aviso de historial no persistente y respuestas no generadas por modelo real.

Hasta validar autenticación, es más seguro mantener toda la UI demo.

## 16. Secretos

Backend/project-service/.env contiene configuración local y una contraseña de reemplazo; debe retirarse del flujo de publicación y rotarse si algún valor fue real. Keycloak usa credenciales privadas de desarrollo descritas en su README. No reutilizarlas públicamente.

Antes de publicar: escanear historial Git y artefactos ignorados, rotar DB/RabbitMQ/MinIO/Keycloak/workload/túnel y comprobar que front/dist no contiene secretos. Nunca poner secretos en VITE_*.

## 17. Problemas críticos

1. ai-service vacío y rutas AI declaradas.
2. notification-service inexistente y rutas Notification declaradas.
3. /auth/me frente a /api/v1/me requiere resolución.
4. No hay Compose raíz.
5. Docker no está disponible; no hay integración ejecutada.
6. Keycloak es localhost/start-dev.
7. MinIO signed URLs no están validadas detrás de proxy.
8. Reporting requiere eventos y rebuilds.
9. Muchos defaults apuntan a localhost.
10. No se deben desactivar autenticación ni publicar endpoints internos.

## 18. Plan posterior

1. Configurar Docker y repetir docker version/info.
2. Crear Compose raíz y env privado.
3. Crear PostgreSQL única con seis DB/usuarios.
4. Añadir RabbitMQ, MinIO, bucket y ACL.
5. Validar Keycloak discovery, JWKS, PKCE y usuarios demo.
6. Arrancar Identity y validar Flyway/liveness.
7. Arrancar Proposal, Project, Document, Reporting y Audit.
8. Ejecutar seeds y rebuilds.
9. Probar auth, cookies, prefijos y DTO frontend.
10. Añadir Nginx y restringir OIDC/admin.
11. Ejecutar pruebas de autorización/browser.
12. Medir docker stats.
13. Crear túnel hacia Nginx.
14. Apagar y revocar el token al terminar la exposición.

## 19. Conclusión

**Localmente:** viable en principio, pero no con un único comando reproducible en el estado actual.

**Cloudflare Tunnel:** viable para frontend/Nginx y, tras resolver autenticación y dependencias, para backend parcial. No debe presentarse como plataforma completa mientras AI, notificaciones, auth browser, eventos y signed URLs no estén validados.

**$0:** la opción recomendada es frontend demo publicado temporalmente. La plataforma completa puede ejecutarse localmente con recursos suficientes, pero su exposición requiere trabajo de integración y no garantiza estabilidad de servicios gratuitos externos.
