import { describe, expect, it } from 'vitest';
import { projectInputSchema, type ProjectInput } from '../../types/project';

const valid: ProjectInput = {
  title: 'Monitoreo ambiental comunitario',
  description: 'Evaluación de la calidad del agua y sus efectos en las comunidades locales.',
  area: 'Ciencias ambientales',
  budget: 120000,
  startDate: '2026-10-01',
  endDate: '2027-04-30',
};
describe('Validación del registro de proyectos', () => {
  it('normaliza espacios antes de registrar', () => {
    expect(projectInputSchema.parse({ ...valid, title: `  ${valid.title}  ` }).title).toBe(
      valid.title,
    );
  });
  it.each([
    { budget: -1 },
    { budget: Number.NaN },
    { budget: Infinity },
    { endDate: '2026-01-01' },
    { startDate: '2026-02-30' },
    { startDate: '' },
    { title: '  corto ' },
    { description: 'sin detalle' },
    { area: 'Área desconocida' },
  ])('rechaza datos inválidos: %o', (overrides) => {
    expect(projectInputSchema.safeParse({ ...valid, ...overrides }).success).toBe(false);
  });
});
