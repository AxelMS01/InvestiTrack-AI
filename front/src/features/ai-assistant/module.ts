import type { ModuleDefinition } from '../module.types';

export const aiAssistantModule = {
  id: 'ai-assistant',
  label: 'Asistente IA',
  screens: [
    { id: 'chat', label: 'Chat con IA', path: '/asistente', status: 'implemented' },
    {
      id: 'sources',
      label: 'Fuentes de respuesta verificadas',
      path: '/asistente/respuestas/:id/fuentes',
      status: 'implemented',
    },
    { id: 'history', label: 'Historial', path: '/asistente/historial', status: 'implemented' },
  ],
} as const satisfies ModuleDefinition;
