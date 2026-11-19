import { config } from '../lib/config';
import type { AssistantAnswer, ChatInput } from '../features/ai-assistant/model';
import { demoAssistant } from '../demo/assistant';
import { ApiError } from './http-client';

interface AssistantTransport {
  ask: (values: ChatInput) => Promise<AssistantAnswer>;
  history: () => Promise<AssistantAnswer[]>;
  answer: (id: string) => Promise<AssistantAnswer>;
}

async function unavailable(): Promise<never> {
  throw new ApiError(501, 'El asistente institucional está pendiente de integración.');
}

export const assistantApi: AssistantTransport = config.demo
  ? {
      ask: demoAssistant.ask,
      history: async () => demoAssistant.history(),
      answer: async (id) => demoAssistant.answer(id),
    }
  : {
      ask: unavailable,
      history: unavailable,
      answer: unavailable,
    };
