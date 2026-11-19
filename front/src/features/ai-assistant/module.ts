import type { ModuleDefinition } from '../module.types';

export const aiAssistantModule = {
  id: 'ai-assistant',
  label: 'Asistente IA',
  screens: [
    {
      id: 'chat',
      label: 'Asistente IA',
      path: '/asistente',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher', 'reviewer', 'student'],
    },
    {
      id: 'sources',
      label: 'Fuentes de la respuesta',
      path: '/asistente/respuestas/:id/fuentes',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher', 'reviewer', 'student'],
    },
    {
      id: 'history',
      label: 'Historial del asistente',
      path: '/asistente/historial',
      status: 'MOCK_COMPLETE',
      roles: ['coordinator', 'researcher', 'reviewer', 'student'],
    },
  ],
} as const satisfies ModuleDefinition;
