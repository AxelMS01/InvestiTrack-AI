import { z } from 'zod';
import { config } from '../lib/config';
import { request } from './http-client';
import {
  registeredProposalSchema,
  type ProposalDraft,
  type RegisteredProposal,
} from '../types/proposal';
import { demoProposals } from '../demo/proposals';
export { registeredProposalSchema, type RegisteredProposal } from '../types/proposal';

export const proposalsApi = {
  list: async (): Promise<RegisteredProposal[]> =>
    config.demo ? demoProposals.list() : request('/proposals', z.array(registeredProposalSchema)),
  detail: async (id: string): Promise<RegisteredProposal> =>
    config.demo
      ? demoProposals.detail(id)
      : request(`/proposals/${encodeURIComponent(id)}`, registeredProposalSchema),
  save: async (input: { details: ProposalDraft; id?: string; version?: number }) =>
    demoProposals.save(input),
  submit: async ({ id, version }: { id: string; version: number }) =>
    demoProposals.submit(id, version),
  cancel: async ({ id, version }: { id: string; version: number }) =>
    demoProposals.cancel(id, version),
};
