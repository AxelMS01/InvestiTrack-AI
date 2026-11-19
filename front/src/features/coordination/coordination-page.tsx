import { useState, type FormEvent } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useDemoQuery, useWorkflowMutation } from '../../api/demo.queries';
import { useProjects } from '../../api/projects.queries';
import { Button } from '../../components/button';
import { Input } from '../../components/input';
import { PageHeading } from '../../components/ui';
import { demoCoordination } from '../../demo/coordination';
import { requestStatusLabels } from '../../types/academic';
import { canCoordinateProject, canManageProject, projectUnit } from '../auth/access-policy';
import { useAuth } from '../auth/auth-context';
import { ActionLink, Feedback, Field, WorkflowList, WorkflowState } from '../shared/workflow-ui';

export function CoordinationPage() {
  const { unitId, switchUnit } = useAuth();
  const units = useDemoQuery(['academic-units'], demoCoordination.units);
  const selected = units.data?.find((unit) => unit.id === unitId) ?? units.data?.[0];
  const directory = useDemoQuery(
    ['unit-directory', selected?.id],
    () => demoCoordination.directory(selected?.id ?? ''),
    Boolean(selected),
  );
  return (
    <>
      <PageHeading
        eyebrow="Coordinación académica"
        title="Mi coordinación"
        description="Selecciona tu unidad y consulta su directorio y trabajo pendiente."
      />
      <WorkflowState query={units}>
        {selected ? (
          <>
            <div className="panel mb-5 p-5">
              <Field label="Unidad académica">
                <select
                  className="input"
                  value={selected.id}
                  onChange={(event) => switchUnit?.(event.target.value)}
                >
                  {units.data?.map((unit) => (
                    <option key={unit.id} value={unit.id}>
                      {unit.name}
                    </option>
                  ))}
                </select>
              </Field>
            </div>
            <WorkflowState query={directory}>
              {directory.data && (
                <div className="mb-5 grid gap-4 sm:grid-cols-2">
                  <section className="panel space-y-3 p-5">
                    <h2 className="section-title">Proyectos supervisados</h2>
                    <p className="text-2xl font-semibold">{directory.data.projectCount}</p>
                    <ActionLink to={`/proyectos?unidad=${selected.id}`}>
                      Consultar proyectos
                    </ActionLink>
                  </section>
                  <section className="panel space-y-3 p-5">
                    <h2 className="section-title">Solicitudes pendientes</h2>
                    <p className="text-2xl font-semibold">{directory.data.pendingCount}</p>
                    <ActionLink
                      to={`/solicitudes-estudiantes?unidad=${selected.id}&estado=PENDING`}
                    >
                      Consultar solicitudes
                    </ActionLink>
                  </section>
                </div>
              )}
              <div className="grid gap-4 md:grid-cols-2">
                {[
                  { label: 'Investigadores de la unidad', items: directory.data?.investigators },
                  {
                    label: 'Evaluadores disponibles de la unidad',
                    items: directory.data?.evaluators,
                  },
                ].map(({ label, items }) => (
                  <section key={label}>
                    <h2 className="section-title mb-3">{label}</h2>
                    <WorkflowList empty="Sin perfiles activos para esta unidad.">
                      {(items ?? []).map((item) => (
                        <article key={item.id} className="break-words p-4">
                          <p className="font-medium">{item.name}</p>
                          <p className="text-sm text-muted">{item.email}</p>
                        </article>
                      ))}
                    </WorkflowList>
                  </section>
                ))}
              </div>
            </WorkflowState>
          </>
        ) : (
          <WorkflowList empty="Tu cuenta no tiene unidades de coordinación activas.">
            {[]}
          </WorkflowList>
        )}
      </WorkflowState>
    </>
  );
}

export function ProjectEvaluatorPage() {
  const { id } = useParams();
  const { user, unitId } = useAuth();
  const projects = useProjects(!id);
  if (id) return <ProjectEvaluatorForm key={id} id={id} />;
  return (
    <>
      <PageHeading
        eyebrow="Coordinación académica"
        title="Asignar evaluadores de proyecto"
        description="Elige un proyecto abierto dentro de tu coordinación."
      />
      <WorkflowState query={projects}>
        <WorkflowList empty="No hay proyectos abiertos para asignar.">
          {(projects.data ?? [])
            .filter(
              (item) =>
                user &&
                canCoordinateProject(user, item) &&
                item.status !== 'completed' &&
                (!unitId || unitId === projectUnit(item)),
            )
            .map((item) => (
              <article
                key={item.id}
                className="flex flex-wrap items-center justify-between gap-3 p-5"
              >
                <h2 className="font-medium">{item.title}</h2>
                <ActionLink to={`/proyectos/${item.id}/evaluadores`}>Asignar evaluador</ActionLink>
              </article>
            ))}
        </WorkflowList>
      </WorkflowState>
    </>
  );
}

