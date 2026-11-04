import type { ModuleDefinition } from '../module.types';

export const proposalsModule = {
  id: 'proposals',
  label: 'Propuestas',
  screens: [
    { id: 'list', label: 'Lista', path: '/propuestas', status: 'implemented' },
    { id: 'create', label: 'Crear', path: '/propuestas/nueva', status: 'implemented' },
    { id: 'detail', label: 'Detalle', path: '/propuestas/:id', status: 'implemented' },
    { id: 'edit', label: 'Editar', path: '/propuestas/:id/editar', status: 'planned' },
    { id: 'history', label: 'Historial', path: '/propuestas/:id/historial', status: 'planned' },
  ],
} as const satisfies ModuleDefinition;
