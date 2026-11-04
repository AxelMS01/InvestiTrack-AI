import { Link, useParams } from 'react-router-dom';
import { AsyncState, PageHeading } from '../../components/ui';
import { useProposal } from '../../api/proposals.queries';
import { currency, dateLabel } from '../../lib/format';
import { departmentLabels } from '../../types/department';

export function ProposalDetailPage() {
  const { id = '' } = useParams();
  const query = useProposal(id);
  if (query.isPending) return <AsyncState loading />;
  if (query.error) return <AsyncState error={query.error} retry={() => void query.refetch()} />;
  const proposal = query.data;
  const details = proposal.details;
  return (
    <>
      <PageHeading
        eyebrow="Propuesta enviada"
        title={proposal.title}
        description={`${departmentLabels[proposal.departmentId]} · ${details.area}`}
        action={
          <Link to="/propuestas" className="btn-secondary">
            Volver a propuestas
          </Link>
        }
      />
      <section className="panel space-y-7 p-6">
        <p role="status" className="rounded-lg bg-sage p-4 text-sm text-forest">
          Protocolo registrado correctamente. Responsable y departamento asignados desde la sesión.
        </p>
        {[
          ['Resumen', details.abstract],
          ['Objetivo general', details.objective],
          ['Metodología', details.methodology],
        ].map(([label, value]) => (
          <div key={label}>
            <h2 className="section-title">{label}</h2>
            <p className="mt-3 whitespace-pre-wrap text-sm leading-7 text-muted">{value}</p>
          </div>
        ))}
        <div>
          <h2 className="section-title">Periodo</h2>
          <p className="mt-3 text-sm">
            {dateLabel(details.startDate)} — {dateLabel(details.endDate)}
          </p>
        </div>
        <div>
          <h2 className="section-title">Equipo colaborador</h2>
          <ul className="mt-3 space-y-3 text-sm">
            {details.team.map((member) => (
              <li key={member.email}>
                <strong>{member.name}</strong> · {member.email}
                <p className="mt-1 text-muted">{member.contribution}</p>
              </li>
            ))}
          </ul>
        </div>
        <div>
          <h2 className="section-title">Presupuesto registrado</h2>
          <ul className="mt-3 space-y-2 text-sm">
            {details.budgetItems.map((item, index) => (
              <li key={index}>
                {item.concept} · {currency(item.amount)} MXN
              </li>
            ))}
          </ul>
          <p className="mt-4 font-semibold">
            Financiamiento solicitado: {currency(details.requestedFunding)} MXN
          </p>
        </div>
        <p className="text-sm text-muted">
          Participantes humanos:{' '}
          {details.involvesParticipants ? 'Sí, con aprobación ética declarada' : 'No'}. Integridad
          de datos confirmada.
        </p>
      </section>
    </>
  );
}
