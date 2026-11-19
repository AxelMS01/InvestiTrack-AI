import type { ModuleDefinition } from '../module.types';

export const proposalsModule = {
  id: 'proposals',
  label: 'Propuestas',
  screens: [
    {
      id: 'list',
      label: 'Propuestas',
      path: '/propuestas',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher', 'reviewer'],
    },
    {
      id: 'create',
      label: 'Crear propuesta',
      path: '/propuestas/nueva',
      status: 'MOCK_COMPLETE',
      roles: ['researcher'],
    },
    {
      id: 'detail',
      label: 'Detalle de propuesta',
      path: '/propuestas/:id',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher', 'reviewer'],
    },
    {
      id: 'edit',
      label: 'Editar propuesta',
      path: '/propuestas/:id/editar',
      status: 'MOCK_COMPLETE',
      roles: ['researcher'],
    },
    {
      id: 'history',
      label: 'Historial de propuesta',
      path: '/propuestas/:id/historial',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher', 'reviewer'],
    },
    {
      id: 'review',
      label: 'Revisar y enviar',
      path: '/propuestas/:id/revision',
      status: 'MOCK_COMPLETE',
      roles: ['researcher'],
    },
  ],
} as const satisfies ModuleDefinition;
