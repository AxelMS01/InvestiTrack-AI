import type { ModuleDefinition } from '../module.types';

export const activitiesModule = {
  id: 'activities',
  label: 'Actividades',
  screens: [
    {
      id: 'list',
      label: 'Actividades',
      path: '/actividades',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher', 'student'],
    },
    {
      id: 'create',
      label: 'Nueva actividad o hito',
      path: '/actividades/nueva',
      status: 'MOCK_COMPLETE',
      roles: ['researcher'],
    },
    {
      id: 'edit',
      label: 'Editar actividad',
      path: '/actividades/:id/editar',
      status: 'MOCK_COMPLETE',
      roles: ['researcher'],
    },
    {
      id: 'detail',
      label: 'Detalle de actividad',
      path: '/actividades/:id',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher', 'student'],
    },
    {
      id: 'milestones',
      label: 'Hitos',
      path: '/actividades/hitos',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher', 'student'],
    },
    {
      id: 'calendar',
      label: 'Calendario',
      path: '/actividades/calendario',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher', 'student'],
    },
  ],
} as const satisfies ModuleDefinition;
