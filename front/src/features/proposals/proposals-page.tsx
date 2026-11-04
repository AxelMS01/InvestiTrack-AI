import { Link } from 'react-router-dom';
import { PageHeading, AsyncState, EmptyState } from '../../components/ui';
import { useProposals } from '../../api/proposals.queries';

export function ProposalsPage() {
  const query = useProposals();
  return (
    <>
      <PageHeading
        eyebrow="Propuestas científicas"
        title="Propuestas de investigación"
        description="Protocolos registrados que puedes consultar con tu perfil activo."
        action={
          <Link className="btn-primary" to="/propuestas/nueva">
            Crear propuesta
          </Link>
        }
      />
      <section className="panel divide-y divide-line">
        {query.isPending ? (
          <AsyncState loading />
        ) : query.error ? (
          <AsyncState error={query.error} retry={() => void query.refetch()} />
        ) : !query.data.length ? (
          <EmptyState
            title="Aún no hay propuestas"
            description="Registra un protocolo con equipo, metodología y presupuesto para comenzar."
          />
        ) : (
          query.data.map((proposal) => (
            <article key={proposal.id} className="p-6">
              <span className="status bg-sage text-forest">
                Enviada · versión {proposal.version}
              </span>
              <Link
                className="mt-3 block text-lg font-medium hover:text-accent"
                to={`/propuestas/${proposal.id}`}
              >
                {proposal.title}
              </Link>
              <p className="mt-2 line-clamp-2 text-sm text-muted">{proposal.abstract}</p>
            </article>
          ))
        )}
      </section>
    </>
  );
}
