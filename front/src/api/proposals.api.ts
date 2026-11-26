import { z } from 'zod';
import { config } from '../lib/config';
import { ApiError, request } from './http-client';
import {
  registeredProposalSchema,
  type ProposalDraft,
  type RegisteredProposal,
} from '../types/proposal';
import { demoProposals } from '../demo/proposals';
export { registeredProposalSchema, type RegisteredProposal } from '../types/proposal';

function demoOnly() {
  if (!config.demo)
    throw new ApiError(
      503,
      'Esta operación no está disponible en esta pantalla. Usa el flujo institucional de propuestas.',
    );
}

export const proposalsApi = {
  list: async (): Promise<RegisteredProposal[]> =>
    config.demo ? demoProposals.list() : request('/proposals', z.array(registeredProposalSchema)),
  detail: async (id: string): Promise<RegisteredProposal> =>
    config.demo
      ? demoProposals.detail(id)
      : request(`/proposals/${encodeURIComponent(id)}`, registeredProposalSchema),
  save: async (input: { details: ProposalDraft; id?: string; version?: number }) => {
    demoOnly();
    return demoProposals.save(input);
  },
  submit: async ({ id, version }: { id: string; version: number }) => {
    demoOnly();
    return demoProposals.submit(id, version);
  },
  cancel: async ({ id, version }: { id: string; version: number }) => {
    demoOnly();
    return demoProposals.cancel(id, version);
  },
};
