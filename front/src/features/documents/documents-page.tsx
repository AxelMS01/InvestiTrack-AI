import { useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ApiError } from '../../api/http-client';
import { useDemoQuery, useWorkflowMutation } from '../../api/demo.queries';
import { useProjects } from '../../api/projects.queries';
import { Button } from '../../components/button';
import { Input } from '../../components/input';
import { PageHeading } from '../../components/ui';
import { demoActivities } from '../../demo/activities';
import { demoProposals } from '../../demo/proposals';
import { demoDocuments, fileAccept, fileType } from '../../demo/documents';
import { downloadBlob } from '../../lib/download';
import type { Document } from '../../types/document';
import { useAuth } from '../auth/auth-context';
import { can } from '../auth/model';
import { canManageProject } from '../auth/access-policy';
import { ActionLink, Feedback, Field, WorkflowList, WorkflowState } from '../shared/workflow-ui';

const labels: Record<Document['category'], string> = {
  protocol: 'Protocolo',
  evidence: 'Evidencia',
  report: 'Informe final',
  publication: 'Publicación',
  other: 'Documento compartido',
};

export function DocumentsPage({ evidence = false }: { evidence?: boolean }) {
  const [params] = useSearchParams();
  const [search, setSearch] = useState('');
  const { user } = useAuth();
  const project = params.get('proyecto');
  const query = useDemoQuery(['documents'], demoDocuments.list);
  const entries = (query.data ?? []).filter(
    (item) =>
      (!evidence || item.category === 'evidence') &&
      (!project || item.projectId === project) &&
      item.name.toLowerCase().includes(search.toLowerCase()),
  );
  const target = new URLSearchParams();
  if (project) target.set('proyecto', project);
  if (evidence) target.set('categoria', 'evidence');
  return (
    <>
      <PageHeading
        eyebrow="Archivos de investigación"
        title={evidence ? 'Evidencias' : 'Repositorio de documentos'}
        description="Consulta los archivos compartidos en tu ámbito de trabajo."
        action={
          user &&
          can(user, 'document:upload') && (
            <ActionLink to={`/documentos/subir?${target}`} primary>
              Subir archivo
            </ActionLink>
          )
        }
      />
      {project && (
        <div className="mb-4">
          <ActionLink to={`/proyectos/${project}`}>Volver al proyecto</ActionLink>
        </div>
      )}
      <div className="mb-4 max-w-md">
        <Field label="Buscar por nombre">
          <Input value={search} onChange={(event) => setSearch(event.target.value)} />
        </Field>
      </div>
      <WorkflowState query={query}>
        <WorkflowList
          empty={
            evidence
              ? 'Todavía no hay evidencias disponibles.'
              : 'Todavía no hay documentos disponibles.'
          }
        >
          {entries.map((item) => (
            <article
              className="flex flex-wrap items-center justify-between gap-3 p-5"
              key={item.id}
            >
              <div className="min-w-0">
                <h2 className="font-medium break-all">{item.name}</h2>
                <p className="text-sm text-muted">
                  {labels[item.category]} · v{item.version}
                </p>
              </div>
              <ActionLink to={`/documentos/${item.id}`}>Ver archivo</ActionLink>
            </article>
          ))}
        </WorkflowList>
      </WorkflowState>
    </>
  );
}

