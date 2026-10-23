import type { ModuleDefinition } from '../module.types';

export const documentsModule = {
  id: 'documents',
  label: 'Documentos',
  screens: [
    { id: 'repository', label: 'Repositorio', path: '/documentos', status: 'planned' },
    { id: 'upload', label: 'Subir', path: '/documentos/subir', status: 'planned' },
    { id: 'detail', label: 'Detalle', path: '/documentos/:id', status: 'planned' },
    { id: 'evidence', label: 'Evidencias', path: '/documentos/evidencias', status: 'planned' },
    { id: 'versions', label: 'Versiones', path: '/documentos/:id/versiones', status: 'planned' },
  ],
} as const satisfies ModuleDefinition;
