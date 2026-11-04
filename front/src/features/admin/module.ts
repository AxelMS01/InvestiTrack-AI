import type { ModuleDefinition } from '../module.types';

export const adminModule = {
  id: 'admin',
  label: 'Administración',
  screens: [
    {
      id: 'users',
      label: 'Gestión de usuarios (consulta)',
      path: '/usuarios',
      status: 'implemented',
      roles: ['admin'],
    },
    {
      id: 'roles',
      label: 'Roles',
      path: '/administracion/roles',
      status: 'planned',
      roles: ['admin'],
    },
    {
      id: 'audit',
      label: 'Auditoría',
      path: '/administracion/auditoria',
      status: 'planned',
      roles: ['admin'],
    },
    {
      id: 'settings',
      label: 'Configuración',
      path: '/administracion/configuracion',
      status: 'planned',
      roles: ['admin'],
    },
  ],
} as const satisfies ModuleDefinition;
