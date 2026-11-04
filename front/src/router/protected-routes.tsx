import type { RouteObject } from 'react-router-dom';
import { DashboardLayout } from '../layouts/dashboard-layout';
import { RequireAuth } from './require-auth';
import { permissions } from '../features/auth/model';
import { ForbiddenPage } from '../features/auth/forbidden-page';
import { NotFoundPage } from './status-pages';

export const protectedRoutes: RouteObject[] = [
  {
    element: <RequireAuth />,
    children: [
      {
        element: <DashboardLayout />,
        children: [
          {
            path: 'asistente',
            lazy: async () => ({
              Component: (await import('../features/ai-assistant/assistant-chat-page'))
                .AssistantChatPage,
            }),
          },
          {
            path: 'asistente/historial',
            lazy: async () => ({
              Component: (await import('../features/ai-assistant/assistant-history-page'))
                .AssistantHistoryPage,
            }),
          },
          {
            path: 'asistente/respuestas/:id/fuentes',
            lazy: async () => ({
              Component: (await import('../features/ai-assistant/assistant-sources-page'))
                .AssistantSourcesPage,
            }),
          },
          {
            element: <RequireAuth roles={permissions.createProposal} />,
            children: [
              {
                path: 'propuestas',
                lazy: async () => ({
                  Component: (await import('../features/proposals/proposals-page')).ProposalsPage,
                }),
              },
              {
                path: 'propuestas/nueva',
                lazy: async () => ({
                  Component: (await import('../features/proposals/proposal-create-page'))
                    .ProposalCreatePage,
                }),
              },
              {
                path: 'propuestas/:id',
                lazy: async () => ({
                  Component: (await import('../features/proposals/proposal-detail-page'))
                    .ProposalDetailPage,
                }),
              },
            ],
          },
          {
            index: true,
            lazy: async () => ({
              Component: (await import('../features/dashboard/dashboard-page')).DashboardPage,
            }),
          },
          {
            path: 'proyectos',
            lazy: async () => ({
              Component: (await import('../features/projects/projects-page')).ProjectsPage,
            }),
          },
          {
            path: 'proyectos/:id',
            lazy: async () => ({
              Component: (await import('../features/projects/project-detail-page'))
                .ProjectDetailPage,
            }),
          },
          {
            element: <RequireAuth roles={permissions.createProject} />,
            children: [
              {
                path: 'proyectos/nuevo',
                lazy: async () => ({
                  Component: (await import('../features/projects/project-create-page'))
                    .ProjectCreatePage,
                }),
              },
            ],
          },
          {
            element: <RequireAuth roles={permissions.manageUsers} />,
            children: [
              {
                path: 'usuarios',
                lazy: async () => ({
                  Component: (await import('../features/admin/users-page')).UsersPage,
                }),
              },
            ],
          },
          { path: 'sin-acceso', element: <ForbiddenPage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
];