export function DocumentUploadPage() {
  const { user } = useAuth();
  const [params] = useSearchParams();
  const previousId = params.get('version') ?? '';
  const proposalId = params.get('propuesta') ?? '';
  const activityId = params.get('actividad') ?? '';
  const previous = useDemoQuery(
    ['document', previousId],
    () => demoDocuments.detail(previousId),
    Boolean(previousId),
  );
  const proposal = useDemoQuery(
    ['proposal-for-file', proposalId],
    () => demoProposals.detail(proposalId),
    Boolean(proposalId),
  );
  const activity = useDemoQuery(
    ['activity', activityId],
    () => demoActivities.detail(activityId),
    Boolean(activityId),
  );
  const projectList = useProjects(
    Boolean(!previousId && !proposalId && !activityId && user && can(user, 'project:read')),
  );
  const activityList = useDemoQuery(
    ['activities'],
    demoActivities.list,
    Boolean(!previousId && !proposalId && !activityId && user && can(user, 'activity:read')),
  );
  const contextQuery = previousId ? previous : proposalId ? proposal : activityId ? activity : null;
  const back = previousId
    ? `/documentos/${previousId}`
    : proposalId
      ? `/propuestas/${proposalId}`
      : activityId
        ? `/actividades/${activityId}`
        : '/documentos';
  return (
    <>
      <PageHeading
        eyebrow="Archivos de investigación"
        title={previousId ? 'Subir nueva versión' : 'Subir archivo'}
        description="Selecciona el contexto de la entrega y un archivo de hasta 10 MB."
        action={<ActionLink to={back}>Volver</ActionLink>}
      />
      {user &&
        (contextQuery ? (
          <WorkflowState query={contextQuery}>
            <UploadForm
              key={params.toString()}
              defaults={{
                projectId: previous.data?.projectId ?? activity.data?.project.id,
                proposalId: previous.data?.proposalId ?? proposal.data?.id,
                activityId: previous.data?.activityId ?? activity.data?.activity.id,
                category: previous.data?.category ?? (proposalId ? 'protocol' : 'evidence'),
                previousId: previous.data?.id,
              }}
              contextName={
                previous.data?.name ??
                proposal.data?.title ??
                activity.data?.activity.title ??
                'Propuesta sin título'
              }
            />
          </WorkflowState>
        ) : (
          <WorkflowState query={projectList}>
            <WorkflowState query={activityList}>
              <UploadForm
                key={params.toString()}
                projects={(projectList.data ?? []).filter((item) => canManageProject(user, item))}
                activities={(activityList.data ?? []).filter(
                  (item) =>
                    item.status !== 'cancelled' &&
                    projectList.data?.some(
                      (project) =>
                        project.id === item.projectId &&
                        project.status !== 'completed' &&
                        (item.assigneeId === user.id || canManageProject(user, project)),
                    ),
                )}
                defaults={{
                  projectId: params.get('proyecto') ?? undefined,
                  category:
                    params.get('categoria') === 'report'
                      ? 'report'
                      : params.get('categoria') === 'evidence'
                        ? 'evidence'
                        : 'other',
                }}
              />
            </WorkflowState>
          </WorkflowState>
        ))}
    </>
  );
}

type UploadContext = Parameters<typeof demoDocuments.upload>[0];
function UploadForm({
  defaults,
  contextName,
  projects = [],
  activities = [],
}: {
  defaults: UploadContext;
  contextName?: string;
  projects?: { id: string; title: string }[];
  activities?: { id: string; title: string; projectId: string }[];
}) {
  const navigate = useNavigate();
  const [category, setCategory] = useState(
    !contextName && !projects.length && activities.length ? 'evidence' : defaults.category,
  );
  const [file, setFile] = useState<File>();
  const [error, setError] = useState<Error>();
  const [stage, setStage] = useState('');
  const mutation = useWorkflowMutation(
    ({ context, file }: { context: UploadContext; file: File }) =>
      demoDocuments.upload(context, file, () => setStage('Procesando y verificando archivo…')),
  );
  return (
    <form
      className="panel max-w-3xl space-y-4 p-6"
      onSubmit={async (event) => {
        event.preventDefault();
        if (!file) return;
        const data = new FormData(event.currentTarget);
        const assignee = activities.find((item) => item.id === data.get('activity'));
        const projectId = String(data.get('project') ?? '');
        const context: UploadContext = contextName
          ? defaults
          : {
              category,
              projectId: assignee?.projectId ?? (projectId || undefined),
              activityId: assignee?.id,
            };
        setError(undefined);
        setStage('Subiendo…');
        try {
          const result = await mutation.mutateAsync({ context, file });
          setStage('Disponible');
          void navigate(`/documentos/${result.id}`, { state: { uploaded: true } });
        } catch (cause) {
          setStage(
            cause instanceof ApiError && [413, 415, 422].includes(cause.status)
              ? 'Rechazado'
              : 'Error de carga',
          );
        }
      }}
    >
      {contextName ? (
        <p className="text-sm">
          {contextName} · {labels[category]}
        </p>
      ) : (
        <>
          <Field label="Tipo de archivo">
            <select
              className="input"
              value={category}
              onChange={(event) => {
                const next = event.target.value;
                if (next === 'report' || next === 'evidence' || next === 'other') {
                  setCategory(next);
                  setFile(undefined);
                  setStage('');
                  setError(undefined);
                  mutation.reset();
                }
              }}
            >
              <option value="other" disabled={!projects.length}>
                Documento compartido
              </option>
              <option value="report" disabled={!projects.length}>
                Informe final
              </option>
              <option value="evidence" disabled={!activities.length}>
                Evidencia
              </option>
            </select>
          </Field>
          {category === 'evidence' ? (
            <Field label="Actividad">
              <select className="input" name="activity" required defaultValue="">
                <option value="">Selecciona una actividad</option>
                {activities.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.title}
                  </option>
                ))}
              </select>
            </Field>
          ) : (
            <Field label="Proyecto">
              <select
                className="input"
                name="project"
                required
                defaultValue={defaults.projectId ?? ''}
              >
                <option value="">Selecciona un proyecto</option>
                {projects.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.title}
                  </option>
                ))}
              </select>
            </Field>
          )}
        </>
      )}
      <Field label="Archivo">
        <Input
          key={category}
          type="file"
          accept={category === 'report' ? '.pdf' : fileAccept}
          disabled={mutation.isPending}
          required
          onChange={(event) => {
            setError(undefined);
            setStage('');
            mutation.reset();
            const selected = event.target.files?.[0];
            if (!selected) {
              setFile(undefined);
              return;
            }
            try {
              fileType(selected, category);
              setFile(selected);
              setStage('Archivo seleccionado');
            } catch (cause) {
              setFile(undefined);
              setStage('Rechazado');
              setError(cause instanceof Error ? cause : new Error('No pudimos leer el archivo.'));
            }
          }}
        />
      </Field>
      {stage && (
        <p role="status" className="text-sm text-muted">
          {stage}
          {file && ` · ${file.name}`}
        </p>
      )}
      <Feedback error={error ?? mutation.error} />
      <Button type="submit" disabled={!file || mutation.isPending}>
        Subir archivo
      </Button>
    </form>
  );
}

