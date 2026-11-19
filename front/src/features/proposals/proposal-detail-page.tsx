import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { useProposal } from '../../api/proposals.queries';
import { proposalsApi } from '../../api/proposals.api';
import { useDemoQuery, useWorkflowMutation } from '../../api/demo.queries';
import { PageHeading } from '../../components/ui';
import { Button } from '../../components/button';
import { currency, dateLabel } from '../../lib/format';
import { departmentLabels } from '../../types/department';
import { proposalStatusLabels, type RegisteredProposal } from '../../types/proposal';
import { useAuth } from '../auth/auth-context';
import { can } from '../auth/model';
import { canCoordinateProposal, canEditProposal } from '../../demo/access';
import { demoProjects } from '../../demo/projects';
import { demoDocuments } from '../../demo/documents';
import { config } from '../../lib/config';
import { ActionLink, Feedback, WorkflowList, WorkflowState } from '../shared/workflow-ui';
import { proposalInputSchema } from './schema';

function Protocol({ proposal }: { proposal: RegisteredProposal }) {
  const { details } = proposal;
  return (
    <section className="panel space-y-6 p-6">
      {[
        ['Resumen', details.abstract],
        ['Objetivo general', details.objective],
        ['Metodología', details.methodology],
      ].map(([label, value]) => (
        <div key={label}>
          <h2 className="section-title">{label}</h2>
          <p className="mt-2 whitespace-pre-wrap text-sm leading-7 text-muted">
            {value || 'Pendiente de completar'}
          </p>
        </div>
      ))}
      <div>
        <h2 className="section-title">Periodo</h2>
        <p className="mt-2 text-sm">
          {details.startDate && details.endDate
            ? `${dateLabel(details.startDate)} – ${dateLabel(details.endDate)}`
            : 'Pendiente de completar'}
        </p>
      </div>
      <div>
        <h2 className="section-title">Equipo colaborador</h2>
        <ul className="mt-2 space-y-2 text-sm">
          {details.team
            .filter((item) => item.name)
            .map((item, index) => (
              <li key={index}>
                <strong>{item.name}</strong> · {item.email}
                <p className="text-muted">{item.contribution}</p>
              </li>
            ))}
        </ul>
      </div>
      <div>
        <h2 className="section-title">Presupuesto registrado</h2>
        <ul className="mt-2 space-y-2 text-sm">
          {details.budgetItems
            .filter((item) => item.concept)
            .map((item, index) => (
              <li key={index}>
                {item.concept} · {currency(item.amount)} MXN
              </li>
            ))}
        </ul>
        <p className="mt-2 text-sm">
          Financiamiento solicitado · {currency(details.requestedFunding)} MXN
        </p>
      </div>
      <p className="text-sm text-muted">
        {details.involvesParticipants
          ? 'Incluye participantes humanos.'
          : 'Sin participantes humanos.'}{' '}
        {details.ethicsApproval && 'Aprobación ética declarada. '}
        {details.declaration && 'Integridad de datos confirmada.'}
      </p>
    </section>
  );
}

export function ProposalDetailPage() {
  const { id = '' } = useParams();
  const query = useProposal(id);
  const { user, workspace } = useAuth();
  const location = useLocation();
  const cancel = useWorkflowMutation(proposalsApi.cancel);
  const proposal = query.data;
  const convertible = useDemoQuery(
    ['convertible-proposals'],
    demoProjects.convertible,
    Boolean(config.demo && proposal?.status === 'approved' && user && can(user, 'project:convert')),
  );
  return (
    <WorkflowState query={query}>
      {proposal && (
        <>
          <PageHeading
            eyebrow={proposalStatusLabels[proposal.status]}
            title={proposal.title || 'Propuesta sin título'}
            description={`${proposal.departmentId ? departmentLabels[proposal.departmentId] : 'Unidad académica asignada'} · ${proposal.details.area} · Versión ${proposal.version}`}
            action={<ActionLink to="/propuestas">Volver a propuestas</ActionLink>}
          />
          <div className="mb-5 space-y-3">
            <Feedback
              error={cancel.error}
              success={
                cancel.isSuccess
                  ? 'Borrador cancelado.'
                  : location.state?.submitted
                    ? 'Propuesta enviada. Coordinación recibirá el protocolo para asignar su evaluación.'
                    : location.state?.saved
                      ? 'Borrador guardado.'
                      : false
              }
            />
            {proposal.status === 'changes_requested' && (
              <p className="panel p-4 text-sm">
                {
                  proposal.history
                    .slice()
                    .reverse()
                    .find((item) => item.status === 'changes_requested')?.note
                }
              </p>
            )}
            <div className="flex flex-wrap gap-2">
              <ActionLink to={`/propuestas/${id}/historial`}>Historial</ActionLink>
              {config.demo &&
                user &&
                workspace !== 'reviewer' &&
                canEditProposal(user, proposal) && (
                  <>
                    <ActionLink to={`/propuestas/${id}/editar`} primary>
                      Editar propuesta
                    </ActionLink>
                    {can(user, 'proposal:submit') && (
                      <ActionLink to={`/propuestas/${id}/revision`}>Revisar envío</ActionLink>
                    )}
                    {proposal.status === 'draft' && (
                      <>
                        {can(user, 'document:upload') && (
                          <ActionLink to={`/documentos/subir?propuesta=${id}`}>
                            Adjuntar documento
                          </ActionLink>
                        )}
                        <Button
                          variant="secondary"
                          disabled={cancel.isPending}
                          onClick={() => {
                            if (window.confirm('¿Cancelar este borrador?'))
                              cancel.mutate({ id, version: proposal.version });
                          }}
                        >
                          Cancelar borrador
                        </Button>
                      </>
                    )}
                  </>
                )}
              {convertible.data?.some((item) => item.id === id) && (
                <ActionLink to={`/proyectos/nuevo?propuesta=${id}`}>Convertir propuesta</ActionLink>
              )}
              {config.demo && user && canCoordinateProposal(user, proposal) && (
                <>
                  {can(user, 'evaluation:assign') &&
                    ['submitted', 'under_review'].includes(proposal.status) && (
                      <ActionLink to={`/evaluaciones/asignar?propuesta=${id}`}>
                        Asignar evaluación
                      </ActionLink>
                    )}
                  {can(user, 'evaluation:read') && proposal.status !== 'submitted' && (
                    <ActionLink to={`/evaluaciones/${id}/resultados`}>Resultados</ActionLink>
                  )}
                </>
              )}
            </div>
          </div>
          <Protocol proposal={proposal} />
          <ProposalAttachments id={id} />
        </>
      )}
    </WorkflowState>
  );
}

