import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  institutionalProposalStatus as status,
  workflowApi as api,
  type EvaluationInput,
  type InstitutionalEvaluation,
  type InstitutionalProposal,
} from '../../api/institutional-workflow.api';
import { AsyncState, EmptyState } from '../../components/ui';
import { userRoles } from '../../types/user';
import { useAuth } from '../auth/auth-context';

const proposalKey = (id: string) => ['institutional', 'proposal', id];
const evaluationsKey = (id: string) => ['institutional', 'evaluations', id];
const assignmentsKey = (id: string) => ['institutional', 'assignments', id];
const path = (id: string) => '/propuestas/' + id;

function ErrorMessage({ error }: { error?: Error | null }) {
  return (
    error && (
      <p role="alert" className="field-error">
        {error.message}
      </p>
    )
  );
}

function useAction<T>(write: (values: T) => Promise<unknown>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: write,
    onSettled: () => client.invalidateQueries({ queryKey: ['institutional'] }),
  });
}

export function InstitutionalProposalsPage({ evaluations = false }: { evaluations?: boolean }) {
  const query = useQuery({
    queryKey: ['institutional', 'proposals'],
    queryFn: ({ signal }) => api.proposals(signal),
  });
  const { user } = useAuth();
  const proposals = query.data?.filter(
    (item) => !evaluations || ['SUBMITTED', 'UNDER_REVIEW', 'APPROVED'].includes(item.status),
  );
  return (
    <section className="space-y-5">
      <h1 className="text-2xl font-semibold">{evaluations ? 'Evaluaciones' : 'Propuestas'}</h1>
      {!evaluations && user && userRoles(user).includes('researcher') && (
        <Link className="btn-primary" to="/propuestas/nueva">
          Crear propuesta
        </Link>
      )}
      {(query.isPending || query.error) && (
        <AsyncState
          loading={query.isPending}
          error={query.error ?? undefined}
          retry={() => void query.refetch()}
        />
      )}
      {proposals?.length === 0 && (
        <EmptyState
          title="Sin propuestas disponibles"
          description="Las propuestas a las que tienes acceso aparecerán aquí."
        />
      )}
      <ul className="space-y-3">
        {proposals?.map((proposal) => (
          <li key={proposal.id} className="rounded-xl border border-line p-4">
            <Link className="font-semibold underline" to={path(proposal.id)}>
              {proposal.title}
            </Link>
            <p className="text-sm text-muted">{status[proposal.status]}</p>
          </li>
        ))}
      </ul>
    </section>
  );
}

const fields = [
  ['title', 'Título'],
  ['abstract', 'Resumen'],
  ['researchProblem', 'Problema de investigación'],
  ['objective', 'Objetivo general'],
  ['methodology', 'Metodología'],
  ['expectedResults', 'Resultados esperados'],
] as const;

export function InstitutionalProposalForm({ editing = false }: { editing?: boolean }) {
  const id = useParams().id ?? '';
  const navigate = useNavigate();
  const areas = useQuery({
    queryKey: ['institutional', 'areas'],
    queryFn: ({ signal }) => api.areas(signal),
  });
  const current = useQuery({
    queryKey: proposalKey(id),
    queryFn: ({ signal }) => api.proposal(id, signal),
    enabled: editing,
  });
  const save = useAction(async (form: FormData) => {
    const value = (name: string) => String(form.get(name) ?? '').trim();
    const existing = current.data;
    const saved = await api.saveProposal(
      {
        title: value('title'),
        abstract: value('abstract'),
        researchProblem: value('researchProblem'),
        objectives: [
          { type: 'GENERAL', description: value('objective') },
          ...(existing?.objectives.filter((item) => item.type === 'SPECIFIC') ?? []),
        ],
        methodology: value('methodology'),
        expectedResults: value('expectedResults'),
        researchAreaCode: value('researchAreaCode'),
        estimatedDurationMonths: Number(value('duration')),
      },
      existing,
    );
    await navigate(path(saved.id));
  });
  const item = current.data;
  const error = areas.error ?? (editing ? current.error : null);
  if (areas.isPending || (editing && current.isPending) || error) {
    return (
      <AsyncState
        loading={!error}
        error={error ?? undefined}
        retry={() => {
          void areas.refetch();
          if (editing) void current.refetch();
        }}
      />
    );
  }
  if (areas.data?.length === 0)
    return (
      <EmptyState
        title="No hay áreas de investigación activas"
        description="Solicita a la administración que habilite el catálogo."
      />
    );
  if (editing && (!item || !['DRAFT', 'CHANGES_REQUESTED'].includes(item.status))) {
    return (
      <EmptyState
        title="La propuesta no admite cambios en su estado actual"
        description="Puedes consultar la información en el detalle de la propuesta."
      />
    );
  }
  return (
    <section className="max-w-3xl space-y-5">
      <h1 className="text-2xl font-semibold">{editing ? 'Editar propuesta' : 'Crear propuesta'}</h1>
      <form
        className="space-y-4"
        onSubmit={(event) => {
          event.preventDefault();
          save.mutate(new FormData(event.currentTarget));
        }}
      >
        {fields.map(([name, label]) => (
          <label key={name} className="field-label">
            {label}
            {name === 'title' ? (
              <input
                name={name}
                className="input"
                required
                maxLength={500}
                defaultValue={item?.title}
              />
            ) : (
              <textarea
                name={name}
                className="input"
                rows={3}
                required
                maxLength={10000}
                defaultValue={
                  (name === 'objective'
                    ? item?.objectives.find((objective) => objective.type === 'GENERAL')
                        ?.description
                    : item?.[name]) ?? ''
                }
              />
            )}
          </label>
        ))}
        <label className="field-label">
          Área de investigación
          <select
            className="input"
            name="researchAreaCode"
            defaultValue={item?.researchAreaCode ?? ''}
            required
          >
            <option value="">Selecciona un área</option>
            {areas.data?.map((area) => (
              <option key={area.code} value={area.code}>
                {area.name}
              </option>
            ))}
          </select>
        </label>
        <label className="field-label">
          Duración estimada en meses
          <input
            className="input"
            type="number"
            name="duration"
            min={1}
            defaultValue={item?.estimatedDurationMonths ?? ''}
            required
          />
        </label>
        <ErrorMessage error={save.error ?? undefined} />
        <button className="btn-primary" disabled={save.isPending}>
          Guardar borrador
        </button>
      </form>
    </section>
  );
}

