import {
  ArrowRight,
  ArrowUpRight,
  CheckCheck,
  CircleDot,
  Clock3,
  FolderKanban,
  Plus,
} from 'lucide-react';
import { Link } from 'react-router-dom';
import { AsyncState, PageHeading } from '../../components/ui';
import { useSession } from '../../api/auth.queries';
import { can } from '../auth/model';
import { useProjects } from '../../api/projects.queries';
import { ProjectTable } from '../projects/project-table';
import { areas, statusLabels, statusSchema } from '../../types/project';
import { currency, dateLabel } from '../../lib/format';

export function DashboardPage() {
  const query = useProjects();
  const { data: user } = useSession();
  if (query.isPending) return <AsyncState loading />;
  if (query.error) return <AsyncState error={query.error} retry={() => void query.refetch()} />;
  const projects = query.data;
  const active = projects.filter((project) => project.status === 'active');
  const stats = [
    {
      label: 'Proyectos registrados',
      value: projects.length,
      note: 'Portafolio institucional',
      Icon: FolderKanban,
      color: 'bg-sage text-forest',
    },
    {
      label: 'En ejecución',
      value: active.length,
      note: 'Investigación en movimiento',
      Icon: CircleDot,
      color: 'bg-blue-50 text-blue-700',
    },
    {
      label: 'En revisión',
      value: projects.filter((p) => p.status === 'review').length,
      note: 'Seguimiento académico',
      Icon: Clock3,
      color: 'bg-amber-50 text-amber-700',
    },
    {
      label: 'Finalizados',
      value: projects.filter((p) => p.status === 'completed').length,
      note: 'Conocimiento que trasciende',
      Icon: CheckCheck,
      color: 'bg-violet-50 text-violet-700',
    },
  ];
  const upcoming = projects
    .filter((p) => p.status !== 'completed')
    .sort((a, b) => a.endDate.localeCompare(b.endDate))
    .slice(0, 3);
  const totalBudget = projects.reduce((total, p) => total + p.budget, 0);
  return (
    <>
      <PageHeading
        eyebrow="Tu investigación, en perspectiva"
        title="Panel general"
        description={`Hola, ${user?.name.split(' ')[0] ?? ''}. Aquí tienes una visión de la actividad de investigación institucional.`}
        action={
          user &&
          can(user.role, 'createProject') && (
            <Link className="btn-primary" to="/proyectos/nuevo">
              <Plus size={17} />
              Nuevo proyecto
            </Link>
          )
        }
      />
      <section className="hero-panel mb-7 flex flex-col justify-between gap-7 p-7 sm:flex-row sm:items-center sm:p-8">
        <div>
          <span className="mb-4 inline-flex items-center gap-2 text-[10px] font-medium uppercase tracking-[0.15em] text-lime-200">
            <span className="size-1.5 rounded-full bg-lime-200" />
            Conocimiento en acción
          </span>
          <h2 className="max-w-lg text-2xl font-medium leading-snug tracking-tight">
            Cada proyecto es un paso
            <br className="hidden sm:block" /> hacia nuevas posibilidades.
          </h2>
          <p className="mt-3 max-w-md text-xs leading-6 text-white/65">
            Consulta el avance, acompaña a tu equipo y transforma los resultados en impacto.
          </p>
          <Link
            to="/proyectos"
            className="mt-5 inline-flex items-center gap-2 text-xs font-medium text-lime-200"
          >
            Explorar el portafolio
            <ArrowRight size={15} />
          </Link>
        </div>
        <div className="relative z-10 border-l border-white/20 pl-7 sm:min-w-48">
          <p className="text-[10px] uppercase tracking-wider text-white/60">
            Presupuesto registrado
          </p>
          <p className="mt-2 text-3xl font-medium tracking-tight">{currency(totalBudget)}</p>
          <p className="mt-2 text-xs text-white/55">MXN · {projects.length} proyectos</p>
        </div>
      </section>
      <section
        aria-label="Indicadores de investigación"
        className="mb-8 grid gap-4 sm:grid-cols-2 xl:grid-cols-4"
      >
        {stats.map(({ label, value, note, Icon, color }) => (
          <article key={label} className="panel p-5">
            <div className="flex items-center justify-between">
              <span className="text-xs text-muted">{label}</span>
              <span className={`rounded-lg p-2 ${color}`}>
                <Icon size={17} aria-hidden="true" />
              </span>
            </div>
            <p className="mt-3 text-3xl font-semibold tracking-tight">
              {value.toString().padStart(2, '0')}
            </p>
            <p className="mt-3 text-[11px] text-muted">{note}</p>
          </article>
        ))}
      </section>
      <div className="mb-8 grid gap-6 xl:grid-cols-[1.5fr_1fr]">
        <section className="panel p-6">
          <div className="flex items-start justify-between gap-3">
            <div>
              <h2 className="section-title">Distribución del portafolio</h2>
              <p className="mt-1 text-xs text-muted">Proyectos por línea de investigación</p>
            </div>
            <span className="rounded-md bg-canvas px-2 py-1 text-[10px] text-muted">
              {projects.length} en total
            </span>
          </div>
          <div className="mt-7 space-y-5">
            {areas.map((area, index) => {
              const count = projects.filter((p) => p.area === area).length;
              return (
                <div
                  key={area}
                  className="grid grid-cols-[minmax(0,1fr)_minmax(60px,1fr)_20px] items-center gap-4"
                >
                  <span className="text-xs text-muted">{area}</span>
                  <div className="h-2 rounded-full bg-canvas">
                    <div
                      className={`h-full rounded-full ${['bg-accent', 'bg-[#9bb59b]', 'bg-[#b8a78c]', 'bg-[#a9b8cd]'][index]}`}
                      style={{ width: `${projects.length ? (count / projects.length) * 100 : 0}%` }}
                    />
                  </div>
                  <span className="text-right text-xs font-medium">{count}</span>
                </div>
              );
            })}
          </div>
          <div className="mt-7 flex flex-wrap gap-x-4 gap-y-2 border-t border-line pt-4">
            {statusSchema.options.map((status) => (
              <Link
                key={status}
                to={`/proyectos?estado=${status}`}
                className="text-[10px] text-muted hover:text-accent"
              >
                {statusLabels[status]} · {projects.filter((p) => p.status === status).length}
              </Link>
            ))}
          </div>
        </section>
        <section className="panel p-6">
          <div className="flex items-center justify-between">
            <h2 className="section-title">Próximos cierres</h2>
            <Clock3 size={17} className="text-muted" aria-hidden="true" />
          </div>
          <p className="mt-1 text-xs text-muted">Fechas de cierre programadas del portafolio</p>
          <div className="mt-5 divide-y divide-line">
            {upcoming.map((project) => (
              <Link
                key={project.id}
                to={`/proyectos/${project.id}`}
                className="group flex items-start gap-3 py-4"
              >
                <span className="mt-1 size-2 shrink-0 rounded-full bg-accent/60" />
                <div className="flex-1">
                  <p className="text-xs font-medium leading-5 group-hover:text-accent">
                    {project.title}
                  </p>
                  <p className="mt-1 text-[10px] text-muted">{dateLabel(project.endDate)}</p>
                </div>
                <ArrowUpRight size={14} className="mt-1 shrink-0 text-muted" />
              </Link>
            ))}
            {!upcoming.length && <p className="py-6 text-sm text-muted">Sin cierres pendientes.</p>}
          </div>
        </section>
      </div>
      <section className="panel overflow-hidden">
        <div className="flex items-center justify-between gap-3 p-6">
          <div>
            <h2 className="section-title">Proyectos del portafolio</h2>
            <p className="mt-1 text-xs text-muted">El avance de las ideas que nos mueven</p>
          </div>
          <Link
            to="/proyectos"
            className="flex shrink-0 items-center gap-2 text-xs font-medium text-accent"
          >
            Ver todos
            <ArrowRight size={15} />
          </Link>
        </div>
        <ProjectTable projects={projects.slice(0, 4)} />
      </section>
    </>
  );
}
