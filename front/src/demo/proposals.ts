import { proposalInputSchema } from '../features/proposals/schema';
import {
  proposalDraftSchema,
  type ProposalDraft,
  type ProposalStatus,
  type RegisteredProposal,
} from '../types/proposal';
import {
  canEditProposal,
  canReadProposal,
  checkVersion,
  fail,
  requireDemoUser,
  visibleProposal,
} from './access';
import { readDatabase, updateDatabase } from './database';

export function recordProposal(
  proposal: RegisteredProposal,
  status: ProposalStatus,
  actorId: string,
  note: string,
) {
  const at = new Date().toISOString();
  proposal.status = status;
  proposal.updatedAt = at;
  proposal.version += 1;
  proposal.history.push({ status, at, actorId, note });
}

export const demoProposals = {
  list() {
    const user = requireDemoUser('proposal:read');
    return readDatabase().proposals.filter((proposal) => canReadProposal(user, proposal));
  },
  detail(id: string) {
    return visibleProposal(readDatabase(), requireDemoUser('proposal:read'), id);
  },
  save(input: { details: ProposalDraft; id?: string; version?: number }) {
    const user = requireDemoUser(input.id ? 'proposal:update' : 'proposal:create');
    const result = proposalDraftSchema.safeParse(input.details);
    if (!result.success)
      fail(422, 'Revisa los límites de los campos antes de guardar el borrador.');
    const details = result.data;
    return updateDatabase((data) => {
      if (input.id) {
        const existing = visibleProposal(data, user, input.id);
        if (!canEditProposal(user, existing)) fail(403, 'La propuesta ya no admite cambios.');
        checkVersion(input.version ?? 0, existing.version);
        if (
          existing.status === 'changes_requested' &&
          proposalDraftSchema
            .keyof()
            .options.some(
              (field) =>
                !existing.allowedFields.includes(field) &&
                JSON.stringify(existing.details[field]) !== JSON.stringify(details[field]),
            )
        )
          fail(403, 'Solo puedes modificar los campos indicados en la solicitud de cambios.');
        Object.assign(existing, { details, title: details.title, abstract: details.abstract });
        recordProposal(existing, existing.status, user.id, 'Borrador actualizado.');
        return existing;
      }
      if (!user.departmentId)
        fail(403, 'Tu cuenta requiere un departamento para registrar propuestas.');
      const at = new Date().toISOString();
      const proposal: RegisteredProposal = {
        id: crypto.randomUUID(),
        ownerId: user.id,
        departmentId: user.departmentId,
        status: 'draft',
        title: details.title,
        abstract: details.abstract,
        version: 1,
        createdAt: at,
        updatedAt: at,
        details,
        reviewerIds: [],
        allowedFields: [],
        history: [{ status: 'draft', at, actorId: user.id, note: 'Borrador creado.' }],
      };
      data.proposals.unshift(proposal);
      return proposal;
    });
  },
  submit(id: string, version: number) {
    const user = requireDemoUser('proposal:submit');
    return updateDatabase((data) => {
      const proposal = visibleProposal(data, user, id);
      if (!canEditProposal(user, proposal)) fail(403, 'La propuesta ya no se puede enviar.');
      checkVersion(version, proposal.version);
      if (!proposalInputSchema.safeParse(proposal.details).success)
        fail(422, 'Completa los campos obligatorios antes de enviar.');
      proposal.allowedFields = [];
      proposal.reviewerIds = [];
      // Keep historical reviews for coordinators while requiring a fresh rubric for this submission.
      data.evaluations.forEach((evaluation) => {
        if (evaluation.proposalId === id && evaluation.status !== 'submitted')
          evaluation.superseded = true;
      });
      recordProposal(proposal, 'submitted', user.id, 'Enviada a coordinación para su evaluación.');
      proposal.submissionVersion = proposal.version;
      return proposal;
    });
  },
  cancel(id: string, version: number) {
    const user = requireDemoUser('proposal:update');
    return updateDatabase((data) => {
      const proposal = visibleProposal(data, user, id);
      if (proposal.ownerId !== user.id || proposal.status !== 'draft')
        fail(403, 'Solo se pueden cancelar borradores propios.');
      checkVersion(version, proposal.version);
      recordProposal(proposal, 'cancelled', user.id, 'Borrador cancelado.');
      return proposal;
    });
  },
};
