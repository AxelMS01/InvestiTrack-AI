import { Plus, Search, SlidersHorizontal } from 'lucide-react';
import { Link, useSearchParams } from 'react-router-dom';
import { AsyncState, PageHeading } from '../../components/ui';
import { useSession } from '../../api/auth.queries';
import { can } from '../auth/model';
import { useProjects } from '../../api/projects.queries';
import { areas, statusLabels, statusSchema } from '../../types/project';
import { ProjectTable } from './project-table';

export function ProjectsPage() {
  const query = useProjects();
  const { data: user } = useSession();
  const [params, setParams] = useSearchParams();
  const search = params.get('q') ?? '';
  const status = params.get('estado') ?? '';
  const area = params.get('area') ?? '';
  function update(key: string, value: string) {
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous);
        if (value) next.set(key, value);
        else next.delete(key);
        return next;
      },
      { replace: true },
    );
  }
  const filtered = (query.data ?? []).filter(
    (p) =>
      (!status || p.status === status) &&
      (!area || p.area === area) &&
      `${p.title} ${p.code} ${p.lead}`
        .toLocaleLowerCase('es')
        .includes(search.toLocaleLowerCase('es')),
  );
  return (
    <>
      <PageHeading
        eyebrow="Portafolio institucional"
        title="Proyectos de investigación"
        description="Encuentra, consulta y da seguimiento a las iniciativas que construyen conocimiento."
        action={
          user &&
          can(user.role, 'createProject') && (
            <Link to="/proyectos/nuevo" className="btn-primary">
              <Plus size={17} />
              Nuevo proyecto
            </Link>
          )
        }
      />
      <section className="panel overflow-hidden">
        <div className="flex flex-wrap gap-3 p-5">
          <div className="relative min-w-52 flex-1">
            <Search
              size={17}
              className="pointer-events-none absolute top-3.5 left-3 text-muted"
              aria-hidden="true"
            />
            <label htmlFor="search" className="sr-only">
              Buscar por proyecto, código o responsable
            </label>
            <input
              id="search"
              className="input pl-10"
              placeholder="Buscar proyecto, código o responsable…"
              value={search}
              onChange={(e) => update('q', e.target.value)}
            />
          </div>
          <div className="flex flex-wrap gap-3">
            <label htmlFor="status" className="sr-only">
              Filtrar por estado
            </label>
            <select
              id="status"
              className="input w-auto"
              value={status}
              onChange={(e) => update('estado', e.target.value)}
            >
              <option value="">Todos los estados</option>
              {statusSchema.options.map((value) => (
                <option key={value} value={value}>
                  {statusLabels[value]}
                </option>
              ))}
            </select>
            <label htmlFor="area" className="sr-only">
              Filtrar por área
            </label>
            <select
              id="area"
              className="input w-auto max-w-full"
              value={area}
              onChange={(e) => update('area', e.target.value)}
            >
              <option value="">Todas las áreas</option>
              {areas.map((value) => (
                <option key={value}>{value}</option>
              ))}
            </select>
            {(search || status || area) && (
              <button className="btn-secondary" onClick={() => setParams({})}>
                <SlidersHorizontal size={15} />
                Limpiar filtros
              </button>
            )}
          </div>
        </div>
        {query.isPending ? (
          <AsyncState loading />
        ) : query.error ? (
          <AsyncState error={query.error} retry={() => void query.refetch()} />
        ) : (
          <>
            <ProjectTable projects={filtered} />
            <p aria-live="polite" className="border-t border-line px-6 py-4 text-xs text-muted">
              {filtered.length} de {query.data.length} proyectos
            </p>
          </>
        )}
      </section>
    </>
  );
}