export function InstitutionalProposalPage({ history = false }: { history?: boolean }) {
  const id = useParams().id ?? '';
  const { user } = useAuth();
  const query = useQuery({
    queryKey: proposalKey(id),
    queryFn: ({ signal }) => api.proposal(id, signal),
  });
  const events = useQuery({
    queryKey: ['institutional', 'history', id],
    queryFn: ({ signal }) => api.history(id, signal),
    enabled: history,
  });
  const action = useAction((target: 'SUBMITTED' | 'CANCELLED') =>
    api.transition(query.data!, target),
  );
  const item = query.data;
  if (!item)
    return (
      <AsyncState
        loading={query.isPending}
        error={query.error ?? undefined}
        retry={() => void query.refetch()}
      />
    );
  const roles = user ? userRoles(user) : [];
  const editable = Boolean(
    user &&
    roles.includes('researcher') &&
    item.researcherIds.includes(user.id) &&
    ['DRAFT', 'CHANGES_REQUESTED'].includes(item.status),
  );
  return (
    <section className="space-y-5">
      <Link className="underline" to="/propuestas">
        Propuestas
      </Link>
      <h1 className="text-2xl font-semibold">{item.title}</h1>
      <p>{status[item.status]}</p>
      {history ? (
        <>
          <Link className="underline" to={path(id)}>
            Volver a la propuesta
          </Link>
          {(events.isPending || events.error) && (
            <AsyncState
              loading={events.isPending}
              error={events.error ?? undefined}
              retry={() => void events.refetch()}
            />
          )}
          <ul>
            {events.data?.map((entry) => (
              <li key={entry.id}>
                {new Date(entry.occurredAt).toLocaleString('es-MX')} · {entry.eventType}
              </li>
            ))}
          </ul>
        </>
      ) : (
        <>
          <p className="whitespace-pre-wrap">{item.abstract}</p>
          <Link className="underline" to={path(id) + '/historial'}>
            Historial
          </Link>
          {editable && (
            <div className="flex flex-wrap gap-3">
              <Link className="btn-secondary" to={path(id) + '/editar'}>
                Editar propuesta
              </Link>
              <button
                className="btn-primary"
                disabled={action.isPending}
                onClick={() => action.mutate('SUBMITTED')}
              >
                Enviar propuesta
              </button>
              <button
                className="btn-secondary"
                disabled={action.isPending}
                onClick={() => action.mutate('CANCELLED')}
              >
                Cancelar propuesta
              </button>
            </div>
          )}
          <ErrorMessage error={action.error ?? undefined} />
          {roles.includes('coordinator') &&
            user &&
            ['SUBMITTED', 'UNDER_REVIEW'].includes(item.status) && (
              <Coordination key={item.id} item={item} coordinatorId={user.id} />
            )}
          {roles.includes('reviewer') && user && item.status === 'UNDER_REVIEW' && (
            <Review key={item.id} item={item} userId={user.id} />
          )}
          {item.status === 'APPROVED' && (
            <p>
              {item.projectId ? (
                <Link className="underline" to={'/proyectos/' + item.projectId}>
                  Ver proyecto generado
                </Link>
              ) : (
                <Link className="underline" to="/proyectos">
                  Consultar proyectos generados
                </Link>
              )}
            </p>
          )}
        </>
      )}
    </section>
  );
}

