# REST API Contract — Research Project Management Platform

This contract defines the external and internal endpoints for the Spring microservices described in `backend-architecture.md`.

## 1. API conventions

### Base paths

- External client API: `/api/v1`
- Service-to-service API: `/internal/v1`
- Operational endpoints: `/actuator`

The API Gateway exposes only `/api/v1/**`. Internal and operational paths are available only on trusted networks and require service credentials.

### Authentication

All external endpoints require an OAuth 2.0 access token except operational liveness checks where infrastructure policy permits anonymous access.

```http
Authorization: Bearer <access-token>
```

Authentication is handled by the institutional OpenID Connect provider. Therefore, the application does not expose password login, password reset, or token-issuance endpoints.

Recommended token claims:

```json
{
  "sub": "institutional-user-id",
  "roles": ["RESEARCHER"],
  "scope": "research-platform",
  "iss": "https://identity.university.example",
  "aud": "research-platform-api"
}
```

Roles establish broad capabilities. Resource-level policies and memberships establish which specific proposals, projects, evaluations, documents, reports, and AI sources a user may access.

### Content types

- Ordinary requests and responses: `application/json`
- Direct file upload: `multipart/form-data`
- Server-sent AI stream: `text/event-stream`
- Report and document downloads: content type of the returned file

### Identifiers and time

- Public identifiers are UUIDs.
- Timestamps use ISO 8601 UTC, for example `2026-09-30T20:15:30Z`.
- Dates without a time use `YYYY-MM-DD`.
- Clients must treat identifiers as opaque strings.

### Pagination, sorting, and filtering

Collection endpoints use cursor pagination:

```http
GET /api/v1/projects?limit=25&cursor=eyJpZCI6Ii4uLiJ9&sort=-updatedAt
```

Common query parameters:

| Parameter | Meaning |
|---|---|
| `limit` | Page size; default 25, maximum 100 |
| `cursor` | Opaque continuation cursor |
| `sort` | Field name; prefix with `-` for descending order |
| `q` | Free-text query where supported |
| `status` | Repeatable status filter |
| `from`, `to` | Inclusive date or timestamp range |

Collection response:

```json
{
  "items": [],
  "page": {
    "limit": 25,
    "nextCursor": null,
    "hasMore": false
  }
}
```

### Optimistic concurrency

Mutable aggregate resources return an `ETag`. Update and state-transition requests must supply the current value:

```http
If-Match: "7"
```

A stale version returns `412 Precondition Failed`. Creation requests that may be retried accept an `Idempotency-Key` header.

### Standard response codes

| Code | Use |
|---|---|
| `200 OK` | Successful query or update |
| `201 Created` | Resource created; `Location` identifies it |
| `202 Accepted` | Asynchronous processing started |
| `204 No Content` | Successful operation with no body |
| `400 Bad Request` | Malformed request or invalid parameters |
| `401 Unauthorized` | Missing or invalid authentication |
| `403 Forbidden` | Authenticated but not authorized |
| `404 Not Found` | Resource absent or intentionally concealed from caller |
| `409 Conflict` | Business-state conflict or duplicate operation |
| `412 Precondition Failed` | Stale `If-Match` value |
| `413 Payload Too Large` | Upload or request exceeds configured limit |
| `415 Unsupported Media Type` | Unsupported document or request type |
| `422 Unprocessable Content` | Syntactically valid but violates domain validation |
| `429 Too Many Requests` | Rate limit exceeded |
| `503 Service Unavailable` | Required dependency temporarily unavailable |

### Standard error body

Use RFC 9457 Problem Details:

```json
{
  "type": "https://api.university.example/problems/invalid-status-transition",
  "title": "Invalid status transition",
  "status": 409,
  "detail": "A rejected proposal cannot be submitted.",
  "instance": "/api/v1/proposals/31c.../transitions",
  "code": "PROPOSAL_TRANSITION_NOT_ALLOWED",
  "correlationId": "01J9...",
  "errors": [
    {"field": "targetStatus", "message": "Transition from REJECTED to SUBMITTED is not allowed"}
  ]
}
```

## 2. Identity & Access Service

### Current user

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/me` | Any authenticated user | Current profile, roles, account status, and effective UI capabilities |
| `PATCH` | `/api/v1/me` | Current user | Update permitted personal profile fields |
| `GET` | `/api/v1/me/projects` | Current user | Projects visible to the current user |
| `GET` | `/api/v1/me/proposals` | Current user | Proposals visible to the current user |
| `GET` | `/api/v1/me/evaluation-assignments` | Evaluator | Evaluations assigned to the current user |
| `GET` | `/api/v1/me/permissions` | Current user | Effective global and optionally resource-specific permissions |

Example `GET /api/v1/me` response:

```json
{
  "id": "ca62b387-45fd-4e97-8940-1b061dcac234",
  "institutionalId": "A01234567",
  "displayName": "Ana Pérez",
  "email": "ana.perez@university.example",
  "status": "ACTIVE",
  "roles": ["RESEARCHER", "EVALUATOR"],
  "capabilities": ["proposal:create", "evaluation:submit"]
}
```

### User administration

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/users` | Administrator; limited lookup for coordinators | Search and list users |
| `POST` | `/api/v1/users` | Administrator | Provision an application user linked to an institutional identity |
| `GET` | `/api/v1/users/{userId}` | Administrator or permitted user lookup | Get a user profile |
| `PATCH` | `/api/v1/users/{userId}` | Administrator | Update application profile or account attributes |
| `POST` | `/api/v1/users/{userId}/deactivation` | Administrator | Deactivate an account with reason |
| `DELETE` | `/api/v1/users/{userId}/deactivation` | Administrator | Reactivate an account when policy permits |
| `GET` | `/api/v1/users/{userId}/roles` | Administrator | List role assignments |
| `PUT` | `/api/v1/users/{userId}/roles` | Administrator | Replace the complete role assignment set |
| `POST` | `/api/v1/users/{userId}/roles/{roleCode}` | Administrator | Add one role idempotently |
| `DELETE` | `/api/v1/users/{userId}/roles/{roleCode}` | Administrator | Remove one role |

