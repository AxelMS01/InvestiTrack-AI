import { z } from 'zod';
import { config } from '../lib/config';
import { ApiError, request } from './http-client';
import { authApi } from './auth.api';
import { can } from '../features/auth/model';
import { subjectKey } from '../features/auth/access-policy';
import { proposalInputSchema, type ProposalInput } from '../features/proposals/schema';
import { proposalSchema } from '../types/proposal';
import { departmentSchema } from '../types/department';

export const registeredProposalSchema = proposalSchema.extend({
  details: proposalInputSchema,
  departmentId: departmentSchema,
});
export type RegisteredProposal = z.infer<typeof registeredProposalSchema>;
const key = 'invesctictrack.demo.proposals.v1';
function read(): RegisteredProposal[] {
  const stored = localStorage.getItem(key);
  if (!stored) return [];
  try {
    return z.array(registeredProposalSchema).parse(JSON.parse(stored) as unknown);
  } catch {
    throw new Error('No se pueden leer las propuestas de demostración almacenadas.');
  }
}
export const proposalsApi = {
  list: async (): Promise<RegisteredProposal[]> => {
    if (!config.demo) return request('/proposals', z.array(registeredProposalSchema));
    const user = await authApi.me();
    if (!user || !can(user.role, 'createProposal'))
      throw new ApiError(403, 'No tienes acceso a las propuestas.');
    return read().filter(
      (proposal) =>
        user.role === 'admin' ||
        (user.role === 'coordinator' &&
          Boolean(user.departmentId) &&
          user.departmentId === proposal.departmentId) ||
        (proposal.ownerId === user.id &&
          Boolean(user.departmentId) &&
          user.departmentId === proposal.departmentId),
    );
  },
  detail: async (id: string) => {
    if (!config.demo)
      return request(`/proposals/${encodeURIComponent(id)}`, registeredProposalSchema);
    const proposal = (await proposalsApi.list()).find((item) => item.id === id);
    if (!proposal) throw new ApiError(404, 'La propuesta no está disponible para tu perfil.');
    return proposal;
  },
  create: async (input: ProposalInput): Promise<RegisteredProposal> => {
    const details = proposalInputSchema.parse(input);
    if (!config.demo)
      return request('/proposals', registeredProposalSchema, {
        method: 'POST',
        body: JSON.stringify(details),
      });
    const user = await authApi.me();
    if (!user || !can(user.role, 'createProposal') || !user.departmentId)
      throw new ApiError(403, 'Se requiere un perfil autorizado con departamento asignado.');
    const now = new Date().toISOString();
    const proposal = registeredProposalSchema.parse({
      id: crypto.randomUUID(),
      title: details.title,
      abstract: details.abstract,
      ownerId: user.id,
      departmentId: user.departmentId,
      status: 'submitted',
      version: 1,
      createdAt: now,
      updatedAt: now,
      details,
    });
    const current = await authApi.me();
    if (!current || subjectKey(user) !== subjectKey(current))
      throw new ApiError(403, 'El perfil cambió durante el registro.');
    localStorage.setItem(key, JSON.stringify([proposal, ...read()]));
    return proposal;
  },
};
