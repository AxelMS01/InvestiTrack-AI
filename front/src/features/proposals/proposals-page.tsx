import { Link } from 'react-router-dom';
import { PageHeading, AsyncState, EmptyState } from '../../components/ui';
import { proposalStatusLabels } from '../../types/proposal';
import { config } from '../../lib/config';
import { useAuth } from '../auth/auth-context';
import { can } from '../auth/model';
import { canCoordinateProposal } from '../../demo/access';
import { useProposals } from '../../api/proposals.queries';

export function ProposalsPage() {
  const query = useProposals();
  const { user, workspace } = useAuth();
  const proposals = (query.data ?? []).filter((proposal) => {
    if (!config.demo || !user) return true;
    if (workspace === 'reviewer') return proposal.reviewerIds.includes(user.id);
    if (workspace === 'coordinator') return canCoordinateProposal(user, proposal);
    return proposal.ownerId === user.id;
  });
  return (
    <>
      <PageHeading
        eyebrow="Propuestas científicas"
        title="Propuestas de investigación"
        description="Protocolos registrados que puedes consultar con tu perfil activo."
        action={
          config.demo &&
          user &&
          workspace !== 'reviewer' &&
          workspace !== 'coordinator' &&
          can(user, 'proposal:create') && (
            <Link className="btn-primary" to="/propuestas/nueva">
              Crear propuesta
            </Link>
          )
        }
      />
      <section className="panel divide-y divide-line">
        {query.isPending ? (
          <AsyncState loading />
        ) : query.error ? (
          <AsyncState error={query.error} retry={() => void query.refetch()} />
        ) : !proposals.length ? (
          <EmptyState
            title="Aún no hay propuestas"
            description="Los protocolos de tu espacio de trabajo aparecerán aquí."
          />
        ) : (
          proposals.map((proposal) => (
            <article key={proposal.id} className="p-6">
              <span className="status bg-sage text-forest">
                {proposalStatusLabels[proposal.status]} · versión {proposal.version}
              </span>
              <Link
                className="mt-3 block text-lg font-medium hover:text-accent"
                to={`/propuestas/${proposal.id}`}
              >
                {proposal.title || 'Propuesta sin título'}
              </Link>
              <p className="mt-2 line-clamp-2 text-sm text-muted">{proposal.abstract}</p>
            </article>
          ))
        )}
      </section>
    </>
  );
}
