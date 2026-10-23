import type { ModuleDefinition } from '../module.types';

export const reportsModule = {
  id: 'reports',
  label: 'Reportes',
  screens: [
    { id: 'indicators', label: 'Indicadores', path: '/reportes', status: 'planned' },
    { id: 'export', label: 'Exportación', path: '/reportes/exportacion', status: 'planned' },
    {
      id: 'final-report',
      label: 'Informe final',
      path: '/reportes/proyectos/:id/informe-final',
      status: 'planned',
    },
    {
      id: 'publications',
      label: 'Publicaciones',
      path: '/reportes/publicaciones',
      status: 'planned',
    },
    { id: 'closure', label: 'Cierre', path: '/reportes/proyectos/:id/cierre', status: 'planned' },
  ],
} as const satisfies ModuleDefinition;
