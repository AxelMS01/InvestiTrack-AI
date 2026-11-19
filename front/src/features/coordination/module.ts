import type { ModuleDefinition } from '../module.types';

export const coordinationModule = {
  id: 'coordination',
  label: 'Coordinación',
  screens: [
    {
      id: 'home',
      label: 'Mi coordinación',
      path: '/coordinacion',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator'],
    },
    {
      id: 'assign-list',
      label: 'Asignar evaluadores de proyecto',
      path: '/coordinacion/evaluadores',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator'],
    },
    {
      id: 'assign',
      label: 'Asignar evaluador',
      path: '/proyectos/:id/evaluadores',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator'],
    },
    {
      id: 'propose',
      label: 'Proponer estudiante',
      path: '/proyectos/:id/proponer-estudiante',
      status: 'MOCK_COMPLETE',
      roles: ['researcher'],
    },
    {
      id: 'requests',
      label: 'Solicitudes de estudiantes',
      path: '/solicitudes-estudiantes',
      status: 'MOCK_COMPLETE',
      roles: ['researcher', 'coordinator'],
    },
    {
      id: 'request',
      label: 'Detalle de solicitud',
      path: '/solicitudes-estudiantes/:id',
      status: 'MOCK_COMPLETE',
      roles: ['researcher', 'coordinator'],
    },
  ],
} as const satisfies ModuleDefinition;
