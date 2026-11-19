import { useState, type FormEvent } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useDemoQuery, useWorkflowMutation } from '../../api/demo.queries';
import { useProposals } from '../../api/proposals.queries';
import { Button } from '../../components/button';
import { Input } from '../../components/input';
import { AsyncState, PageHeading } from '../../components/ui';
import { criteria, demoEvaluations, rubricSchema, type Rubric } from '../../demo/evaluations';
import { canCoordinateProposal } from '../../demo/access';
import { dateLabel, today } from '../../lib/format';
import {
  proposalFieldSchema,
  proposalStatusLabels,
  type RegisteredProposal,
} from '../../types/proposal';
import type { Evaluation } from '../../types/evaluation';
import { useAuth } from '../auth/auth-context';
import { can } from '../auth/model';
import { ActionLink, Feedback, Field, WorkflowList, WorkflowState } from '../shared/workflow-ui';

const statusLabels = { assigned: 'Pendiente', in_progress: 'En borrador', submitted: 'Enviada' };
const recommendations = { approve: 'Aprobar', revise: 'Solicitar cambios', reject: 'Rechazar' };

export function EvaluationsPage() {
  const { user, workspace } = useAuth();
  const [params, setParams] = useSearchParams();
  const filter = params.get('estado') ?? '';
  const query = useDemoQuery(['evaluations'], demoEvaluations.list);
  const proposals = useProposals(
    Boolean(user && workspace === 'coordinator' && can(user, 'proposal:decide')),
  );
  const reviews = (query.data ?? []).filter(
    (item) =>
      (workspace !== 'reviewer' || item.reviewerId === user?.id) &&
      (!filter ||
        (filter === 'pendientes'
          ? item.status !== 'submitted' && !item.superseded
          : item.status === filter)),
  );
  const decisions =
    workspace === 'coordinator' && user
      ? (proposals.data ?? []).filter(
          (proposal) => canCoordinateProposal(user, proposal) && proposal.status === 'under_review',
        )
      : [];
  return (
    <>
      <PageHeading
        eyebrow="Evaluación científica"
        title="Evaluaciones"
        description="Consulta asignaciones, plazos y resultados."
        action={
          user &&
          workspace === 'coordinator' &&
          can(user, 'evaluation:assign') && (
            <ActionLink to="/evaluaciones/asignar" primary>
              Asignar evaluador
            </ActionLink>
          )
        }
      />
      <div className="mb-4 max-w-xs">
        <Field label="Estado">
          <select
            className="input"
            value={filter}
            onChange={(event) =>
              setParams(event.target.value ? { estado: event.target.value } : {})
            }
          >
            <option value="">Todas</option>
            <option value="pendientes">Pendientes</option>
            <option value="in_progress">En borrador</option>
            <option value="submitted">Enviadas</option>
          </select>
        </Field>
      </div>
      <WorkflowState query={query}>
        <WorkflowList empty="No hay evaluaciones en esta vista.">
          {reviews.map((item) => (
            <article
              key={item.id}
              className="flex flex-wrap items-center justify-between gap-3 p-5"
            >
              <div>
                <h2 className="font-medium">{item.proposalTitle}</h2>
                <p className="text-sm text-muted">
                  {item.superseded ? 'Asignación sustituida' : statusLabels[item.status]} ·{' '}
                  {item.reviewerName}
                  {item.dueDate && ` · vence el ${dateLabel(item.dueDate)}`}
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                <ActionLink to={`/propuestas/${item.proposalId}`}>Consultar propuesta</ActionLink>
                {item.reviewerId === user?.id &&
                item.status !== 'submitted' &&
                !item.superseded &&
                user &&
                can(user, 'evaluation:create') ? (
                  <ActionLink to={`/evaluaciones/${item.id}/rubrica`} primary>
                    Abrir rúbrica
                  </ActionLink>
                ) : (
                  <ActionLink to={`/evaluaciones/${item.id}/resultados`}>Ver resultados</ActionLink>
                )}
              </div>
            </article>
          ))}
        </WorkflowList>
      </WorkflowState>
      {decisions.length > 0 && (
        <section className="panel mt-5 space-y-4 p-5">
          <h2 className="section-title">Propuestas en evaluación</h2>
          {decisions.map((item) => (
            <div key={item.id} className="flex flex-wrap items-center justify-between gap-3">
              <p>{item.title}</p>
              <ActionLink to={`/evaluaciones/${item.id}/resultados`}>Revisar resultados</ActionLink>
            </div>
          ))}
        </section>
      )}
    </>
  );
}