function ProjectEvaluatorForm({ id }: { id: string }) {
  const query = useDemoQuery(['evaluator-context', id], () =>
    demoCoordination.assignmentContext(id),
  );
  const mutation = useWorkflowMutation(demoCoordination.assignEvaluator);
  const lead = useWorkflowMutation(demoCoordination.changeLead);
  const [search, setSearch] = useState('');
  const [candidate, setCandidate] = useState('');
  const [reason, setReason] = useState('');
  const [review, setReview] = useState(false);
  const selected = query.data?.candidates.find((item) => item.id === candidate);
  return (
    <WorkflowState query={query}>
      {query.data && (
        <>
          <PageHeading
            eyebrow="Seguimiento del proyecto"
            title="Asignar evaluador"
            description={query.data.project.title}
            action={<ActionLink to={`/proyectos/${id}`}>Volver al proyecto</ActionLink>}
          />
          <section className="panel mb-5 space-y-3 p-5">
            <h2 className="section-title">Asignaciones del proyecto</h2>
            {query.data.assigned.length ? (
              <ul className="space-y-2 text-sm">
                {query.data.assigned.map((item) => (
                  <li key={item.id}>
                    {item.name} ·{' '}
                    {item.status === 'ACTIVE'
                      ? 'Vigente'
                      : item.status === 'COMPLETED'
                        ? 'Completada'
                        : 'Revocada'}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-sm text-muted">Aún no hay evaluadores asignados.</p>
            )}
          </section>
          <form
            className="panel max-w-3xl space-y-4 p-5"
            onSubmit={async (event) => {
              event.preventDefault();
              if (!selected) return;
              if (!review) {
                setReview(true);
                return;
              }
              const saved = await mutation
                .mutateAsync({ projectId: id, evaluatorUserId: selected.id, reason })
                .catch(() => null);
              if (saved) {
                setReview(false);
                setCandidate('');
                setReason('');
              }
            }}
          >
            {review ? (
              <>
                <p className="font-medium">
                  Confirma la asignación de {selected?.name} a este proyecto.
                </p>
                {reason && <p className="whitespace-pre-wrap text-sm text-muted">{reason}</p>}
                <Button variant="secondary" onClick={() => setReview(false)}>
                  Volver a editar
                </Button>
              </>
            ) : (
              <>
                <Field label="Buscar evaluador">
                  <Input value={search} onChange={(event) => setSearch(event.target.value)} />
                </Field>
                <Field label="Evaluador">
                  <select
                    className="input"
                    value={candidate}
                    onChange={(event) => setCandidate(event.target.value)}
                    required
                  >
                    <option value="">Selecciona un evaluador</option>
                    {query.data.candidates
                      .filter((item) =>
                        `${item.name} ${item.email}`.toLowerCase().includes(search.toLowerCase()),
                      )
                      .map((item) => (
                        <option key={item.id} value={item.id}>
                          {item.name} · {item.email}
                        </option>
                      ))}
                  </select>
                </Field>
                {!query.data.candidates.length && (
                  <p className="text-sm text-muted">No hay candidatos adicionales habilitados.</p>
                )}
                <Field label="Motivo (opcional)">
                  <textarea
                    className="input"
                    value={reason}
                    maxLength={2000}
                    onChange={(event) => setReason(event.target.value)}
                  />
                </Field>
              </>
            )}
            <Button type="submit" disabled={!selected || mutation.isPending}>
              {review ? 'Confirmar asignación' : 'Revisar asignación'}
            </Button>
            <Feedback
              error={mutation.error}
              success={mutation.isSuccess && 'Evaluador asignado al proyecto.'}
            />
          </form>
          {query.data.investigators.length > 0 && (
            <form
              className="panel mt-5 max-w-3xl space-y-4 p-5"
              onSubmit={(event) => {
                event.preventDefault();
                const userId = String(new FormData(event.currentTarget).get('researcher') ?? '');
                const previousLeadId = query.data?.project.leadId;
                if (
                  previousLeadId &&
                  userId &&
                  window.confirm('¿Cambiar la persona responsable de este proyecto?')
                )
                  lead.mutate({ projectId: id, userId, previousLeadId });
              }}
            >
              <h2 className="section-title">Investigación principal</h2>
              <p className="text-sm text-muted">Responsable actual · {query.data.project.lead}</p>
              <Field label="Nuevo responsable">
                <select className="input" name="researcher" required defaultValue="">
                  <option value="">Selecciona un investigador de la unidad</option>
                  {query.data.investigators.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.name}
                    </option>
                  ))}
                </select>
              </Field>
              <Button type="submit" disabled={lead.isPending}>
                Asignar responsable
              </Button>
              <Feedback
                error={lead.error}
                success={lead.isSuccess && 'Responsabilidad actualizada.'}
              />
            </form>
          )}
        </>
      )}
    </WorkflowState>
  );
}

