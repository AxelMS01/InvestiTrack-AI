import { useState } from 'react';
import { Navigate, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ApiError } from '../../api/http-client';
import { useDemoQuery, useWorkflowMutation } from '../../api/demo.queries';
import { useProjects } from '../../api/projects.queries';
import { Button } from '../../components/button';
import { Input } from '../../components/input';
import { PageHeading } from '../../components/ui';
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

export function DocumentsPage() {
  const [params] = useSearchParams();
  const [search, setSearch] = useState('');
  const { user } = useAuth();
  const project = params.get('proyecto');
  const query = useDemoQuery(['documents'], demoDocuments.list);
  const entries = (query.data ?? []).filter(
    (item) =>
      (!project || item.projectId === project) &&
      item.name.toLowerCase().includes(search.toLowerCase()),
  );
  const target = new URLSearchParams();
  if (project) target.set('proyecto', project);
  return (
    <>
      <PageHeading
        eyebrow="Archivos de investigación"
        title="Repositorio de documentos"
        description="Consulta los archivos compartidos en tu ámbito de trabajo."
        action={
          user &&
          can(user, 'project:update') &&
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
        <WorkflowList empty="Todavía no hay documentos disponibles.">
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
  const [params] = useSearchParams();
  const activity = params.get('actividad');
  if (activity || params.get('categoria') === 'evidence') {
    const target = new URLSearchParams();
    if (activity) target.set('actividad', activity);
    if (params.has('proyecto')) target.set('proyecto', params.get('proyecto') ?? '');
    return <Navigate to={`/evidencias/nueva?${target}`} replace />;
  }
  return <DocumentUploadForm key={params.toString()} params={params} />;
}

function DocumentUploadForm({ params }: { params: URLSearchParams }) {
  const { user } = useAuth();
  const navigate = useNavigate();
  const previousId = params.get('version') ?? '';
  const proposalId = params.get('propuesta') ?? '';
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
  const projects = useProjects(
    !previousId && !proposalId && Boolean(user && can(user, 'project:update')),
  );
  const [category, setCategory] = useState<Document['category']>(
    params.get('categoria') === 'report' ? 'report' : 'other',
  );
  const [file, setFile] = useState<File>();
  const [error, setError] = useState<Error>();
  const [stage, setStage] = useState('');
  const mutation = useWorkflowMutation(
    ({ context, file }: { context: Parameters<typeof demoDocuments.upload>[0]; file: File }) =>
      demoDocuments.upload(context, file, () => setStage('Procesando y verificando archivo…')),
  );
  const actualCategory = previous.data?.category ?? (proposalId ? 'protocol' : category);
  const context = previousId ? previous : proposalId ? proposal : projects;
  const eligible = (projects.data ?? []).filter((item) => user && canManageProject(user, item));
  const back = previousId
    ? `/documentos/${previousId}`
    : proposalId
      ? `/propuestas/${proposalId}`
      : '/documentos';
  const allowed = Boolean(user && (previousId || proposalId || can(user, 'project:update')));
  return (
    <>
      <PageHeading
        eyebrow="Archivos de investigación"
        title={previousId ? 'Subir nueva versión' : 'Subir archivo'}
        description="Selecciona un archivo de hasta 10 MB."
        action={<ActionLink to={back}>Volver</ActionLink>}
      />
      {!allowed ? (
        <WorkflowList empty="Entrega archivos académicos desde una actividad asignada.">
          {[]}
        </WorkflowList>
      ) : (
        <WorkflowState query={context}>
          {actualCategory === 'evidence' ? (
            <WorkflowList empty="Gestiona versiones desde la evidencia académica.">
              {[]}
            </WorkflowList>
          ) : (
            <form
              className="panel max-w-3xl space-y-4 p-6"
              onSubmit={async (event) => {
                event.preventDefault();
                if (!file) return;
                const data = new FormData(event.currentTarget);
                setStage('Subiendo…');
                try {
                  const result = await mutation.mutateAsync({
                    context: {
                      category: actualCategory,
                      previousId: previous.data?.id,
                      proposalId: proposal.data?.id ?? previous.data?.proposalId,
                      projectId:
                        previous.data?.projectId ??
                        (String(data.get('project') ?? '') || undefined),
                    },
                    file,
                  });
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
              {previousId || proposalId ? (
                <p className="text-sm">
                  {previous.data?.name ?? proposal.data?.title} · {labels[actualCategory]}
                </p>
              ) : (
                <>
                  <Field label="Tipo de archivo">
                    <select
                      className="input"
                      value={category}
                      onChange={(event) => {
                        setCategory(event.target.value === 'report' ? 'report' : 'other');
                        setFile(undefined);
                        setStage('');
                        setError(undefined);
                        mutation.reset();
                      }}
                    >
                      <option value="other">Documento compartido</option>
                      <option value="report">Informe final</option>
                    </select>
                  </Field>
                  <Field label="Proyecto">
                    <select
                      className="input"
                      name="project"
                      required
                      defaultValue={params.get('proyecto') ?? ''}
                    >
                      <option value="">Selecciona un proyecto</option>
                      {eligible.map((item) => (
                        <option value={item.id} key={item.id}>
                          {item.title}
                        </option>
                      ))}
                    </select>
                  </Field>
                </>
              )}
              <Field label="Archivo">
                <Input
                  key={actualCategory}
                  type="file"
                  accept={actualCategory === 'report' ? '.pdf' : fileAccept}
                  required
                  disabled={mutation.isPending}
                  onChange={(event) => {
                    setError(undefined);
                    setFile(undefined);
                    setStage('');
                    mutation.reset();
                    const selected = event.target.files?.[0];
                    if (!selected) return;
                    try {
                      fileType(selected, actualCategory);
                      setFile(selected);
                      setStage('Archivo seleccionado');
                    } catch (cause) {
                      setStage('Rechazado');
                      setError(
                        cause instanceof Error ? cause : new Error('No pudimos leer el archivo.'),
                      );
                    }
                  }}
                />
              </Field>
              {stage && (
                <p role="status" className="text-sm text-muted">
                  {stage}
                </p>
              )}
              <Feedback error={error ?? mutation.error} />
              <Button type="submit" disabled={!file || mutation.isPending}>
                Subir archivo
              </Button>
            </form>
          )}
        </WorkflowState>
      )}
    </>
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