function ProposalAttachments({ id }: { id: string }) {
  const { user } = useAuth();
  const enabled = config.demo && Boolean(user && can(user, 'document:read'));
  const attachments = useDemoQuery(
    ['proposal-documents', id],
    () => demoDocuments.list().filter((item) => item.proposalId === id),
    enabled,
  );
  if (!enabled) return null;
  return (
    <section className="panel mt-5 space-y-3 p-6">
      <h2 className="section-title">Documentos adjuntos</h2>
      <WorkflowState query={attachments}>
        {attachments.data?.length ? (
          <ul className="space-y-2">
            {attachments.data.map((item) => (
              <li key={item.id}>
                <ActionLink to={`/documentos/${item.id}`}>{item.name}</ActionLink>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted">Esta propuesta todavía no tiene adjuntos.</p>
        )}
      </WorkflowState>
    </section>
  );
}

export function ProposalHistoryPage() {
  const { id = '' } = useParams();
  const query = useProposal(id);
  return (
    <WorkflowState query={query}>
      {query.data && (
        <>
          <PageHeading
            eyebrow="Propuestas"
            title="Historial de la propuesta"
            description={query.data.title}
            action={<ActionLink to={`/propuestas/${id}`}>Volver a la propuesta</ActionLink>}
          />
          <WorkflowList empty="El historial no contiene movimientos.">
            {query.data.history
              .slice()
              .reverse()
              .map((item, index) => (
                <article className="p-5" key={index}>
                  <p className="font-medium">{proposalStatusLabels[item.status]}</p>
                  <p className="text-sm text-muted">{item.note}</p>
                  <time className="text-xs text-muted" dateTime={item.at}>
                    {new Date(item.at).toLocaleString('es-MX')}
                  </time>
                </article>
              ))}
          </WorkflowList>
        </>
      )}
    </WorkflowState>
  );
}

export function ProposalReviewPage() {
  const { id = '' } = useParams();
  const query = useProposal(id);
  const { user } = useAuth();
  const navigate = useNavigate();
  const submit = useWorkflowMutation(proposalsApi.submit);
  const proposal = query.data;
  const editable = Boolean(user && proposal && canEditProposal(user, proposal));
  const validation = proposal && proposalInputSchema.safeParse(proposal.details);
  return (
    <WorkflowState query={query}>
      {proposal && (
        <>
          <PageHeading
            eyebrow="Propuestas · Envío"
            title="Revisar propuesta"
            description={`${proposal.title || 'Propuesta sin título'} · ${proposal.details.area}`}
            action={<ActionLink to={`/propuestas/${id}`}>Volver a la propuesta</ActionLink>}
          />
          <Protocol proposal={proposal} />
          <ProposalAttachments id={id} />
          <div className="mt-5 space-y-4">
            {!editable && (
              <p role="status" className="text-sm text-muted">
                La propuesta ya no admite envíos.
              </p>
            )}
            {editable && validation && !validation.success && (
              <div role="alert" className="panel space-y-2 p-4">
                <p className="field-error">Completa los campos obligatorios antes de enviar.</p>
                <ul className="list-disc pl-5 text-sm">
                  {validation.error.issues.map((item, index) => (
                    <li key={index}>{item.message}</li>
                  ))}
                </ul>
              </div>
            )}
            <Feedback error={submit.error} />
            {editable && (
              <div className="flex flex-wrap gap-2">
                <ActionLink to={`/propuestas/${id}/editar`}>Editar borrador</ActionLink>
                <Button
                  disabled={!validation?.success || submit.isPending}
                  onClick={async () => {
                    const result = await submit
                      .mutateAsync({ id, version: proposal.version })
                      .catch(() => null);
                    if (result) void navigate(`/propuestas/${id}`, { state: { submitted: true } });
                  }}
                >
                  {submit.isPending ? 'Enviando…' : 'Confirmar y enviar'}
                </Button>
              </div>
            )}
          </div>
        </>
      )}
    </WorkflowState>
  );
}