function Coordination({
  item,
  coordinatorId,
}: {
  item: InstitutionalProposal;
  coordinatorId: string;
}) {
  const assignments = useQuery({
    queryKey: assignmentsKey(item.id),
    queryFn: ({ signal }) => api.assignments(item.id, signal),
  });
  const evaluators = useQuery({
    queryKey: ['institutional', 'evaluators'],
    queryFn: ({ signal }) => api.evaluators(signal),
    enabled: item.status === 'SUBMITTED',
  });
  const evaluations = useQuery({
    queryKey: evaluationsKey(item.id),
    queryFn: ({ signal }) => api.evaluations(item.id, signal),
    enabled: item.status === 'UNDER_REVIEW',
  });
  const assign = useAction((evaluatorId: string) => api.assign(item, evaluatorId));
  const start = useAction(() => api.transition(item, 'UNDER_REVIEW'));
  const approve = useAction((form: FormData) =>
    api.transition(item, 'APPROVED', {
      coordinatorId,
      startDate: String(form.get('start') || '') || null,
      plannedEndDate: String(form.get('end') || ''),
    }),
  );
  const active =
    assignments.data?.filter(({ status }) => !['REVOKED', 'DECLINED'].includes(status)) ?? [];
  const completed = active.length > 0 && active.every(({ status }) => status === 'COMPLETED');
  return (
    <div className="space-y-4 rounded-xl border border-line p-4">
      <h2 className="font-semibold">Evaluación de la propuesta</h2>
      {(assignments.isPending || assignments.error) && (
        <AsyncState
          loading={assignments.isPending}
          error={assignments.error ?? undefined}
          retry={() => void assignments.refetch()}
        />
      )}
      {item.status === 'SUBMITTED' && (
        <>
          {(evaluators.isPending || evaluators.error) && (
            <AsyncState
              loading={evaluators.isPending}
              error={evaluators.error ?? undefined}
              retry={() => void evaluators.refetch()}
            />
          )}
          {evaluators.data && (
            <form
              className="space-y-3"
              onSubmit={(event) => {
                event.preventDefault();
                assign.mutate(String(new FormData(event.currentTarget).get('evaluator')));
              }}
            >
              <label className="field-label">
                Evaluador
                <select className="input" name="evaluator" required defaultValue="">
                  <option value="">Selecciona un evaluador</option>
                  {evaluators.data
                    .filter(
                      (user) =>
                        !item.researcherIds.includes(user.id) &&
                        !active.some((entry) => entry.evaluatorId === user.id),
                    )
                    .map((user) => (
                      <option key={user.id} value={user.id}>
                        {user.displayName}
                      </option>
                    ))}
                </select>
              </label>
              <button className="btn-secondary" disabled={assign.isPending || start.isPending}>
                Asignar evaluador
              </button>
            </form>
          )}
          <p>Asignaciones activas: {active.length}</p>
          <button
            className="btn-primary"
            disabled={!active.length || start.isPending || assign.isPending}
            onClick={() => start.mutate()}
          >
            Iniciar evaluación
          </button>
        </>
      )}
      {item.status === 'UNDER_REVIEW' && (
        <>
          {(evaluations.isPending || evaluations.error) && (
            <AsyncState
              loading={evaluations.isPending}
              error={evaluations.error ?? undefined}
              retry={() => void evaluations.refetch()}
            />
          )}
          <ul className="space-y-2">
            {evaluations.data?.map((evaluation) => (
              <li key={evaluation.id} className="rounded-lg border border-line p-3">
                <p>{evaluationDecision[evaluation.decision ?? ''] || 'Evaluación en curso'}</p>
                {evaluation.criteria.map((criterion) => (
                  <p key={criterion.criterionCode}>
                    {criterion.criterionCode}: {criterion.score}
                  </p>
                ))}
                <p>{evaluation.observations}</p>
                <p>{evaluation.recommendations}</p>
              </li>
            ))}
          </ul>
          {completed ? (
            <form
              className="space-y-3"
              onSubmit={(event) => {
                event.preventDefault();
                approve.mutate(new FormData(event.currentTarget));
              }}
            >
              <label className="field-label">
                Inicio del proyecto (opcional)
                <input className="input" type="date" name="start" />
              </label>
              <label className="field-label">
                Fin previsto del proyecto
                <input className="input" type="date" name="end" required />
              </label>
              <button
                className="btn-primary"
                disabled={approve.isPending || Boolean(evaluations.error)}
              >
                Aprobar propuesta
              </button>
            </form>
          ) : (
            <p>La aprobación requiere que los evaluadores envíen sus dictámenes.</p>
          )}
        </>
      )}
      <ErrorMessage error={assign.error ?? start.error ?? approve.error} />
    </div>
  );
}

