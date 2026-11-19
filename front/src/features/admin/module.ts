import type { ModuleDefinition } from '../module.types';

export const adminModule = {
  id: 'admin',
  label: 'Administración',
  screens: [
    {
      id: 'units',
      label: 'Unidades académicas',
      path: '/administracion/unidades',
      status: 'MOCK_COMPLETE',
      roles: ['admin'],
    },
    {
      id: 'unit',
      label: 'Detalle de unidad',
      path: '/administracion/unidades/:id',
      status: 'MOCK_COMPLETE',
      roles: ['admin'],
    },
    {
      id: 'users',
      label: 'Usuarios',
      path: '/usuarios',
      status: 'MOCK_COMPLETE',
      roles: ['admin'],
    },
    {
      id: 'create-user',
      label: 'Crear cuenta',
      path: '/usuarios/nuevo',
      status: 'MOCK_COMPLETE',
      roles: ['admin'],
    },
    {
      id: 'user-detail',
      label: 'Detalle de usuario',
      path: '/usuarios/:id',
      status: 'MOCK_COMPLETE',
      roles: ['admin'],
    },
    {
      id: 'roles',
      label: 'Asignación de roles',
      path: '/administracion/roles',
      status: 'MOCK_COMPLETE',
      roles: ['admin'],
    },
    {
      id: 'audit',
      label: 'Auditoría de accesos',
      path: '/administracion/auditoria',
      status: 'MOCK_COMPLETE',
      roles: ['admin'],
    },
    {
      id: 'settings',
      label: 'Preferencias administrativas',
      path: '/administracion/configuracion',
      status: 'MOCK_COMPLETE',
      roles: ['admin'],
    },
  ],
} as const satisfies ModuleDefinition;
