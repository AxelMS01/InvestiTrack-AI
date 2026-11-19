import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useCreateProject } from '../../api/projects.queries';
import { useDemoQuery } from '../../api/demo.queries';
import { Button } from '../../components/button';
import { BackLink, PageHeading } from '../../components/ui';
import { demoProjects } from '../../demo/projects';
import { ActionLink, Feedback, Field, WorkflowState } from '../shared/workflow-ui';

export function ProjectCreatePage() {
  const [params] = useSearchParams();
  const [proposalId, setProposalId] = useState(params.get('propuesta') ?? '');
  const navigate = useNavigate();
  const query = useDemoQuery(['convertible-proposals'], demoProjects.convertible);
  const mutation = useCreateProject();
  const selected = query.data?.find((item) => item.id === proposalId);
  return (
    <>
      <BackLink />
      <PageHeading
        eyebrow="Proyectos de investigación"
        title="Convertir propuesta aprobada"
        description="El proyecto conserva el protocolo de origen, la responsabilidad y el periodo aprobados."
      />
      <WorkflowState query={query}>
        <form
          className="panel max-w-3xl space-y-5 p-6"
          onSubmit={async (event) => {
            event.preventDefault();
            if (!selected) return;
            const project = await mutation.mutateAsync(selected.id).catch(() => null);
            if (project) void navigate(`/proyectos/${project.id}`, { state: { converted: true } });
          }}
        >
          {query.data?.length ? (
            <>
              <Field label="Propuesta aprobada">
                <select
                  className="input"
                  required
                  value={selected?.id ?? ''}
                  onChange={(event) => setProposalId(event.target.value)}
                >
                  <option value="">Selecciona una propuesta</option>
                  {query.data.map((item) => (
                    <option value={item.id} key={item.id}>
                      {item.title}
                    </option>
                  ))}
                </select>
              </Field>
              {selected && <p className="text-sm text-muted">{selected.abstract}</p>}
              <Button type="submit" disabled={!selected || mutation.isPending}>
                {mutation.isPending ? 'Convirtiendo…' : 'Crear proyecto desde propuesta'}
              </Button>
            </>
          ) : (
            <>
              <p className="text-sm text-muted">
                Las propuestas aprobadas sin proyecto aparecerán aquí.
              </p>
              <ActionLink to="/propuestas">Consultar propuestas</ActionLink>
            </>
          )}
          <Feedback error={mutation.error} />
        </form>
      </WorkflowState>
    </>
  );
}
