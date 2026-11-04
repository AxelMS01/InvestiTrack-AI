import { describe, expect, it } from 'vitest';
import { proposalInputSchema, type ProposalInput } from './schema';

const validProposal: ProposalInput = {
  title: 'Monitoreo ambiental de comunidades rurales',
  abstract:
    'Investigación participativa para identificar factores ambientales y evaluar la calidad del agua en comunidades rurales de la región.',
  objective:
    'Evaluar las condiciones ambientales y proponer medidas para mejorar la calidad del agua.',
  methodology:
    'Se realizarán muestreos periódicos con instrumentos calibrados, análisis de laboratorio y comparación estadística de resultados en comunidades rurales.',
  area: 'Ciencias ambientales',
  startDate: '2026-11-01',
  endDate: '2027-06-30',
  team: [{ name: 'Ana López', email: 'ana@example.com', contribution: 'Análisis de laboratorio' }],
  budgetItems: [{ concept: 'Análisis de muestras', category: 'services', amount: 150000 }],
  requestedFunding: 120000,
  involvesParticipants: false,
  ethicsApproval: false,
  declaration: true,
};
describe('Integridad de propuestas', () => {
  it('acepta un protocolo completo', () =>
    expect(proposalInputSchema.safeParse(validProposal).success).toBe(true));
  it.each([
    { requestedFunding: 100.001 },
    { endDate: '2026-01-01' },
    { requestedFunding: 200000 },
    { declaration: false },
    { involvesParticipants: true, ethicsApproval: false },
    { team: [] },
    { budgetItems: [{ concept: 'Análisis', category: 'services', amount: Number.NaN }] },
    { team: [...validProposal.team, { ...validProposal.team[0], email: 'ANA@example.com' }] },
    {
      budgetItems: [
        { concept: 'Análisis', category: 'services', amount: 800000 },
        { concept: 'Materiales', category: 'materials', amount: 800000 },
      ],
    },
  ])('rechaza la inconsistencia %o', (override) =>
    expect(proposalInputSchema.safeParse({ ...validProposal, ...override }).success).toBe(false),
  );
});