export function StudentProposalPage() {
  const { id = '' } = useParams();
  const [search, setSearch] = useState('');
  const navigate = useNavigate();
  const query = useDemoQuery(['student-candidates', id], () =>
    demoCoordination.studentCandidates(id),
  );
  const mutation = useWorkflowMutation(demoCoordination.propose);
  const candidates = (query.data?.candidates ?? []).filter((item) =>
    `${item.name} ${item.email}`.toLowerCase().includes(search.toLowerCase()),
  );
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const result = await mutation
      .mutateAsync({
        projectId: id,
        studentUserId: String(data.get('student')),
        rationale: String(data.get('rationale')),
      })
      .catch(() => null);
    if (result) void navigate(`/solicitudes-estudiantes/${result.id}`);
  }
  return (
    <>
      <PageHeading
        eyebrow="Participantes del proyecto"
        title="Proponer estudiante"
        description={
          query.data?.project.title ?? 'Solicita autorización para incorporar a un estudiante.'
        }
        action={<ActionLink to={`/proyectos/${id}/colaboradores`}>Volver al equipo</ActionLink>}
      />
      <WorkflowState query={query}>
        <form className="panel max-w-3xl space-y-4 p-5" onSubmit={(event) => void submit(event)}>
          <Field label="Buscar estudiante">
            <Input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Nombre o correo"
            />
          </Field>
          {candidates.length ? (
            <>
              <Field label="Estudiante">
                <select className="input" name="student" required defaultValue="">
                  <option value="">Selecciona un estudiante</option>
                  {candidates.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.name} · {item.email}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Justificación o responsabilidad">
                <textarea className="input" name="rationale" rows={4} required maxLength={2000} />
              </Field>
              <Button type="submit" disabled={mutation.isPending}>
                Enviar solicitud
              </Button>
            </>
          ) : (
            <p className="text-sm text-muted">No hay estudiantes disponibles para esta búsqueda.</p>
          )}
          <Feedback error={mutation.error} />
        </form>
      </WorkflowState>
    </>
  );
}

