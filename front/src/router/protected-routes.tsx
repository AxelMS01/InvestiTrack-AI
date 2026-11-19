import type { ComponentType } from 'react';
import type { RouteObject } from 'react-router-dom';
import { DashboardLayout } from '../layouts/dashboard-layout';
import { RequireAuth } from './require-auth';
import type { Capability } from '../features/auth/model';
import type { Role } from '../types';
import { ForbiddenPage } from '../features/auth/forbidden-page';
import { DemoOnly } from '../features/shared/workflow-ui';
import { NotFoundPage } from './status-pages';

type Page = () => Promise<{ Component: ComponentType }>;
function guarded(path: string, capability: Capability, lazy: Page, demo = false): RouteObject {
  return {
    element: <RequireAuth capability={capability} />,
    children: [
      {
        path,
        lazy: async () => {
          const { Component } = await lazy();
          return {
            Component: demo
              ? () => (
                  <DemoOnly>
                    <Component />
                  </DemoOnly>
                )
              : Component,
          };
        },
      },
    ],
  };
}

const dashboard = async () => ({
  Component: (await import('../features/dashboard/dashboard-page')).DashboardPage,
});
const proposalForm = () => import('../features/proposals/proposal-create-page');
const proposalDetail = () => import('../features/proposals/proposal-detail-page');
const evaluations = () => import('../features/evaluations/evaluations-page');
const projectWorkspace = () => import('../features/projects/project-workspace');
const activities = () => import('../features/activities/activities-page');
const documents = () => import('../features/documents/documents-page');
const reports = () => import('../features/reports/reports-page');
const coordination = () => import('../features/coordination/coordination-page');
const evidences = () => import('../features/evidences/evidences-page');
const accounts = () => import('../features/admin/account-pages');
const profile = () => import('../features/dashboard/account-page');

function workspace(path: string, role: Role): RouteObject {
  return {
    element: <RequireAuth roles={[role]} />,
    children: [
      {
        path,
        lazy: async () => {
          const { Component } = await dashboard();
          return { Component: () => <Component roleOverride={role} /> };
        },
      },
    ],
  };
}