export function EvaluationAssignmentPage() {
  const { user } = useAuth();
  const [params] = useSearchParams();
  const [selected, setSelected] = useState(params.get('propuesta') ?? '');
  const [search, setSearch] = useState('');
  const [reviewerId, setReviewerId] = useState('');
  const proposals = useProposals();
  const reviewers = useDemoQuery(['reviewers'], demoEvaluations.reviewers);
  const assignments = useDemoQuery(['evaluations'], demoEvaluations.list);
  const mutation = useWorkflowMutation(demoEvaluations.assign);
  const eligible = (proposals.data ?? []).filter(
    (item) =>
      user &&
      canCoordinateProposal(user, item) &&
      ['submitted', 'under_review'].includes(item.status),
  );
  const proposal = eligible.find((item) => item.id === selected);
  const assigned = (assignments.data ?? []).filter(
    (item) =>
      item.proposalId === selected && item.submissionVersion === proposal?.submissionVersion,
  );
  const candidates = (reviewers.data ?? []).filter(
    (item) =>
      item.id !== proposal?.ownerId &&
      item.id !== user?.id &&
      !assigned.some((assignment) => assignment.reviewerId === item.id) &&
      `${item.name} ${item.email}`.toLowerCase().includes(search.toLowerCase()),
  );
  function assign(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const fields = new FormData(event.currentTarget);
    mutation.mutate(
      { proposalId: selected, reviewerId, dueDate: String(fields.get('dueDate') ?? '') },
      { onSuccess: () => setReviewerId('') },
    );
  }
  return (
    <>
      <PageHeading
        eyebrow="Evaluaciones"
        title="Asignar evaluador"
        description="Selecciona una propuesta recibida y establece el plazo de revisión."
        action={<ActionLink to="/evaluaciones">Volver a evaluaciones</ActionLink>}
      />
      <WorkflowState query={proposals}>
        <WorkflowState query={reviewers}>
          <WorkflowState query={assignments}>
            {!eligible.length ? (
              <WorkflowList empty="No hay propuestas recibidas para asignar.">{[]}</WorkflowList>
            ) : (
              <form className="panel max-w-3xl space-y-4 p-6" onSubmit={assign}>
                <Field label="Propuesta">
                  <select
                    className="input"
                    required
                    value={selected}
                    onChange={(event) => {
                      setSelected(event.target.value);
                      setReviewerId('');
                    }}
                  >
                    <option value="">Selecciona una propuesta</option>
                    {eligible.map((item) => (
                      <option value={item.id} key={item.id}>
                        {item.title}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="Buscar evaluadores">
                  <Input
                    value={search}
                    onChange={(event) => {
                      setSearch(event.target.value);
                      setReviewerId('');
                    }}
                    placeholder="Nombre o correo"
                  />
                </Field>
                <Field label="Evaluador">
                  <select
                    className="input"
                    value={reviewerId}
                    onChange={(event) => setReviewerId(event.target.value)}
                    required
                  >
                    <option value="">Selecciona un evaluador</option>
                    {candidates.map((item) => (
                      <option value={item.id} key={item.id}>
                        {item.name} · {item.email}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="Fecha límite">
                  <Input type="date" name="dueDate" min={today()} required />
                </Field>
                <Button type="submit" disabled={!proposal || !reviewerId || mutation.isPending}>
                  Asignar evaluación
                </Button>
                <Feedback
                  error={mutation.error}
                  success={mutation.isSuccess && 'Evaluación asignada.'}
                />
                {proposal && (
                  <div className="border-t border-line pt-4">
                    <h2 className="section-title">Asignaciones de esta entrega</h2>
                    <p className="text-sm text-muted">
                      {assigned.length
                        ? assigned.map((item) => item.reviewerName).join(', ')
                        : 'Sin asignaciones.'}
                    </p>
                  </div>
                )}
              </form>
            )}
          </WorkflowState>
        </WorkflowState>
      </WorkflowState>
    </>
  );
}

export function EvaluationRubricPage() {
  const { id = '' } = useParams();
  const { user } = useAuth();
  const query = useDemoQuery(['evaluations'], demoEvaluations.list);
  const item = query.data?.find((entry) => entry.id === id && entry.reviewerId === user?.id);
  return (
    <WorkflowState query={query}>
      {item && item.status !== 'submitted' && !item.superseded ? (
        <RubricForm key={id} evaluation={item} title={item.proposalTitle} />
      ) : (
        <>
          <ActionLink to="/evaluaciones">Volver a evaluaciones</ActionLink>
          <AsyncState error={new Error('La rúbrica no está disponible para edición.')} />
        </>
      )}
    </WorkflowState>
  );
}

function RubricForm({ evaluation, title }: { evaluation: Evaluation; title: string }) {
  const { user } = useAuth();
  const mutation = useWorkflowMutation(demoEvaluations.save);
  const navigate = useNavigate();
  const {
    register,
    handleSubmit,
    getValues,
    formState: { errors },
  } = useForm<Rubric>({
    resolver: zodResolver(rubricSchema),
    defaultValues: {
      scores: criteria.map(
        ({ id }) =>
          evaluation.scores.find((score) => score.criterionId === id) ?? {
            criterionId: id,
            score: null,
            comment: '',
          },
      ),
      recommendation: evaluation.recommendation,
      feedback: evaluation.feedback,
    },
  });
  return (
    <>
      <PageHeading
        eyebrow="Rúbrica de evaluación"
        title={title}
        description={
          evaluation.dueDate
            ? `Entrega hasta el ${dateLabel(evaluation.dueDate)}.`
            : 'Califica el protocolo y registra tu recomendación.'
        }
        action={<ActionLink to="/evaluaciones">Volver a evaluaciones</ActionLink>}
      />
      <form
        className="panel max-w-3xl space-y-5 p-6"
        noValidate
        onSubmit={handleSubmit(async (rubric) => {
          const result = await mutation
            .mutateAsync({ id: evaluation.id, rubric, submit: true })
            .catch(() => null);
          if (result)
            void navigate(`/evaluaciones/${evaluation.id}/resultados`, {
              state: { submitted: true },
            });
        })}
      >
        <ActionLink to={`/propuestas/${evaluation.proposalId}`}>Consultar protocolo</ActionLink>
        {criteria.map((criterion, index) => (
          <fieldset className="space-y-3 rounded-lg border border-line p-4" key={criterion.id}>
            <legend className="px-2 text-sm font-medium">{criterion.label}</legend>
            <input type="hidden" {...register(`scores.${index}.criterionId`)} />
            <Field label="Puntuación de 0 a 100">
              <Input
                type="number"
                min="0"
                max="100"
                {...register(`scores.${index}.score`, {
                  setValueAs: (value: string) => (value === '' ? null : Number(value)),
                })}
              />
            </Field>
            <Field label="Observaciones del criterio">
              <textarea className="input" {...register(`scores.${index}.comment`)} />
            </Field>
          </fieldset>
        ))}
        <Field label="Recomendación del evaluador">
          <select
            className="input"
            {...register('recommendation', { setValueAs: (value: string) => value || null })}
          >
            <option value="">Elige una recomendación</option>
            {Object.entries(recommendations).map(([value, label]) => (
              <option value={value} key={value}>
                {label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Retroalimentación">
          <textarea className="input" rows={4} {...register('feedback')} />
        </Field>
        {Object.keys(errors).length > 0 && (
          <p role="alert" className="field-error">
            {errors.recommendation?.message ??
              errors.feedback?.message ??
              'Revisa las puntuaciones y observaciones.'}
          </p>
        )}
        <Feedback
          error={mutation.error}
          success={mutation.isSuccess && 'Borrador de evaluación guardado.'}
        />
        <div className="flex flex-wrap gap-2">
          <Button
            variant="secondary"
            disabled={mutation.isPending}
            onClick={() =>
              mutation.mutate({ id: evaluation.id, rubric: getValues(), submit: false })
            }
          >
            Guardar borrador
          </Button>
          {user && can(user, 'evaluation:submit') && (
            <Button type="submit" disabled={mutation.isPending}>
              Enviar evaluación
            </Button>
          )}
        </div>
      </form>
    </>
  );
}

export function EvaluationResultsPage() {
  const location = useLocation();
  const { id = '' } = useParams();
  const query = useDemoQuery(['results', id], () => demoEvaluations.results(id));
  const result = query.data;
  return (
    <WorkflowState query={query}>
      {result && (
        <>
          <PageHeading
            eyebrow="Evaluaciones"
            title="Resultados de evaluación"
            description={result.proposalTitle}
            action={<ActionLink to="/evaluaciones">Volver a evaluaciones</ActionLink>}
          />
          {location.state?.submitted && <Feedback success="Evaluación enviada a coordinación." />}
          {result.proposalStatus && (
            <p className="mb-4 text-sm">
              Estado institucional · {proposalStatusLabels[result.proposalStatus]}
            </p>
          )}
          <WorkflowList empty="Aún no se han enviado evaluaciones.">
            {result.evaluations.map((item) => (
              <article key={item.id} className="space-y-3 p-5">
                <h2 className="section-title">{item.reviewerName}</h2>
                <p className="text-sm font-medium">
                  Recomendación académica ·{' '}
                  {item.recommendation ? recommendations[item.recommendation] : 'Sin recomendación'}
                </p>
                <ul className="space-y-2">
                  {item.scores.map((score) => (
                    <li key={score.criterionId} className="text-sm">
                      {criteria.find(({ id: key }) => key === score.criterionId)?.label ??
                        score.criterionId}{' '}
                      · {score.score}/100
                      {score.comment && <p className="text-muted">{score.comment}</p>}
                    </li>
                  ))}
                </ul>
                <p className="whitespace-pre-wrap text-sm text-muted">{item.feedback}</p>
              </article>
            ))}
          </WorkflowList>
          {result.canDecide && (
            <div className="mt-5">
              <ActionLink to={`/evaluaciones/${result.proposalId}/decision`} primary>
                Registrar decisión institucional
              </ActionLink>
            </div>
          )}
        </>
      )}
    </WorkflowState>
  );
}

const fieldLabels: Record<(typeof proposalFieldSchema.options)[number], string> = {
  title: 'Título',
  abstract: 'Resumen',
  area: 'Área',
  objective: 'Objetivo',
  methodology: 'Metodología',
  startDate: 'Fecha de inicio',
  endDate: 'Fecha de cierre',
  team: 'Equipo',
  budgetItems: 'Partidas del presupuesto',
  requestedFunding: 'Financiamiento',
  involvesParticipants: 'Participantes',
  ethicsApproval: 'Aprobación ética',
  declaration: 'Declaración',
};

export function EvaluationDecisionPage() {
  const { id = '' } = useParams();
  const query = useDemoQuery(['results', id], () => demoEvaluations.results(id));
  return (
    <WorkflowState query={query}>
      {query.data?.coordinatedProposal ? (
        <DecisionForm proposal={query.data.coordinatedProposal} ready={query.data.canDecide} />
      ) : (
        <AsyncState error={new Error('La decisión está reservada a la coordinación autorizada.')} />
      )}
    </WorkflowState>
  );
}

function DecisionForm({ proposal, ready }: { proposal: RegisteredProposal; ready: boolean }) {
  const [status, setStatus] = useState<'approved' | 'rejected' | 'changes_requested'>('approved');
  const mutation = useWorkflowMutation(demoEvaluations.decide);
  return (
    <>
      <PageHeading
        eyebrow="Coordinación"
        title="Decisión institucional"
        description={proposal.title}
        action={
          <ActionLink to={`/evaluaciones/${proposal.id}/resultados`}>
            Volver a resultados
          </ActionLink>
        }
      />
      {ready ? (
        <form
          className="panel max-w-3xl space-y-4 p-6"
          onSubmit={(event) => {
            event.preventDefault();
            const fields = new FormData(event.currentTarget);
            const allowedFields = fields
              .getAll('field')
              .map((item) => proposalFieldSchema.parse(item));
            mutation.mutate({
              id: proposal.id,
              version: proposal.version,
              note: String(fields.get('note') ?? ''),
              status,
              allowedFields,
            });
          }}
        >
          <Field label="Decisión">
            <select
              className="input"
              value={status}
              onChange={(event) => {
                const value = event.target.value;
                if (value === 'approved' || value === 'rejected' || value === 'changes_requested')
                  setStatus(value);
              }}
            >
              <option value="approved">Aprobar</option>
              <option value="rejected">Rechazar</option>
              <option value="changes_requested">Solicitar modificaciones</option>
            </select>
          </Field>
          <Field label="Motivo de la decisión">
            <textarea className="input" name="note" required minLength={10} rows={4} />
          </Field>
          {status === 'changes_requested' && (
            <fieldset className="grid gap-2 sm:grid-cols-2">
              <legend className="field-label">Campos autorizados para corregir</legend>
              {proposalFieldSchema.options.map((field) => (
                <label key={field} className="flex items-center gap-2 text-sm">
                  <input type="checkbox" name="field" value={field} />
                  {fieldLabels[field]}
                </label>
              ))}
            </fieldset>
          )}
          <label className="flex gap-2 text-sm">
            <input type="checkbox" required />
            Confirmo que revisé los resultados y autorizo registrar esta decisión.
          </label>
          <Feedback error={mutation.error} />
          <Button type="submit" disabled={mutation.isPending}>
            Registrar decisión
          </Button>
        </form>
      ) : (
        <Feedback
          success={
            ['approved', 'rejected', 'changes_requested'].includes(proposal.status)
              ? `Decisión registrada · ${proposalStatusLabels[proposal.status]}.`
              : 'Completa las evaluaciones de la entrega antes de registrar una decisión.'
          }
        />
      )}
    </>
  );
}