`GET /users` filters: `q`, `status`, `role`, `limit`, and `cursor`.

Example role replacement:

```json
{
  "roles": ["RESEARCHER", "EVALUATOR"],
  "reason": "Assigned to the 2026 engineering review committee"
}
```

### Role catalog

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/roles` | Administrator | List assignable roles and descriptions |
| `GET` | `/api/v1/roles/{roleCode}` | Administrator | Get role details and capabilities |

Roles are configuration-backed in the MVP. Creating arbitrary roles through the API is intentionally excluded until the institution defines a stable permission-administration model.

## 3. Proposal & Evaluation Service

### Proposals

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/proposals` | Authorized user | List only proposals visible to the caller |
| `POST` | `/api/v1/proposals` | Researcher | Create a proposal in `DRAFT` |
| `GET` | `/api/v1/proposals/{proposalId}` | Authorized proposal participant | Get proposal details |
| `PATCH` | `/api/v1/proposals/{proposalId}` | Owner/authorized collaborator | Partially update editable proposal information |
| `DELETE` | `/api/v1/proposals/{proposalId}` | Owner or administrator | Delete a draft according to retention policy |
| `GET` | `/api/v1/proposals/{proposalId}/history` | Authorized participant/coordinator | Proposal status and important change history |
| `POST` | `/api/v1/proposals/{proposalId}/transitions` | Policy-dependent | Perform an explicit state transition |

`GET /proposals` filters: `q`, `status`, `researchArea`, `researcherId`, `evaluatorId`, `submittedFrom`, `submittedTo`, `limit`, and `cursor`.

Create/update proposal body:

```json
{
  "title": "Water quality monitoring with low-cost sensors",
  "abstract": "...",
  "researchProblem": "...",
  "objectives": [
    {"type": "GENERAL", "description": "..."},
    {"type": "SPECIFIC", "description": "..."}
  ],
  "methodology": "...",
  "researchAreaCode": "ENVIRONMENTAL_ENGINEERING",
  "researcherIds": ["ca62b387-45fd-4e97-8940-1b061dcac234"],
  "expectedResults": "...",
  "estimatedDurationMonths": 18,
  "supportingDocumentIds": ["a6bca6c5-bf88-435c-bdc6-1a28d6b300d3"]
}
```

Transition body:

```json
{
  "targetStatus": "SUBMITTED",
  "reason": null
}
```

Allowed proposal transitions:

| From | To | Authorized actor |
|---|---|---|
| `DRAFT` | `SUBMITTED`, `CANCELLED` | Owner/authorized researcher |
| `SUBMITTED` | `UNDER_REVIEW`, `CANCELLED` | Coordinator; cancellation by policy |
| `UNDER_REVIEW` | `CHANGES_REQUESTED`, `APPROVED`, `REJECTED` | Coordinator after evaluation policy is satisfied |
| `CHANGES_REQUESTED` | `SUBMITTED`, `CANCELLED` | Owner/authorized researcher |

Terminal states cannot transition without a separately authorized administrative correction procedure, which is recorded in the audit trail.

### Proposal collaborators

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/proposals/{proposalId}/researchers` | Authorized proposal participant | List proposal researchers |
| `POST` | `/api/v1/proposals/{proposalId}/researchers` | Proposal owner | Add a researcher while editable |
| `PATCH` | `/api/v1/proposals/{proposalId}/researchers/{userId}` | Proposal owner | Update proposal-specific responsibility |
| `DELETE` | `/api/v1/proposals/{proposalId}/researchers/{userId}` | Proposal owner | Remove a researcher while editable |

### Evaluator assignments

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/proposals/{proposalId}/evaluator-assignments` | Coordinator; assigned evaluator sees own assignment | List evaluator assignments |
| `POST` | `/api/v1/proposals/{proposalId}/evaluator-assignments` | Coordinator | Assign an evaluator |
| `GET` | `/api/v1/proposals/{proposalId}/evaluator-assignments/{assignmentId}` | Coordinator or assigned evaluator | Get assignment |
| `PATCH` | `/api/v1/proposals/{proposalId}/evaluator-assignments/{assignmentId}` | Coordinator | Change deadline or assignment metadata |
| `DELETE` | `/api/v1/proposals/{proposalId}/evaluator-assignments/{assignmentId}` | Coordinator | Revoke an uncompleted assignment |

