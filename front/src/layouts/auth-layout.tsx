import { Microscope } from 'lucide-react';
import { Outlet } from 'react-router-dom';
import { Brand } from '../components/ui';

export function AuthLayout() {
  return (
    <main className="grid min-h-screen lg:grid-cols-2">
      <section className="relative flex flex-col justify-between overflow-hidden bg-forest p-8 text-white lg:p-16">
        <Brand />
        <div className="py-16">
          <span className="mb-8 inline-flex size-16 items-center justify-center rounded-2xl border border-white/20 bg-white/5">
            <Microscope size={32} aria-hidden="true" />
          </span>
          <p className="text-xs uppercase tracking-[0.2em] text-lime-200">
            Investigación que transforma
          </p>
          <h1 className="mt-5 max-w-lg text-4xl font-medium leading-tight tracking-tight lg:text-5xl">
            Grandes ideas.
            <br />
            <span className="text-lime-200">Impacto real.</span>
          </h1>
          <p className="mt-6 max-w-md text-sm leading-7 text-white/65">
            Un espacio para impulsar la ciencia, conectar equipos y acompañar cada proyecto desde su
            primera pregunta.
          </p>
        </div>
        <p className="text-xs text-white/50">InvescticTrack · Plataforma institucional</p>
      </section>
      <section className="flex items-center justify-center p-8 lg:p-16">
        <div className="w-full max-w-sm">
          <Outlet />
        </div>
      </section>
    </main>
  );
}
