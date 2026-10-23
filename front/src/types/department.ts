import { z } from 'zod';

export const departmentSchema = z.enum(['environment', 'engineering', 'health', 'social']);
export type DepartmentId = z.infer<typeof departmentSchema>;
export const departmentLabels: Record<DepartmentId, string> = {
  environment: 'Ciencias ambientales',
  engineering: 'Ingeniería y tecnología',
  health: 'Ciencias de la salud',
  social: 'Ciencias sociales',
};
