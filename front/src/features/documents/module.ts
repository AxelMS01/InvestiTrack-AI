import type { ModuleDefinition } from '../module.types';

export const documentsModule = {
  id: 'documents',
  label: 'Documentos',
  screens: [
    {
      id: 'repository',
      label: 'Documentos',
      path: '/documentos',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher', 'reviewer', 'student'],
    },
    {
      id: 'upload',
      label: 'Subir archivo',
      path: '/documentos/subir',
      status: 'MOCK_COMPLETE',
      roles: ['researcher', 'student'],
    },
    {
      id: 'detail',
      label: 'Detalle de documento',
      path: '/documentos/:id',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher', 'reviewer', 'student'],
    },
    {
      id: 'evidence',
      label: 'Evidencias',
      path: '/documentos/evidencias',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher', 'reviewer', 'student'],
    },
    {
      id: 'versions',
      label: 'Versiones de documento',
      path: '/documentos/:id/versiones',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher', 'reviewer', 'student'],
    },
  ],
} as const satisfies ModuleDefinition;
