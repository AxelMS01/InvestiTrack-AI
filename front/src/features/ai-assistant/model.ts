import { z } from 'zod';

export const chatInputSchema = z.object({
  prompt: z
    .string()
    .trim()
    .min(5, 'Escribe al menos 5 caracteres.')
    .max(1500, 'Máximo 1500 caracteres.'),
  projectId: z.string().optional(),
});
export type ChatInput = z.infer<typeof chatInputSchema>;
export interface AssistantSource {
  id: string;
  projectId: string;
  title: string;
  excerpt: string;
  version: number;
}
export interface AssistantAnswer {
  id: string;
  question: string;
  text: string;
  createdAt: string;
  sources: AssistantSource[];
  denied: boolean;
}