Assignment body:

```json
{
  "evaluatorId": "c2ef8d6d-762f-4ad9-9d08-461c7ce4dd6c",
  "dueDate": "2026-10-31",
  "instructions": "Evaluate scientific merit and feasibility."
}
```

### Evaluations

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/proposals/{proposalId}/evaluations` | Coordinator; evaluators according to policy; researcher after release | List evaluations visible to caller |
| `POST` | `/api/v1/proposals/{proposalId}/evaluations` | Assigned evaluator | Create a draft evaluation for the caller's assignment |
| `GET` | `/api/v1/proposals/{proposalId}/evaluations/{evaluationId}` | Authorized evaluator/coordinator; researcher after release | Get evaluation |
| `PATCH` | `/api/v1/proposals/{proposalId}/evaluations/{evaluationId}` | Owning evaluator | Edit a draft evaluation |
| `POST` | `/api/v1/proposals/{proposalId}/evaluations/{evaluationId}/submission` | Owning evaluator | Submit and lock an evaluation |
| `POST` | `/api/v1/proposals/{proposalId}/evaluations/{evaluationId}/release` | Coordinator | Release an allowed evaluation result to researchers |
| `GET` | `/api/v1/proposals/{proposalId}/evaluations/{evaluationId}/history` | Coordinator/auditor | Evaluation change and submission history |

Evaluation body:

```json
{
  "criteria": [
    {
      "criterionCode": "SCIENTIFIC_MERIT",
      "score": 4,
      "comment": "The research problem is well supported."
    }
  ],
  "observations": "...",
  "recommendations": "...",
  "decision": "APPROVE"
}
```

Supported decisions: `APPROVE`, `REQUEST_CHANGES`, and `REJECT`. Submitting an evaluation does not by itself approve or reject the proposal; the coordinator transition applies institutional decision rules.

### Evaluation criteria catalog

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/evaluation-criteria` | Evaluator/coordinator | List active evaluation criteria and scoring rules |
| `GET` | `/api/v1/evaluation-criteria/{criterionCode}` | Evaluator/coordinator | Get criterion details |

## 4. Project Execution Service

### Projects

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/projects` | Authorized user | List projects visible to caller |
| `POST` | `/api/v1/projects` | Coordinator | Create a project from an approved proposal |
| `GET` | `/api/v1/projects/{projectId}` | Authorized project participant/coordinator | Get project details and progress summary |
| `PATCH` | `/api/v1/projects/{projectId}` | Authorized researcher/coordinator | Update permitted project information |
| `GET` | `/api/v1/projects/{projectId}/history` | Authorized participant/coordinator | Project status and important change history |
| `POST` | `/api/v1/projects/{projectId}/transitions` | Policy-dependent | Change project status using validated transition rules |
| `GET` | `/api/v1/projects/{projectId}/progress` | Authorized participant/coordinator | Get calculated progress and calculation breakdown |
| `POST` | `/api/v1/projects/{projectId}/progress/recalculation` | Coordinator/administrator | Request explicit progress recalculation |

`GET /projects` filters: `q`, `status`, `researchArea`, `memberId`, `coordinatorId`, `delayed`, `deadlineFrom`, `deadlineTo`, `limit`, and `cursor`.

Create-from-proposal body:

```json
{
  "proposalId": "31c5725a-cb06-4cbe-b183-b046872e31f8",
  "startDate": "2026-11-01",
  "plannedEndDate": "2028-04-30",
  "coordinatorId": "14e7f187-1048-40d1-8fd4-dd761f17813d"
}
```

Project transition body:

```json
{
  "targetStatus": "CLOSED",
  "reason": "All closure conditions were verified."
}
```

Allowed project transitions:

| From | To | Authorized actor |
|---|---|---|
| `APPROVED` | `ACTIVE`, `CANCELLED` | Coordinator |
| `ACTIVE` | `DELAYED`, `COMPLETED`, `CANCELLED` | Coordinator; selected progress updates may mark delayed |
| `DELAYED` | `ACTIVE`, `COMPLETED`, `CANCELLED` | Coordinator |
| `COMPLETED` | `CLOSED`, `ACTIVE` | Coordinator |

`CLOSED` and `CANCELLED` are terminal for ordinary workflows. Closing a project validates required final-report and institutional conditions.

### Project members

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/projects/{projectId}/members` | Authorized project participant | List members and project roles |
| `POST` | `/api/v1/projects/{projectId}/members` | Principal researcher/coordinator | Add a member |
| `GET` | `/api/v1/projects/{projectId}/members/{userId}` | Authorized project participant | Get membership details |
| `PATCH` | `/api/v1/projects/{projectId}/members/{userId}` | Principal researcher/coordinator | Update project role or permissions |
| `DELETE` | `/api/v1/projects/{projectId}/members/{userId}` | Principal researcher/coordinator | Remove a member when domain rules allow |

Membership body:

