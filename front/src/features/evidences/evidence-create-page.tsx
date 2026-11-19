import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useDemoQuery, useWorkflowMutation } from '../../api/demo.queries';
import { Button } from '../../components/button';
import { Input } from '../../components/input';
import { PageHeading } from '../../components/ui';
import { fileAccept } from '../../demo/documents';
import { demoEvidences } from '../../demo/evidences';
import { ActionLink, Feedback, Field, WorkflowList, WorkflowState } from '../shared/workflow-ui';

export function EvidenceCreatePage() {
  const [params] = useSearchParams();
  const activity = params.get('actividad');
  const query = useDemoQuery(['evidence-activities'], demoEvidences.options);
  const mutation = useWorkflowMutation(demoEvidences.create);
  const navigate = useNavigate();
  const [selectedProject, setProject] = useState(params.get('proyecto') ?? '');
  const [files, setFiles] = useState<File[]>([]);
  const project =
    selectedProject || query.data?.find((item) => item.id === activity)?.projectId || '';
  const projects = new Map((query.data ?? []).map((item) => [item.projectId, item.projectTitle]));
  return (
    <>
      <PageHeading
        eyebrow="Entregas académicas"
        title="Crear evidencia"
        description="Registra un nombre y una descripción de la entrega. Guarda el borrador para consultar y confirmar el envío."
        action={
          <ActionLink to={activity ? `/actividades/${activity}` : '/evidencias'}>Volver</ActionLink>
        }
      />
      <WorkflowState query={query}>
        {projects.size ? (
          <form
            className="panel max-w-3xl space-y-4 p-6"
            onSubmit={async (event) => {
              event.preventDefault();
              const data = new FormData(event.currentTarget);
              const saved = await mutation
                .mutateAsync({
                  activityId: String(data.get('activity') ?? ''),
                  title: String(data.get('title') ?? ''),
                  description: String(data.get('description') ?? ''),
                  files,
                })
                .catch(() => null);
              if (saved) void navigate(`/evidencias/${saved.id}`, { state: { saved: true } });
            }}
          >
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Proyecto">
                <select
                  className="input"
                  value={project}
                  onChange={(event) => setProject(event.target.value)}
                  required
                >
                  <option value="">Selecciona un proyecto</option>
                  {[...projects].map(([id, name]) => (
                    <option key={id} value={id}>
                      {name}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Actividad">
                <select
                  className="input"
                  name="activity"
                  key={project}
                  defaultValue={activity ?? ''}
                  required
                >
                  <option value="">Selecciona una actividad</option>
                  {query.data
                    ?.filter((item) => item.projectId === project)
                    .map((item) => (
                      <option key={item.id} value={item.id}>
                        {item.title}
                      </option>
                    ))}
                </select>
              </Field>
            </div>
            <Field label="Nombre de la evidencia">
              <Input name="title" maxLength={250} required />
            </Field>
            <Field label="Descripción">
              <textarea className="input" name="description" rows={4} maxLength={4000} />
            </Field>
            <Field label="Archivos adjuntos (opcional en borrador)">
              <Input
                type="file"
                accept={fileAccept}
                multiple
                onChange={(event) => setFiles(Array.from(event.target.files ?? []))}
              />
            </Field>
            <p className="text-xs text-muted">Hasta cinco archivos, de hasta 10 MB cada uno.</p>
            {mutation.isPending && (
              <p role="status" className="text-sm text-muted">
                Validando y guardando la evidencia…
              </p>
            )}
            <Feedback error={mutation.error} />
            <Button type="submit" disabled={files.length > 5 || mutation.isPending}>
              Guardar borrador
            </Button>
            {files.length > 5 && (
              <p role="alert" className="field-error">
                Selecciona hasta cinco archivos.
              </p>
            )}
          </form>
        ) : (
          <WorkflowList empty="Necesitas una actividad asignada en un proyecto abierto.">
            {[]}
          </WorkflowList>
        )}
      </WorkflowState>
    </>
  );
}
