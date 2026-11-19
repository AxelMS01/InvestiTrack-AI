import type { ModuleDefinition } from '../module.types';

export const projectsModule = {
  id: 'projects',
  label: 'Proyectos',
  screens: [
    {
      id: 'list',
      label: 'Proyectos',
      path: '/proyectos',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher', 'student'],
    },
    {
      id: 'create',
      label: 'Convertir propuesta aprobada',
      path: '/proyectos/nuevo',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher'],
    },
    {
      id: 'detail',
      label: 'Detalle de proyecto',
      path: '/proyectos/:id',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher', 'student'],
    },
    {
      id: 'settings',
      label: 'Configuración del proyecto',
      path: '/proyectos/:id/configuracion',
      status: 'MOCK_COMPLETE',
      roles: ['researcher'],
    },
    {
      id: 'collaborators',
      label: 'Colaboradores',
      path: '/proyectos/:id/colaboradores',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher', 'student'],
    },
    {
      id: 'tracking',
      label: 'Seguimiento del proyecto',
      path: '/proyectos/:id/seguimiento',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher', 'student'],
    },
  ],
} as const satisfies ModuleDefinition;