```json
{
  "userId": "ca62b387-45fd-4e97-8940-1b061dcac234",
  "projectRole": "RESEARCHER",
  "permissions": ["ACTIVITY_WRITE", "DOCUMENT_UPLOAD"]
}
```

### Activities

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/projects/{projectId}/activities` | Authorized project participant | List activities |
| `POST` | `/api/v1/projects/{projectId}/activities` | Authorized researcher/collaborator | Create an activity |
| `GET` | `/api/v1/projects/{projectId}/activities/{activityId}` | Authorized project participant | Get activity details |
| `PATCH` | `/api/v1/projects/{projectId}/activities/{activityId}` | Authorized assignee/researcher | Update editable fields |
| `DELETE` | `/api/v1/projects/{projectId}/activities/{activityId}` | Authorized researcher | Delete an activity when allowed |
| `POST` | `/api/v1/projects/{projectId}/activities/{activityId}/transitions` | Authorized assignee/researcher | Change activity status |
| `GET` | `/api/v1/projects/{projectId}/activities/{activityId}/evidence` | Authorized project participant | List evidence documents linked to activity |

`GET /activities` filters: `status`, `responsibleUserId`, `milestoneId`, `overdue`, `deadlineFrom`, `deadlineTo`, `limit`, and `cursor`.

Activity body:

```json
{
  "name": "Calibrate field sensors",
  "description": "...",
  "responsibleUserId": "ca62b387-45fd-4e97-8940-1b061dcac234",
  "startDate": "2026-11-10",
  "deadline": "2026-12-05",
  "milestoneId": "27452ae1-c88d-4309-86f0-924063f9668d",
  "weight": 10
}
```

Activity statuses: `NOT_STARTED`, `IN_PROGRESS`, `BLOCKED`, `COMPLETED`, and `CANCELLED`.

### Milestones

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/projects/{projectId}/milestones` | Authorized project participant | List milestones |
| `POST` | `/api/v1/projects/{projectId}/milestones` | Authorized researcher | Create a milestone |
| `GET` | `/api/v1/projects/{projectId}/milestones/{milestoneId}` | Authorized project participant | Get milestone details |
| `PATCH` | `/api/v1/projects/{projectId}/milestones/{milestoneId}` | Authorized researcher | Update milestone |
| `DELETE` | `/api/v1/projects/{projectId}/milestones/{milestoneId}` | Authorized researcher | Delete when no protected dependency prevents it |
| `POST` | `/api/v1/projects/{projectId}/milestones/{milestoneId}/transitions` | Authorized researcher | Change milestone status |
| `GET` | `/api/v1/projects/{projectId}/milestones/{milestoneId}/activities` | Authorized project participant | List activities associated with milestone |
| `GET` | `/api/v1/projects/{projectId}/milestones/{milestoneId}/evidence` | Authorized project participant | List evidence documents linked to milestone |

Milestone statuses: `PLANNED`, `IN_PROGRESS`, `COMPLETED`, `DELAYED`, and `CANCELLED`.

### Evidence links

Document bytes and metadata are owned by Document Management. These endpoints manage the domain relationship between a project item and a document.

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/projects/{projectId}/activities/{activityId}/evidence` | Authorized project member | Link an uploaded document as activity evidence |
| `DELETE` | `/api/v1/projects/{projectId}/activities/{activityId}/evidence/{documentId}` | Authorized project member | Remove evidence link; does not necessarily delete document |
| `POST` | `/api/v1/projects/{projectId}/milestones/{milestoneId}/evidence` | Authorized project member | Link an uploaded document as milestone evidence |
| `DELETE` | `/api/v1/projects/{projectId}/milestones/{milestoneId}/evidence/{documentId}` | Authorized project member | Remove evidence link |

Link body:

```json
{
  "documentId": "a6bca6c5-bf88-435c-bdc6-1a28d6b300d3",
  "description": "Calibration certificate and photographs"
}
```

### Final report

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/projects/{projectId}/final-report` | Authorized participant/coordinator | Get current final-report record |
| `PUT` | `/api/v1/projects/{projectId}/final-report` | Authorized researcher | Create or replace the draft association with an uploaded document |
| `POST` | `/api/v1/projects/{projectId}/final-report/submission` | Authorized researcher | Submit and lock the final report |
| `POST` | `/api/v1/projects/{projectId}/final-report/reopening` | Coordinator | Reopen final report for changes with reason |

