import type { ModuleDefinition } from '../module.types';

export const activitiesModule = {
  id: 'activities',
  label: 'Actividades',
  screens: [
    { id: 'list', label: 'Lista', path: '/actividades', status: 'planned' },
    { id: 'create', label: 'Crear', path: '/actividades/nueva', status: 'planned' },
    { id: 'edit', label: 'Editar', path: '/actividades/:id/editar', status: 'planned' },
    { id: 'detail', label: 'Detalle', path: '/actividades/:id', status: 'planned' },
    { id: 'milestones', label: 'Hitos', path: '/actividades/hitos', status: 'planned' },
    { id: 'calendar', label: 'Calendario', path: '/actividades/calendario', status: 'planned' },
  ],
} as const satisfies ModuleDefinition;