export function DocumentDetailPage({ versions = false }: { versions?: boolean }) {
  const { id = '' } = useParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const query = useDemoQuery(['document', id], () => demoDocuments.detail(id));
  const history = useDemoQuery(
    ['document-versions', id],
    () => demoDocuments.versions(id),
    Boolean(query.data),
  );
  const download = useWorkflowMutation(demoDocuments.download);
  const remove = useWorkflowMutation(demoDocuments.remove);
  const allowed = useDemoQuery(
    ['document-allowed', id],
    () => demoDocuments.permissions(id),
    Boolean(query.data),
  );
  const item = query.data;
  return (
    <WorkflowState query={query}>
      {item && (
        <>
          <PageHeading
            eyebrow={labels[item.category]}
            title={versions ? 'Historial de versiones' : item.name}
            description={versions ? item.name : `Disponible · versión ${item.version}`}
            action={
              <ActionLink
                to={
                  versions
                    ? `/documentos/${id}`
                    : item.proposalId
                      ? `/propuestas/${item.proposalId}`
                      : item.activityId
                        ? `/actividades/${item.activityId}`
                        : item.category === 'report'
                          ? `/reportes/proyectos/${item.projectId}/informe-final`
                          : `/documentos?proyecto=${item.projectId ?? ''}`
                }
              >
                Volver
              </ActionLink>
            }
          />
          {versions ? (
            <WorkflowState query={history}>
              <WorkflowList empty="No hay versiones disponibles.">
                {(history.data ?? []).map((entry) => (
                  <article
                    className="flex flex-wrap items-center justify-between gap-3 p-5"
                    key={entry.id}
                  >
                    <p>
                      Versión {entry.version} · {entry.name}
                    </p>
                    <ActionLink to={`/documentos/${entry.id}`}>Ver versión</ActionLink>
                  </article>
                ))}
              </WorkflowList>
            </WorkflowState>
          ) : (
            <section className="panel space-y-4 p-6">
              <dl className="grid gap-4 text-sm sm:grid-cols-3">
                <div>
                  <dt className="text-muted">Formato</dt>
                  <dd className="break-all">{item.mimeType}</dd>
                </div>
                <div>
                  <dt className="text-muted">Tamaño</dt>
                  <dd>{Math.ceil(item.sizeBytes / 1024)} KB</dd>
                </div>
                <div>
                  <dt className="text-muted">Fecha de carga</dt>
                  <dd>{new Date(item.uploadedAt).toLocaleString('es-MX')}</dd>
                </div>
              </dl>
              <div className="flex flex-wrap gap-2">
                {user && can(user, 'document:download') && (
                  <Button
                    disabled={download.isPending}
                    onClick={() =>
                      download.mutate(id, {
                        onSuccess: ({ blob, name }) => downloadBlob(blob, name),
                      })
                    }
                  >
                    Descargar archivo
                  </Button>
                )}
                <ActionLink to={`/documentos/${id}/versiones`}>Historial de versiones</ActionLink>
                {allowed.data?.upload && (
                  <ActionLink to={`/documentos/subir?version=${id}`}>
                    Subir nueva versión
                  </ActionLink>
                )}
                {allowed.data?.remove && (
                  <Button
                    variant="secondary"
                    disabled={remove.isPending}
                    onClick={() => {
                      if (window.confirm('¿Eliminar esta versión del archivo?'))
                        remove.mutate(id, { onSuccess: () => void navigate('/documentos') });
                    }}
                  >
                    Eliminar archivo
                  </Button>
                )}
              </div>
              <Feedback error={download.error ?? remove.error} />
            </section>
          )}
        </>
      )}
    </WorkflowState>
  );
}