### Publications

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/projects/{projectId}/publications` | Authorized project participant | List resulting publications |
| `POST` | `/api/v1/projects/{projectId}/publications` | Authorized researcher | Register a publication |
| `GET` | `/api/v1/projects/{projectId}/publications/{publicationId}` | Authorized project participant | Get publication record |
| `PATCH` | `/api/v1/projects/{projectId}/publications/{publicationId}` | Authorized researcher | Update publication record |
| `DELETE` | `/api/v1/projects/{projectId}/publications/{publicationId}` | Authorized researcher | Remove publication record when permitted |

Publication body:

```json
{
  "title": "Low-cost sensing for regional water monitoring",
  "type": "JOURNAL_ARTICLE",
  "authors": ["Ana Pérez", "Luis Gómez"],
  "doi": "10.1000/example.2027.10",
  "publicationDate": "2027-08-15",
  "url": "https://doi.org/10.1000/example.2027.10",
  "documentId": null
}
```

## 5. Document Management Service

### Document search and metadata

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/documents` | Authorized user | Search documents within caller's authorized scope |
| `GET` | `/api/v1/documents/{documentId}` | Authorized user | Get metadata for one document |
| `PATCH` | `/api/v1/documents/{documentId}` | Document owner/authorized project member | Update editable metadata or classification |
| `DELETE` | `/api/v1/documents/{documentId}` | Authorized user | Soft-delete according to retention rules |
| `POST` | `/api/v1/documents/{documentId}/restoration` | Administrator/authorized owner | Restore a soft-deleted document while retained |
| `GET` | `/api/v1/documents/{documentId}/versions` | Authorized user | List document versions |
| `GET` | `/api/v1/documents/{documentId}/versions/{versionNumber}` | Authorized user | Get version metadata |

`GET /documents` filters: `q`, `projectId`, `proposalId`, `activityId`, `milestoneId`, `documentType`, `classification`, `processingStatus`, `uploadedBy`, `from`, `to`, `limit`, and `cursor`.

Supported initial file types: PDF, DOCX, XLSX, PPTX, PNG, JPEG, GIF, and institution-approved image types. Validation uses file signatures in addition to declared MIME type and filename extension.

### Upload workflow

The preferred flow uses time-limited object-storage URLs so large files do not pass through every application layer.

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/document-uploads` | Authorized uploader | Initialize upload and receive upload instructions |
| `GET` | `/api/v1/document-uploads/{uploadId}` | Initiating user | Get upload/scan/processing state |
| `POST` | `/api/v1/document-uploads/{uploadId}/completion` | Initiating user | Confirm object upload and begin validation/scan |
| `DELETE` | `/api/v1/document-uploads/{uploadId}` | Initiating user | Cancel an incomplete upload |
| `POST` | `/api/v1/documents/{documentId}/versions` | Authorized editor | Initialize upload of a new version |

Initialize-upload body:

```json
{
  "fileName": "research-protocol.pdf",
  "contentType": "application/pdf",
  "sizeBytes": 1837291,
  "sha256": "52f72e...",
  "documentType": "RESEARCH_PROTOCOL",
  "classification": "CONFIDENTIAL",
  "context": {
    "proposalId": "31c5725a-cb06-4cbe-b183-b046872e31f8",
    "projectId": null,
    "activityId": null,
    "milestoneId": null
  }
}
```

Initialize-upload response:

```json
{
  "uploadId": "86237726-da60-4fc0-9e83-700393e08d84",
  "method": "PUT",
  "uploadUrl": "https://storage.example/signed-upload-url",
  "requiredHeaders": {"Content-Type": "application/pdf"},
  "expiresAt": "2026-09-30T21:00:00Z"
}
```

Completion returns `202 Accepted`. The document is unavailable for download and AI indexing until signature validation and malware scanning pass.

### Downloads and previews

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/documents/{documentId}/download-authorization` | Authorized user | Get a short-lived download URL after authorization |
| `POST` | `/api/v1/documents/{documentId}/versions/{versionNumber}/download-authorization` | Authorized user | Download a particular version |
| `GET` | `/api/v1/documents/{documentId}/preview` | Authorized user | Get supported preview metadata or short-lived preview URL |
| `GET` | `/api/v1/documents/{documentId}/processing-status` | Authorized user | Get scan, extraction, thumbnail, and indexing states |

Download-authorization body may contain a user-confirmed purpose when policy requires it:

```json
{
  "purpose": "Review assigned research proposal"
}
```

### Document associations

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/documents/{documentId}/associations` | Authorized user | List proposal/project/activity/milestone associations visible to caller |
| `POST` | `/api/v1/documents/{documentId}/associations` | Authorized editor | Associate document with another permitted resource |
| `DELETE` | `/api/v1/documents/{documentId}/associations/{associationId}` | Authorized editor | Remove an association without deleting the document |

## 6. Dashboard & Reporting Service

### Dashboards

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/dashboards/researcher` | Researcher/student collaborator | Current user's project dashboard |
| `GET` | `/api/v1/dashboards/coordinator` | Coordinator | Portfolio dashboard within authorized scope |
| `GET` | `/api/v1/dashboards/projects/{projectId}` | Authorized project participant | Detailed project dashboard projection |
| `GET` | `/api/v1/dashboards/evaluator` | Evaluator | Current assignments, deadlines, and completion summary |

Dashboard filters: `researchArea`, `status`, `from`, `to`, and `coordinatorId` where authorized.

Coordinator response includes at least:

```json
{
  "asOf": "2026-09-30T20:15:30Z",
  "counts": {
    "activeProjects": 34,
    "delayedProjects": 5,
    "upcomingMilestones": 12,
    "completedMilestones": 81,
    "pendingEvaluations": 7
  },
  "projectsByStatus": [
    {"status": "ACTIVE", "count": 34},
    {"status": "DELAYED", "count": 5}
  ],
  "upcomingDeadlines": []
}
```