const evaluationDecision: Record<string, string> = {
  APPROVE: 'Recomendar aprobación',
  REQUEST_CHANGES: 'Solicitar cambios',
  REJECT: 'Recomendar rechazo',
};

function Review({ item, userId }: { item: InstitutionalProposal; userId: string }) {
  const assignments = useQuery({
    queryKey: assignmentsKey(item.id),
    queryFn: ({ signal }) => api.assignments(item.id, signal),
  });
  const criteria = useQuery({
    queryKey: ['institutional', 'criteria'],
    queryFn: ({ signal }) => api.criteria(signal),
  });
  const evaluations = useQuery({
    queryKey: evaluationsKey(item.id),
    queryFn: ({ signal }) => api.evaluations(item.id, signal),
  });
  const [draft, setDraft] = useState<InstitutionalEvaluation>();
  const current =
    evaluations.data?.find((evaluation) => evaluation.evaluatorId === userId) ?? draft;
  const save = useAction(async (form: FormData) => {
    const decision = String(form.get('decision'));
    if (decision !== 'APPROVE' && decision !== 'REQUEST_CHANGES' && decision !== 'REJECT') {
      throw new Error('Selecciona un dictamen.');
    }
    const body: EvaluationInput = {
      decision,
      observations: String(form.get('observations') ?? ''),
      recommendations: String(form.get('recommendations') ?? ''),
      criteria: (criteria.data ?? []).map(({ code }) => ({
        criterionCode: code,
        score: Number(form.get(code)),
      })),
    };
    const saved = await api.saveEvaluation(item.id, body, current);
    setDraft(saved);
    await api.submitEvaluation(item.id, saved);
  });
  const error = assignments.error ?? criteria.error ?? evaluations.error;
  if (error || criteria.isPending || evaluations.isPending || assignments.isPending) {
    return (
      <AsyncState
        loading={!error}
        error={error ?? undefined}
        retry={() => {
          void assignments.refetch();
          void criteria.refetch();
          void evaluations.refetch();
        }}
      />
    );
  }
  if (current && current.status !== 'DRAFT') return <p>Dictamen enviado.</p>;
  if (
    !assignments.data?.some(
      (entry) => entry.evaluatorId === userId && ['ASSIGNED', 'ACCEPTED'].includes(entry.status),
    )
  ) {
    return (
      <EmptyState
        title="No tienes una asignación activa para esta propuesta"
        description="La coordinación administra las asignaciones de evaluación."
      />
    );
  }
  if (!criteria.data?.length)
    return (
      <EmptyState
        title="No hay criterios de evaluación activos"
        description="Solicita a la administración que habilite la rúbrica."
      />
    );
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    save.mutate(new FormData(event.currentTarget));
  }
  return (
    <form className="space-y-4 rounded-xl border border-line p-4" onSubmit={submit}>
      <h2 className="font-semibold">Dictamen de evaluación</h2>
      {criteria.data.map((criterion) => (
        <label key={criterion.code} className="field-label">
          {criterion.name} ({criterion.minimumScore}–{criterion.maximumScore})
          <input
            className="input"
            name={criterion.code}
            type="number"
            step="0.01"
            min={criterion.minimumScore}
            max={criterion.maximumScore}
            required
            defaultValue={
              current?.criteria.find((entry) => entry.criterionCode === criterion.code)?.score
            }
          />
        </label>
      ))}
      <label className="field-label">
        Dictamen
        <select className="input" name="decision" defaultValue={current?.decision ?? ''} required>
          <option value="">Selecciona un dictamen</option>
          {Object.entries(evaluationDecision).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </label>
      <label className="field-label">
        Observaciones
        <textarea
          name="observations"
          className="input"
          rows={3}
          defaultValue={current?.observations ?? ''}
        />
      </label>
      <label className="field-label">
        Recomendaciones
        <textarea
          name="recommendations"
          className="input"
          rows={3}
          defaultValue={current?.recommendations ?? ''}
        />
      </label>
      <ErrorMessage error={save.error ?? undefined} />
      <button className="btn-primary" disabled={save.isPending}>
        Enviar evaluación
      </button>
    </form>
  );
}
