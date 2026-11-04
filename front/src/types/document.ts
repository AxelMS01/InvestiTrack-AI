import { z } from 'zod';
import { entityIdSchema } from './shared';

export const documentSchema = z.object({
  id: entityIdSchema,
  projectId: entityIdSchema,
  name: z.string().min(1),
  mimeType: z.string(),
  sizeBytes: z.number().int().nonnegative(),
  version: z.number().int().positive(),
  uploadedBy: entityIdSchema,
  uploadedAt: z.iso.datetime(),
  category: z.enum(['protocol', 'evidence', 'report', 'publication', 'other']),
});
// Import explicitly to distinguish this domain entity from the browser DOM Document.
export type Document = z.infer<typeof documentSchema>;
