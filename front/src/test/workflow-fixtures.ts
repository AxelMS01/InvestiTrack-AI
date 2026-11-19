import { authApi } from '../api/auth.api';
import { proposalsApi } from '../api/proposals.api';
import { addDays } from '../lib/format';
import { criteria, demoEvaluations, type Rubric } from '../demo/evaluations';
import type { ProposalInput } from '../features/proposals/schema';

export const validProposal: ProposalInput = {
  title: 'Medición de calidad ambiental',
  abstract:
    'Estudio sobre los indicadores ambientales y su variación en diferentes temporadas a partir de mediciones de campo y análisis de laboratorio.',
  objective: 'Documentar los cambios de indicadores ambientales a lo largo del ciclo de estudio.',
  methodology:
    'Se registrarán observaciones en fechas comparables y se revisará la calidad de las mediciones antes de sintetizar los resultados.',
  area: 'Ciencias ambientales',
  startDate: addDays(-1),
  endDate: addDays(120),
  team: [
    { name: 'Ana López', email: 'ana@example.com', contribution: 'Análisis de observaciones' },
  ],
  budgetItems: [{ concept: 'Trabajo de campo', amount: 10000, category: 'services' }],
  requestedFunding: 9000,
  involvesParticipants: false,
  ethicsApproval: false,
  declaration: true,
};
export const validRubric: Rubric = {
  scores: criteria.map((item) => ({
    criterionId: item.id,
    score: 90,
    comment: 'Criterio abordado.',
  })),
  feedback: 'El protocolo detalla sus procedimientos y alcances.',
  recommendation: 'approve',
};
export function pdf(name: string) {
  return new File(['%PDF-1.4\n%%EOF'], name, { type: 'application/pdf' });
}

export async function approveProposal() {
  await authApi.demoLogin('researcher');
  const draft = await proposalsApi.save({ details: validProposal });
  const submitted = await proposalsApi.submit({ id: draft.id, version: draft.version });
  await authApi.demoLogin('coordinator');
  const review = demoEvaluations.assign({
    proposalId: submitted.id,
    reviewerId: 'u4',
    dueDate: addDays(7),
  });
  await authApi.demoLogin('reviewer');
  demoEvaluations.save({ id: review.id, rubric: validRubric, submit: true });
  await authApi.demoLogin('coordinator');
  const ready = await proposalsApi.detail(draft.id);
  return demoEvaluations.decide({
    id: draft.id,
    version: ready.version,
    status: 'approved',
    note: 'El protocolo cumple los requisitos.',
    allowedFields: [],
  });
}
