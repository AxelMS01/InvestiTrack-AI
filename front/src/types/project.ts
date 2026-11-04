import { z } from 'zod';
import { dateSchema } from './shared';
import { departmentSchema } from './department';

export const statusSchema = z.enum(['planning', 'active', 'review', 'completed']);
export type ProjectStatus = z.infer<typeof statusSchema>;
export const statusLabels: Record<ProjectStatus, string> = {
  planning: 'En planeación',
  active: 'En ejecución',
  review: 'En revisión',
  completed: 'Finalizado',
};
export const areas = [
  'Ciencias ambientales',
  'Tecnología e innovación',
  'Salud y bienestar',
  'Ciencias sociales',
] as const;
export const projectInputSchema = z
  .object({
    title: z
      .string()
      .trim()
      .min(8, 'Escribe un título de al menos 8 caracteres.')
      .max(160, 'Máximo 160 caracteres.'),
    description: z
      .string()
      .trim()
      .min(30, 'Describe el proyecto con al menos 30 caracteres.')
      .max(3000),
    area: z.enum(areas),
    budget: z
      .number('Ingresa un monto válido.')
      .finite()
      .min(0, 'El presupuesto no puede ser negativo.')
      .max(1_000_000_000, 'El monto supera el límite permitido.'),
    startDate: dateSchema,
    endDate: dateSchema,
  })
  .refine((value) => value.endDate >= value.startDate, {
    message: 'La fecha de cierre debe ser igual o posterior al inicio.',
    path: ['endDate'],
  });
export type ProjectInput = z.infer<typeof projectInputSchema>;
export const projectSchema = z.object({
  departmentId: departmentSchema.optional(),
  id: z.string(),
  code: z.string(),
  title: z.string(),
  description: z.string(),
  area: z.enum(areas),
  budget: z.number().nonnegative(),
  startDate: dateSchema,
  endDate: dateSchema,
  status: statusSchema,
  progress: z.number().min(0).max(100),
  lead: z.string(),
  leadId: z.string(),
});
export type Project = z.infer<typeof projectSchema>;