export function StudentRequestsPage() {
  const [params, setParams] = useSearchParams();
  const { unitId, workspace, user } = useAuth();
  const projects = useProjects(workspace === 'researcher');
  const query = useDemoQuery(['student-requests'], demoCoordination.requests);
  const unit = params.get('unidad') ?? unitId;
  const filter = params.get('estado') ?? '';
  const projectId = params.get('proyecto');
  const items = (query.data ?? []).filter(
    (item) =>
      (!unit || item.unitId === unit) &&
      (!filter || item.status === filter) &&
      (!projectId || item.projectId === projectId),
  );
  return (
    <>
      <PageHeading
        eyebrow="Incorporación estudiantil"
        title="Solicitudes de estudiantes"
        description="Consulta solicitudes, su decisión y la membresía resultante."
      />
      <div className="mb-4 max-w-xs">
        <Field label="Estado">
          <select
            className="input"
            value={filter}
            onChange={(event) => {
              const next = new URLSearchParams(params);
              if (event.target.value) next.set('estado', event.target.value);
              else next.delete('estado');
              setParams(next);
            }}
          >
            <option value="">Todas</option>
            {Object.entries(requestStatusLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <WorkflowState query={query}>
        <WorkflowList empty="No hay solicitudes para esta vista.">
          {items.map((item) => (
            <article
              key={item.id}
              className="flex flex-wrap items-center justify-between gap-3 p-5"
            >
              <div>
                <h2 className="font-medium">{item.student}</h2>
                <p className="text-sm text-muted">
                  {item.project} · {requestStatusLabels[item.status]}
                </p>
              </div>
              <ActionLink to={`/solicitudes-estudiantes/${item.id}`}>Ver solicitud</ActionLink>
            </article>
          ))}
        </WorkflowList>
      </WorkflowState>
      {workspace === 'researcher' && user && (
        <section className="mt-5">
          <h2 className="section-title mb-3">Estudiantes y colaboradores por proyecto</h2>
          <WorkflowState query={projects}>
            <WorkflowList empty="Todavía no tienes proyectos abiertos bajo tu responsabilidad.">
              {(projects.data ?? [])
                .filter((item) => canManageProject(user, item))
                .map((item) => (
                  <article
                    className="flex flex-wrap items-center justify-between gap-3 p-5"
                    key={item.id}
                  >
                    <p>{item.title}</p>
                    <ActionLink to={`/proyectos/${item.id}/colaboradores`}>
                      Consultar equipo
                    </ActionLink>
                  </article>
                ))}
            </WorkflowList>
          </WorkflowState>
        </section>
      )}
    </>
  );
}

export function StudentRequestPage() {
  const { id = '' } = useParams();
  const query = useDemoQuery(['student-request', id], () => demoCoordination.request(id));
  const mutation = useWorkflowMutation(demoCoordination.decide);
  const item = query.data;
  return (
    <WorkflowState query={query}>
      {item && (
        <>
          <PageHeading
            eyebrow={requestStatusLabels[item.status]}
            title="Solicitud de incorporación"
            description={item.project}
            action={<ActionLink to="/solicitudes-estudiantes">Volver a solicitudes</ActionLink>}
          />
          <section className="panel max-w-3xl space-y-4 p-5">
            <p className="text-sm">
              Estudiante · <strong>{item.student}</strong>
            </p>
            <p className="text-sm">Investigador · {item.researcher}</p>
            <p className="whitespace-pre-wrap text-sm">{item.rationale}</p>
            {item.actor && (
              <p className="text-sm">
                Resolvió {item.actor} ·{' '}
                {item.decidedAt && new Date(item.decidedAt).toLocaleString('es-MX')}
              </p>
            )}
            {item.decisionReason && (
              <p className="whitespace-pre-wrap text-sm">{item.decisionReason}</p>
            )}
            {item.status === 'PENDING' && item.canDecide && (
              <form
                className="space-y-3 border-t border-line pt-4"
                onSubmit={(event) => {
                  event.preventDefault();
                  const data = new FormData(event.currentTarget);
                  const decision = data.get('decision');
                  if (
                    (decision === 'APPROVED' || decision === 'REJECTED') &&
                    window.confirm('¿Confirmar la decisión de incorporación?')
                  )
                    mutation.mutate({
                      id,
                      version: item.version,
                      decision,
                      reason: String(data.get('reason') ?? ''),
                    });
                }}
              >
                <Field label="Decisión">
                  <select className="input" name="decision" required defaultValue="">
                    <option value="">Selecciona</option>
                    <option value="APPROVED">Autorizar</option>
                    <option value="REJECTED">Rechazar</option>
                  </select>
                </Field>
                <Field label="Motivo (opcional)">
                  <textarea className="input" name="reason" maxLength={2000} />
                </Field>
                <Button type="submit" disabled={mutation.isPending}>
                  Confirmar decisión
                </Button>
              </form>
            )}
            {item.status === 'PENDING' && item.canCancel && (
              <Button
                variant="secondary"
                disabled={mutation.isPending}
                onClick={() => {
                  if (window.confirm('¿Cancelar la solicitud pendiente?'))
                    mutation.mutate({
                      id,
                      version: item.version,
                      decision: 'CANCELLED',
                      reason: '',
                    });
                }}
              >
                Cancelar solicitud
              </Button>
            )}
            <Feedback
              error={mutation.error}
              success={mutation.isSuccess && 'Solicitud actualizada.'}
            />
          </section>
        </>
      )}
    </WorkflowState>
  );
}
