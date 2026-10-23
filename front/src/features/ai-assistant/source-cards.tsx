import { Link } from 'react-router-dom';
import { FileCheck2 } from 'lucide-react';
import type { AssistantSource } from './model';

export function SourceCards({ sources }: { sources: AssistantSource[] }) {
  return (
    <section aria-label="Fuentes y documentos de respaldo">
      <h2 className="section-title mb-4">Fuentes y documentos de respaldo</h2>
      <p className="mb-4 text-xs leading-6 text-muted">
        Referencias de demostración verificadas contra las fichas autorizadas al generar la
        respuesta. No representan documentos científicos externos.
      </p>
      {!sources.length && <p className="text-sm text-muted">Esta respuesta no utilizó fuentes.</p>}
      <div className="space-y-3">
        {sources.map((source, index) => (
          <details key={source.id} className="rounded-lg border border-line bg-white">
            <summary className="flex cursor-pointer items-center gap-3 p-4 text-sm font-medium">
              <FileCheck2 size={18} className="shrink-0 text-accent" aria-hidden="true" />
              <span>
                [{index + 1}] {source.title}
              </span>
              <span className="ml-auto text-[10px] text-accent">Ver respaldo +</span>
            </summary>
            <div className="border-t border-line p-4">
              <p className="text-xs text-muted">
                Instantánea de demostración · versión {source.version}
              </p>
              <p className="mt-3 text-sm leading-7">{source.excerpt}</p>
              <Link
                to={`/proyectos/${source.projectId}`}
                className="mt-4 inline-block text-xs font-medium text-accent"
              >
                Consultar ficha del proyecto →
              </Link>
            </div>
          </details>
        ))}
      </div>
    </section>
  );
}