### Deadlines

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/deadlines` | Authorized user | List upcoming and overdue activities/milestones in caller's scope |
| `GET` | `/api/v1/projects/{projectId}/deadlines` | Authorized project participant | List project-specific deadlines |

Filters: `state=UPCOMING|OVERDUE`, `type=ACTIVITY|MILESTONE`, `from`, `to`, `responsibleUserId`, `projectId`, `limit`, and `cursor`.

### Report catalog and generation

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/report-definitions` | Authorized user | List report types caller may generate |
| `GET` | `/api/v1/report-definitions/{reportCode}` | Authorized user | Get parameters and supported formats |
| `POST` | `/api/v1/report-jobs` | Authorized user | Start report generation |
| `GET` | `/api/v1/report-jobs` | Authorized user | List caller's report jobs; administrators may view broader scope |
| `GET` | `/api/v1/report-jobs/{jobId}` | Job owner/authorized administrator | Get state, failure details, and expiration |
| `DELETE` | `/api/v1/report-jobs/{jobId}` | Job owner/administrator | Cancel pending job or remove generated artifact when permitted |
| `POST` | `/api/v1/report-jobs/{jobId}/download-authorization` | Job owner/authorized user | Get short-lived URL for completed report |

Create report job:

```json
{
  "reportCode": "PROJECT_PORTFOLIO_STATUS",
  "format": "XLSX",
  "parameters": {
    "researchArea": "ENVIRONMENTAL_ENGINEERING",
    "status": ["ACTIVE", "DELAYED"],
    "asOfDate": "2026-09-30"
  }
}
```

Report formats are definition-specific, typically `PDF`, `XLSX`, or `CSV`.

## 7. AI Assistant Service

### Conversations

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/ai/conversations` | AI-enabled user | List caller's retained conversations |
| `POST` | `/api/v1/ai/conversations` | AI-enabled user | Create conversation with optional authorized project scope |
| `GET` | `/api/v1/ai/conversations/{conversationId}` | Conversation owner | Get conversation metadata |
| `PATCH` | `/api/v1/ai/conversations/{conversationId}` | Conversation owner | Rename or update allowed settings |
| `DELETE` | `/api/v1/ai/conversations/{conversationId}` | Conversation owner | Delete or schedule deletion under retention policy |
| `GET` | `/api/v1/ai/conversations/{conversationId}/messages` | Conversation owner | List retained messages and citations |

Create conversation:

```json
{
  "title": "Protocol questions",
  "scope": {
    "projectIds": ["141dff33-5f73-4eec-8f33-8a577f239204"],
    "includeInstitutionalKnowledge": true
  }
}
```

The service intersects the requested scope with current permissions. It never accepts client-provided permissions as authoritative.

### Questions and responses

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/ai/conversations/{conversationId}/questions` | Conversation owner | Ask a question and receive a complete response |
| `POST` | `/api/v1/ai/conversations/{conversationId}/questions:stream` | Conversation owner | Ask a question with server-sent response streaming |
| `GET` | `/api/v1/ai/conversations/{conversationId}/messages/{messageId}` | Conversation owner | Get one message, support status, and citations |
| `POST` | `/api/v1/ai/messages/{messageId}/feedback` | Message owner | Submit usefulness/correctness feedback |
| `GET` | `/api/v1/ai/sources/{sourceReferenceId}` | User authorized for underlying source | Resolve citation metadata and an authorized open/download action |

Question body:

```json
{
  "question": "What evidence must be submitted before this milestone can be completed?",
  "scope": {
    "projectIds": ["141dff33-5f73-4eec-8f33-8a577f239204"]
  }
}
```

Response body:

```json
{
  "messageId": "131aec3c-eb54-4d40-b679-6fabdf71d7bf",
  "answer": "The available project protocol requires ...",
  "supportStatus": "SUPPORTED",
  "citations": [
    {
      "sourceReferenceId": "723c2b83-fbde-443d-a9e8-a6f96b37a45c",
      "documentId": "a6bca6c5-bf88-435c-bdc6-1a28d6b300d3",
      "documentName": "Research protocol v2",
      "version": 2,
      "page": 14,
      "section": "Milestone evidence",
      "excerpt": "..."
    }
  ],
  "disclaimer": "This AI-generated response is informational and does not replace official institutional policies, decisions, or human evaluation."
}
```

`supportStatus` values:

- `SUPPORTED`: sufficient authorized sources support the answer.
- `PARTIALLY_SUPPORTED`: answer is limited to the supported portion and explicitly identifies gaps.
- `INSUFFICIENT_INFORMATION`: no reliable answer is generated.
- `SOURCE_ACCESS_CHANGED`: access changed during processing; affected content is omitted.

Streaming event types: `message.started`, `answer.delta`, `citation`, `message.completed`, and `error`. Authorization and sufficiency checks still apply; streaming must not emit retrieved confidential text before access validation is complete.

### Feedback body

```json
{
  "rating": "NOT_HELPFUL",
  "reasonCodes": ["INCORRECT_CITATION"],
  "comment": "The cited page discusses a different milestone."
}
```

