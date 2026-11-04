import type { ModuleDefinition } from '../module.types';
import { permissions } from '../auth/model';

export const projectsModule = {
  id: 'projects',
  label: 'Proyectos',
  screens: [
    { id: 'list', label: 'Lista', path: '/proyectos', status: 'implemented' },
    {
      id: 'create',
      label: 'Registrar',
      path: '/proyectos/nuevo',
      status: 'implemented',
      roles: permissions.createProject,
    },
    { id: 'detail', label: 'Detalle', path: '/proyectos/:id', status: 'implemented' },
    {
      id: 'settings',
      label: 'Configuración',
      path: '/proyectos/:id/configuracion',
      status: 'planned',
    },
    {
      id: 'collaborators',
      label: 'Colaboradores',
      path: '/proyectos/:id/colaboradores',
      status: 'planned',
    },
    { id: 'tracking', label: 'Seguimiento', path: '/proyectos/:id/seguimiento', status: 'planned' },
  ],
} as const satisfies ModuleDefinition;
