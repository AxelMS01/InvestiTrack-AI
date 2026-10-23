import { Table } from '../../components/table';
import { ArrowUpRight } from 'lucide-react';
import { Link } from 'react-router-dom';
import { EmptyState, Progress, StatusBadge } from '../../components/ui';
import { dateLabel, initials } from '../../lib/format';
import type { Project } from '../../types/project';

export function ProjectTable({ projects }: { projects: Project[] }) {
  if (projects.length === 0)
    return (
      <EmptyState
        title="No hay proyectos para mostrar"
        description="Prueba con otro término de búsqueda o ajusta los filtros seleccionados."
      />
    );
  return (
    <Table className="min-w-[760px]">
      <caption className="sr-only">Proyectos de investigación y su avance</caption>
      <thead>
        <tr className="border-y border-line bg-canvas/70 text-[10px] uppercase tracking-wider text-muted">
          <th scope="col" className="px-6 py-4 font-medium">
            Proyecto
          </th>
          <th scope="col" className="px-4 py-4 font-medium">
            Responsable
          </th>
          <th scope="col" className="px-4 py-4 font-medium">
            Estado
          </th>
          <th scope="col" className="min-w-36 px-4 py-4 font-medium">
            Avance
          </th>
          <th scope="col" className="px-6 py-4 font-medium">
            Cierre
          </th>
        </tr>
      </thead>
      <tbody>
        {projects.map((project) => (
          <tr key={project.id} className="border-b border-line last:border-b-0 hover:bg-canvas/60">
            <td className="max-w-80 px-6 py-5">
              <span className="text-[10px] font-medium tracking-wide text-muted">
                {project.code}
              </span>
              <Link
                to={`/proyectos/${project.id}`}
                className="group mt-1.5 flex items-start gap-2 font-medium leading-5 text-ink hover:text-accent"
              >
                {project.title}
                <ArrowUpRight
                  size={15}
                  className="mt-0.5 shrink-0 text-muted group-hover:text-accent"
                  aria-hidden="true"
                />
              </Link>
              <span className="mt-1.5 block text-[11px] text-muted">{project.area}</span>
            </td>
            <td className="px-4 py-5">
              <div className="flex items-center gap-2">
                <span className="avatar size-7 bg-sage text-[10px] text-forest">
                  {initials(project.lead)}
                </span>
                <span className="whitespace-nowrap text-xs text-muted">{project.lead}</span>
              </div>
            </td>
            <td className="px-4 py-5">
              <StatusBadge status={project.status} />
            </td>
            <td className="px-4 py-5">
              <Progress value={project.progress} />
            </td>
            <td className="whitespace-nowrap px-6 py-5 text-xs text-muted">
              {dateLabel(project.endDate)}
            </td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}
