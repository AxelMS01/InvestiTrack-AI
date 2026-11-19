import { Link } from 'react-router-dom';
import { FileCheck2 } from 'lucide-react';
import type { AssistantSource } from './model';

export function SourceCards({ sources }: { sources: AssistantSource[] }) {
  return (
    <section aria-label="Fuentes y documentos de respaldo">
      <h2 className="section-title mb-4">Fuentes y documentos de respaldo</h2>
      <p className="mb-4 text-xs leading-6 text-muted">
        Archivos registrados en el repositorio de demostración y accesibles desde tu cuenta.
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
                Archivo del repositorio · versión {source.version}
              </p>
              <p className="mt-3 text-sm leading-7">{source.excerpt}</p>
              <Link
                to={`/documentos/${source.documentId}`}
                className="mt-4 inline-block text-xs font-medium text-accent"
              >
                Abrir documento →
              </Link>
            </div>
          </details>
        ))}
      </div>
    </section>
  );
}
