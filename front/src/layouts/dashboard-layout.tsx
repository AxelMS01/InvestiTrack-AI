import { useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import {
  ArrowUpRight,
  BookOpen,
  FolderKanban,
  LayoutDashboard,
  LogOut,
  ShieldCheck,
  X,
  Sparkles,
  FileText,
} from 'lucide-react';
import { useLogout, useSession } from '../api/auth.queries';
import { can, roleLabels } from '../features/auth/model';
import { initials } from '../lib/format';
import { Brand } from '../components/ui';
import { InstitutionalHeader } from './institutional-header';

export function DashboardLayout() {
  const [open, setOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const { data: user } = useSession();
  const logout = useLogout();
  const location = useLocation();
  const section = location.pathname.startsWith('/asistente')
    ? 'Asistente IA'
    : location.pathname.startsWith('/propuestas')
      ? 'Propuestas'
      : location.pathname.startsWith('/proyectos')
        ? 'Proyectos'
        : location.pathname === '/usuarios'
          ? 'Administración'
          : 'Panel general';
  if (!user) return null;
  return (
    <div className="min-h-screen bg-canvas">
      <a href="#contenido" className="skip-link">
        Saltar al contenido
      </a>
      {open && (
        <button
          aria-label="Cerrar menú"
          className="fixed inset-0 z-30 bg-black/40 lg:hidden"
          onClick={() => setOpen(false)}
        />
      )}
      <aside
        id="institutional-sidebar"
        aria-label="Navegación principal"
        className={`sidebar ${open ? 'visible translate-x-0' : 'invisible -translate-x-full'} ${collapsed ? 'lg:hidden' : 'lg:visible lg:translate-x-0'}`}
      >
        <div className="flex items-center justify-between px-6 pt-8">
          <NavLink to="/" aria-label="InvescticTrack, inicio" onClick={() => setOpen(false)}>
            <Brand />
          </NavLink>
          <button
            className="rounded p-1 lg:hidden"
            aria-label="Cerrar menú"
            onClick={() => setOpen(false)}
          >
            <X size={20} />
          </button>
        </div>
        <div className="mx-5 mt-10 border-b border-white/10 pb-6">
          <p className="text-[10px] uppercase tracking-[0.18em] text-white/50">
            Espacio institucional
          </p>
          <p className="mt-2 text-sm font-medium">Dirección de Investigación</p>
          <p className="mt-1 text-xs text-white/55">Ciencia que genera impacto</p>
        </div>
        <nav className="mt-7 space-y-2 px-4">
          <p className="mb-4 px-3 text-[10px] font-medium uppercase tracking-[0.18em] text-white/45">
            Principal
          </p>
          <NavLink to="/" end className="nav-item" onClick={() => setOpen(false)}>
            <LayoutDashboard size={18} />
            Panel general
          </NavLink>
          <NavLink to="/proyectos" className="nav-item" onClick={() => setOpen(false)}>
            <FolderKanban size={18} />
            Proyectos
          </NavLink>
          {can(user.role, 'createProposal') && (
            <NavLink to="/propuestas" className="nav-item" onClick={() => setOpen(false)}>
              <FileText size={18} />
              Propuestas
            </NavLink>
          )}
          <NavLink to="/asistente" className="nav-item" onClick={() => setOpen(false)}>
            <Sparkles size={18} />
            Asistente IA
          </NavLink>
          {can(user.role, 'manageUsers') && (
            <NavLink to="/usuarios" className="nav-item" onClick={() => setOpen(false)}>
              <ShieldCheck size={18} />
              Administración
            </NavLink>
          )}
        </nav>
        <div className="mt-auto px-5 pb-5">
          <div className="rounded-xl border border-white/15 bg-white/5 p-4">
            <BookOpen size={21} className="mb-3 text-lime-200" aria-hidden="true" />
            <p className="text-sm font-medium">De la idea al impacto.</p>
            <p className="mt-2 text-xs leading-5 text-white/60">
              Conecta el conocimiento y da seguimiento a cada etapa de tu investigación.
            </p>
            <NavLink
              to="/proyectos"
              className="mt-3 inline-flex items-center gap-2 text-xs text-lime-200"
              onClick={() => setOpen(false)}
            >
              Explorar proyectos
              <ArrowUpRight size={14} />
            </NavLink>
          </div>
          <div className="mt-5 flex items-center gap-3 border-t border-white/10 pt-5">
            <span className="avatar bg-white/15 text-white">{initials(user.name)}</span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-xs font-medium">{user.name}</p>
              <p className="mt-1 text-[11px] text-white/55">{roleLabels[user.role]}</p>
            </div>
            <button
              aria-label="Cerrar sesión"
              title="Cerrar sesión"
              className="rounded-lg p-2 text-white/70 hover:bg-white/10 disabled:opacity-50"
              disabled={logout.isPending}
              onClick={() => logout.mutate()}
            >
              <LogOut size={17} />
            </button>
          </div>
          {logout.error && (
            <p role="alert" className="mt-3 text-xs text-red-200">
              {logout.error.message}
            </p>
          )}
        </div>
      </aside>
      <div className={collapsed ? '' : 'lg:ml-64'}>
        <InstitutionalHeader
          user={user}
          section={section}
          mobileOpen={open}
          collapsed={collapsed}
          onOpenMenu={() => setOpen(true)}
          onToggleSidebar={() => setCollapsed((value) => !value)}
        />
        <main
          id="contenido"
          tabIndex={-1}
          className="mx-auto max-w-[1500px] px-5 py-8 outline-none sm:px-9 sm:py-10"
        >
          <Outlet />
        </main>
        <footer className="mx-5 flex flex-wrap justify-between gap-2 border-t border-line py-6 text-[11px] text-muted sm:mx-9">
          <span>InvescticTrack · Gestión institucional de investigación</span>
          <span>Conocimiento con propósito</span>
        </footer>
      </div>
    </div>
  );
}