export const protectedRoutes: RouteObject[] = [
  {
    element: <RequireAuth />,
    children: [
      {
        element: <DashboardLayout />,
        children: [
          { index: true, lazy: dashboard },
          guarded(
            'administracion/unidades',
            'academic-unit:manage',
            async () => ({
              Component: (await import('../features/admin/academic-units-page')).AcademicUnitsPage,
            }),
            true,
          ),
          guarded(
            'administracion/unidades/:id',
            'academic-unit:manage',
            async () => ({
              Component: (await import('../features/admin/academic-units-page')).AcademicUnitsPage,
            }),
            true,
          ),
          guarded(
            'coordinacion',
            'academic-unit:coordinate',
            async () => ({ Component: (await coordination()).CoordinationPage }),
            true,
          ),
          guarded(
            'coordinacion/evaluadores',
            'project:assign-evaluator',
            async () => ({ Component: (await coordination()).ProjectEvaluatorPage }),
            true,
          ),
          guarded(
            'proyectos/:id/evaluadores',
            'project:assign-evaluator',
            async () => ({ Component: (await coordination()).ProjectEvaluatorPage }),
            true,
          ),
          guarded(
            'proyectos/:id/proponer-estudiante',
            'project:student-propose',
            async () => ({ Component: (await coordination()).StudentProposalPage }),
            true,
          ),
          guarded(
            'solicitudes-estudiantes',
            'project:student-requests',
            async () => ({ Component: (await coordination()).StudentRequestsPage }),
            true,
          ),
          guarded(
            'solicitudes-estudiantes/:id',
            'project:student-requests',
            async () => ({ Component: (await coordination()).StudentRequestPage }),
            true,
          ),
          guarded(
            'proyectos/:id/evidencias',
            'project:evidence-read',
            async () => ({ Component: (await evidences()).ProjectEvidencesPage }),
            true,
          ),
          guarded(
            'evidencias',
            'project:evidence-read',
            async () => ({ Component: (await evidences()).EvidencesPage }),
            true,
          ),
          guarded(
            'evidencias/nueva',
            'project:evidence-submit',
            async () => ({
              Component: (await import('../features/evidences/evidence-create-page'))
                .EvidenceCreatePage,
            }),
            true,
          ),
          guarded(
            'evidencias/:id',
            'project:evidence-read',
            async () => ({
              Component: (await import('../features/evidences/evidence-detail-page'))
                .EvidenceDetailPage,
            }),
            true,
          ),
          guarded(
            'evidencias/:id/evaluar',
            'project:evidence-grade',
            async () => ({
              Component: (await import('../features/evidences/evidence-evaluation-page'))
                .EvidenceEvaluationPage,
            }),
            true,
          ),
          workspace('panel/administrador', 'admin'),
          workspace('panel/coordinador', 'coordinator'),
          workspace('panel/investigador', 'researcher'),
          workspace('panel/evaluador', 'reviewer'),
          workspace('panel/estudiante', 'student'),
          { path: 'perfil', lazy: async () => ({ Component: (await profile()).ProfilePage }) },
          {
            path: 'notificaciones',
            lazy: async () => ({ Component: (await profile()).NotificationsPage }),
          },
          guarded('propuestas', 'proposal:read', async () => ({
            Component: (await import('../features/proposals/proposals-page')).ProposalsPage,
          })),
          guarded(
            'propuestas/nueva',
            'proposal:create',
            async () => ({ Component: (await proposalForm()).ProposalCreatePage }),
            true,
          ),
          guarded('propuestas/:id', 'proposal:read', async () => ({
            Component: (await proposalDetail()).ProposalDetailPage,
          })),
          guarded(
            'propuestas/:id/editar',
            'proposal:update',
            async () => ({ Component: (await proposalForm()).ProposalEditPage }),
            true,
          ),
          guarded('propuestas/:id/historial', 'proposal:read', async () => ({
            Component: (await proposalDetail()).ProposalHistoryPage,
          })),
          guarded(
            'propuestas/:id/revision',
            'proposal:submit',
            async () => ({ Component: (await proposalDetail()).ProposalReviewPage }),
            true,
          ),
          guarded(
            'evaluaciones',
            'evaluation:read',
            async () => ({ Component: (await evaluations()).EvaluationsPage }),
            true,
          ),
          guarded(
            'evaluaciones/asignar',
            'evaluation:assign',
            async () => ({ Component: (await evaluations()).EvaluationAssignmentPage }),
            true,
          ),
          guarded(
            'evaluaciones/:id/rubrica',
            'evaluation:create',
            async () => ({ Component: (await evaluations()).EvaluationRubricPage }),
            true,
          ),
          guarded(
            'evaluaciones/:id/resultados',
            'evaluation:read',
            async () => ({ Component: (await evaluations()).EvaluationResultsPage }),
            true,
          ),
          guarded(
            'evaluaciones/:id/decision',
            'proposal:decide',
            async () => ({ Component: (await evaluations()).EvaluationDecisionPage }),
            true,
          ),
          guarded('proyectos', 'project:read', async () => ({
            Component: (await import('../features/projects/projects-page')).ProjectsPage,
          })),
          guarded('proyectos/:id', 'project:read', async () => ({
            Component: (await import('../features/projects/project-detail-page')).ProjectDetailPage,
          })),
          guarded(
            'proyectos/nuevo',
            'project:convert',
            async () => ({
              Component: (await import('../features/projects/project-create-page'))
                .ProjectCreatePage,
            }),
            true,
          ),
          guarded(
            'proyectos/:id/configuracion',
            'project:update',
            async () => ({ Component: (await projectWorkspace()).ProjectSettingsPage }),
            true,
          ),
          guarded(
            'proyectos/:id/colaboradores',
            'project:read',
            async () => ({ Component: (await projectWorkspace()).ProjectCollaboratorsPage }),
            true,
          ),
          guarded(
            'proyectos/:id/seguimiento',
            'activity:read',
            async () => ({ Component: (await projectWorkspace()).ProjectTrackingPage }),
            true,
          ),
          guarded(
            'actividades',
            'activity:read',
            async () => ({ Component: (await activities()).ActivitiesPage }),
            true,
          ),
          guarded(
            'actividades/nueva',
            'activity:create',
            async () => ({ Component: (await activities()).ActivityCreatePage }),
            true,
          ),
          guarded(
            'actividades/:id',
            'activity:read',
            async () => ({ Component: (await activities()).ActivityDetailPage }),
            true,
          ),
          guarded(
            'actividades/:id/editar',
            'project:manage-activities',
            async () => ({ Component: (await activities()).ActivityEditPage }),
            true,
          ),
          guarded(
            'actividades/hitos',
            'activity:read',
            async () => {
              const { ActivitiesPage } = await activities();
              return { Component: () => <ActivitiesPage view="milestones" /> };
            },
            true,
          ),
          guarded(
            'actividades/calendario',
            'activity:read',
            async () => {
              const { ActivitiesPage } = await activities();
              return { Component: () => <ActivitiesPage view="calendar" /> };
            },
            true,
          ),
          guarded(
            'documentos',
            'document:read',
            async () => ({ Component: (await documents()).DocumentsPage }),
            true,
          ),
          guarded(
            'documentos/subir',
            'document:upload',
            async () => ({ Component: (await documents()).DocumentUploadPage }),
            true,
          ),
          guarded(
            'documentos/:id',
            'document:read',
            async () => ({ Component: (await documents()).DocumentDetailPage }),
            true,
          ),
          guarded(
            'documentos/evidencias',
            'document:read',
            async () => {
              return { Component: (await evidences()).EvidencesPage };
            },
            true,
          ),
          guarded(
            'documentos/:id/versiones',
            'document:read',
            async () => {
              const { DocumentDetailPage } = await documents();
              return { Component: () => <DocumentDetailPage versions /> };
            },
            true,
          ),
          guarded(
            'reportes',
            'report:read',
            async () => ({ Component: (await reports()).ReportsPage }),
            true,
          ),
          guarded(
            'reportes/exportacion',
            'report:export',
            async () => {
              const { ReportsPage } = await reports();
              return { Component: () => <ReportsPage exporting /> };
            },
            true,
          ),
          guarded(
            'reportes/publicaciones',
            'report:read',
            async () => ({ Component: (await reports()).PublicationsPage }),
            true,
          ),
          guarded(
            'reportes/proyectos/:id/informe-final',
            'report:read',
            async () => ({ Component: (await reports()).FinalReportPage }),
            true,
          ),
          guarded(
            'reportes/proyectos/:id/cierre',
            'project:close',
            async () => ({ Component: (await reports()).ProjectClosurePage }),
            true,
          ),
          guarded('usuarios', 'user:read', async () => ({
            Component: (await import('../features/admin/users-page')).UsersPage,
          })),
          guarded(
            'usuarios/nuevo',
            'user:create',
            async () => ({ Component: (await accounts()).UserCreatePage }),
            true,
          ),
          guarded(
            'usuarios/:id',
            'user:read',
            async () => ({ Component: (await accounts()).UserDetailPage }),
            true,
          ),
          guarded(
            'administracion/roles',
            'role:assign',
            async () => ({ Component: (await accounts()).RoleAssignmentPage }),
            true,
          ),
          guarded(
            'administracion/auditoria',
            'audit:read',
            async () => ({ Component: (await accounts()).AuditPage }),
            true,
          ),
          guarded('administracion/configuracion', 'user:read', async () => ({
            Component: (await profile()).PreferencesPage,
          })),
          guarded(
            'asistente',
            'ai:ask',
            async () => ({
              Component: (await import('../features/ai-assistant/assistant-chat-page'))
                .AssistantChatPage,
            }),
            true,
          ),
          guarded(
            'asistente/historial',
            'ai:ask',
            async () => ({
              Component: (await import('../features/ai-assistant/assistant-history-page'))
                .AssistantHistoryPage,
            }),
            true,
          ),
          guarded(
            'asistente/respuestas/:id/fuentes',
            'ai:ask',
            async () => ({
              Component: (await import('../features/ai-assistant/assistant-sources-page'))
                .AssistantSourcesPage,
            }),
            true,
          ),
          { path: 'sin-acceso', element: <ForbiddenPage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
];