## 8. Audit Service

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/audit-events` | Administrator/authorized auditor | Search audit records |
| `GET` | `/api/v1/audit-events/{auditEventId}` | Administrator/authorized auditor | Get one audit record |
| `POST` | `/api/v1/audit-export-jobs` | Authorized auditor | Start a filtered audit export |
| `GET` | `/api/v1/audit-export-jobs/{jobId}` | Requesting auditor | Get export status |
| `POST` | `/api/v1/audit-export-jobs/{jobId}/download-authorization` | Requesting auditor | Download completed export |

Audit filters: `actorId`, `action`, `resourceType`, `resourceId`, `outcome`, `from`, `to`, `correlationId`, `limit`, and `cursor`.

Audit events are immutable. No external `POST`, `PATCH`, or `DELETE` endpoint modifies individual audit records.

Example audit event:

```json
{
  "id": "99509a79-f935-4218-bc70-c15ac3361bd3",
  "occurredAt": "2026-09-30T20:15:30Z",
  "actor": {"type": "USER", "id": "ca62b387-45fd-4e97-8940-1b061dcac234"},
  "action": "DOCUMENT_DOWNLOADED",
  "resource": {"type": "DOCUMENT", "id": "a6bca6c5-bf88-435c-bdc6-1a28d6b300d3"},
  "outcome": "SUCCESS",
  "correlationId": "01J9...",
  "metadata": {"projectId": "141dff33-5f73-4eec-8f33-8a577f239204"}
}
```

## 9. Notification Service

### User notifications

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/notifications` | Current user | List current user's notifications |
| `GET` | `/api/v1/notifications/unread-count` | Current user | Get unread count |
| `GET` | `/api/v1/notifications/{notificationId}` | Notification recipient | Get one notification |
| `POST` | `/api/v1/notifications/{notificationId}/read` | Notification recipient | Mark one notification read |
| `DELETE` | `/api/v1/notifications/{notificationId}/read` | Notification recipient | Mark one notification unread |
| `POST` | `/api/v1/notifications/read-all` | Current user | Mark all matching notifications read |

Filters: `read`, `type`, `from`, `to`, `limit`, and `cursor`.

### Notification preferences

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/me/notification-preferences` | Current user | Get channel and event preferences |
| `PUT` | `/api/v1/me/notification-preferences` | Current user | Replace preferences |

Mandatory security or institutional notifications cannot be disabled through user preferences.

## 10. Reference-data endpoints

Reference data prevents clients from hard-coding changeable institutional catalogs.

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/reference/research-areas` | Authenticated user | List active research areas |
| `GET` | `/api/v1/reference/document-types` | Authenticated user | List document types and allowed formats |
| `GET` | `/api/v1/reference/publication-types` | Authenticated user | List publication types |
| `GET` | `/api/v1/reference/statuses` | Authenticated user | Get display labels for relevant statuses |

Administrative catalog mutation should be added only after ownership and governance rules are defined.

## 11. Internal service-to-service endpoints

These endpoints are never exposed through the public gateway. They require mutually authenticated service identities, audience-restricted tokens, and network policy enforcement.

### Identity & authorization

| Method | Endpoint | Caller | Purpose |
|---|---|---|---|
| `POST` | `/internal/v1/authorization/check` | Any domain service | Evaluate one action against a resource and user context |
| `POST` | `/internal/v1/authorization/filter` | Query, document, or AI service | Return/intersect authorized resource IDs for bounded input |
| `GET` | `/internal/v1/users/{userId}/status` | Domain services | Verify current account state |

Authorization check:

```json
{
  "subjectId": "ca62b387-45fd-4e97-8940-1b061dcac234",
  "action": "DOCUMENT_READ",
  "resource": {
    "type": "DOCUMENT",
    "id": "a6bca6c5-bf88-435c-bdc6-1a28d6b300d3",
    "projectId": "141dff33-5f73-4eec-8f33-8a577f239204"
  }
}
```

Response:

```json
{
  "allowed": true,
  "policyVersion": "2026-09-01",
  "reasonCode": "PROJECT_MEMBER"
}
```

Services should enforce locally available policies and projections when possible. This endpoint is for decisions that require authoritative current access information, not for turning the authorization service into a per-row bottleneck.

### Proposal and project integration

| Method | Endpoint | Caller | Purpose |
|---|---|---|---|
| `GET` | `/internal/v1/proposals/{proposalId}/project-creation-data` | Project service | Get immutable approved proposal snapshot needed to create project |
| `PUT` | `/internal/v1/proposals/{proposalId}/project-reference` | Project service | Idempotently record created project reference |
| `GET` | `/internal/v1/projects/{projectId}/access-context` | Document/AI/query service | Get project classification and current membership/access version |
| `POST` | `/internal/v1/projects/access-contexts` | Document/AI/query service | Batch access-context lookup |

The preferred creation trigger remains the `ProposalApproved` event. Internal REST supports authoritative lookup and reconciliation.

### Document processing

| Method | Endpoint | Caller | Purpose |
|---|---|---|---|
| `POST` | `/internal/v1/documents/{documentId}/scan-results` | Malware scanner | Record signed scan result |
| `POST` | `/internal/v1/documents/{documentId}/extractions` | Text extraction worker | Store extraction result metadata and protected text location |
| `GET` | `/internal/v1/documents/{documentId}/indexing-content` | AI indexing worker | Retrieve minimum authorized content and ACL metadata for indexing |
| `POST` | `/internal/v1/documents/{documentId}/indexing-status` | AI service | Record index version/status |
| `POST` | `/internal/v1/documents/{documentId}/source-validation` | AI service | Revalidate document state and subject access before answer generation |

### Dashboard reconciliation

| Method | Endpoint | Caller | Purpose |
|---|---|---|---|
| `POST` | `/internal/v1/projections/rebuilds` | Administrator job/control plane | Start a bounded read-model rebuild |
| `GET` | `/internal/v1/projections/rebuilds/{jobId}` | Administrator job/control plane | Get rebuild status |

### Audit ingestion fallback

The primary mechanism is broker-based event consumption. A synchronous fallback is available only for security-critical records that must be acknowledged before completing an operation.

| Method | Endpoint | Caller | Purpose |
|---|---|---|---|
| `POST` | `/internal/v1/audit-events` | Trusted services | Idempotently append a signed audit event |

## 12. Operational endpoints per Spring Boot service

Expose these through a separate management port or protected internal network:

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/actuator/health/liveness` | Process liveness; must not depend on remote services |
| `GET` | `/actuator/health/readiness` | Readiness to receive traffic |
| `GET` | `/actuator/info` | Sanitized service/version information |
| `GET` | `/actuator/prometheus` | Metrics scrape endpoint, restricted to monitoring |

Do not expose environment variables, configuration properties, heap dumps, mappings, or log-level mutation publicly.

## 13. Domain events associated with the API

REST updates publish events through a transactional outbox. Event envelopes use a stable versioned contract:

```json
{
  "eventId": "c68a54e7-9b85-47ee-8a0f-66cfaf9d9c73",
  "eventType": "ProjectProgressChanged",
  "eventVersion": 1,
  "occurredAt": "2026-09-30T20:15:30Z",
  "producer": "project-execution-service",
  "correlationId": "01J9...",
  "actor": {"type": "USER", "id": "ca62b387-45fd-4e97-8940-1b061dcac234"},
  "data": {
    "projectId": "141dff33-5f73-4eec-8f33-8a577f239204",
    "progress": 42.5
  }
}
```

Principal events:

- Identity: `UserCreated`, `UserDeactivated`, `UserReactivated`, `UserRolesChanged`, `ProjectAccessChanged`.
- Proposal: `ProposalCreated`, `ProposalSubmitted`, `EvaluatorAssigned`, `EvaluationSubmitted`, `ProposalChangesRequested`, `ProposalApproved`, `ProposalRejected`.
- Project: `ProjectCreated`, `ProjectMemberChanged`, `ActivityChanged`, `MilestoneChanged`, `DeadlineOverdue`, `ProjectProgressChanged`, `FinalReportSubmitted`, `PublicationRegistered`, `ProjectClosed`.
- Document: `DocumentUploadCompleted`, `DocumentScanPassed`, `DocumentScanFailed`, `DocumentTextExtracted`, `DocumentVersionCreated`, `DocumentAccessChanged`, `DocumentDeleted`.
- AI: `AiQuestionProcessed`, `AiInsufficientInformation`, `AiFeedbackSubmitted`, containing metadata rather than unnecessary prompt contents.

## 14. Requirement-to-endpoint coverage

| Requirement area | Principal endpoints |
|---|---|
| Authentication | Institutional OIDC plus `/me` |
| User and role management | `/users`, `/users/{id}/roles`, `/roles` |
| Proposal lifecycle | `/proposals`, `/proposals/{id}/transitions` |
| Evaluator assignment | `/proposals/{id}/evaluator-assignments` |
| Evaluation and decision | `/proposals/{id}/evaluations`, evaluation submission, proposal transition |
| Project creation and status | `/projects`, `/projects/{id}/transitions` |
| Collaborators | `/projects/{id}/members` |
| Activities and milestones | `/projects/{id}/activities`, `/projects/{id}/milestones` |
| Evidence and documents | Evidence link endpoints, `/document-uploads`, `/documents` |
| Progress and deadlines | `/projects/{id}/progress`, `/deadlines` |
| Dashboards and reports | `/dashboards/**`, `/report-jobs` |
| Final report and closure | `/projects/{id}/final-report`, project transition to `CLOSED` |
| Publications | `/projects/{id}/publications` |
| Authorized AI | `/ai/conversations`, question and citation endpoints |
| Audit trail | `/audit-events` and broker-consumed domain events |
| Notifications | `/notifications`, `/me/notification-preferences` |

## 15. APIs intentionally not provided

- No application password endpoints: authentication belongs to the institutional identity provider.
- No generic endpoint allowing clients to set arbitrary statuses: transitions are domain commands with validated state machines.
- No endpoint for public object-storage keys or permanent download URLs.
- No client endpoint for inserting audit records.
- No generic AI search endpoint that accepts arbitrary access filters from the client.
- No cross-service database or SQL query endpoint.
- No permanent hard-delete endpoint for regulated records until institutional retention requirements are defined.

