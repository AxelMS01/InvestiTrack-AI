import { useState } from 'react';
import { Link, NavLink, Outlet, matchPath, useLocation } from 'react-router-dom';
import { LogOut, X } from 'lucide-react';
import { useLogout } from '../api/auth.queries';
import { useAuth } from '../features/auth/auth-context';
import { roleLabels } from '../features/auth/model';
import {
  workspaceNavigation,
  workspacePaths,
  workspaceTitles,
} from '../features/dashboard/navigation';
import { modules } from '../features/modules';
import { userRoles } from '../types/user';
import { config } from '../lib/config';
import { initials } from '../lib/format';
import { Brand } from '../components/ui';
import { InstitutionalHeader } from './institutional-header';

export function DashboardLayout() {
  const [open, setOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const { user, workspace } = useAuth();
  const logout = useLogout();
  const location = useLocation();
  if (!user) return null;
  const active =
    userRoles(user).find((role) => workspacePaths[role] === location.pathname) ??
    workspace ??
    user.role;
  const menu = workspaceNavigation(user, active);
  const screens = modules.flatMap((module) => module.screens);
  const route = screens
    .filter((screen) => matchPath(screen.path, location.pathname))
    .sort((a, b) => Number(a.path.includes(':')) - Number(b.path.includes(':')))[0];
  const basePath = '/' + (location.pathname.split('/')[1] ?? '');
  const group = screens.find((screen) => screen.path === basePath && basePath !== '/');
  const section =
    location.pathname === '/'
      ? workspaceTitles[active]
      : (route?.label ?? group?.label ?? 'Mi espacio');
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
          {menu.map(({ href, label, icon: Icon }) => {
            const [path, search] = href.split('?');
            const activeItem =
              (path === '/'
                ? location.pathname === '/'
                : location.pathname === path ||
                  (!search && location.pathname.startsWith(path + '/'))) &&
              (!search || location.search === '?' + search);
            return (
              <Link
                key={href}
                to={href}
                aria-current={activeItem ? 'page' : undefined}
                className={`nav-item${activeItem ? ' active' : ''}`}
                onClick={() => setOpen(false)}
              >
                <Icon size={18} />
                {label}
              </Link>
            );
          })}
        </nav>
        <div className="mt-auto px-5 pb-5">
          <div className="mt-5 flex items-center gap-3 border-t border-white/10 pt-5">
            <span className="avatar bg-white/15 text-white">{initials(user.name)}</span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-xs font-medium">{user.name}</p>
              <p className="mt-1 text-[11px] text-white/55">{roleLabels[active]}</p>
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
          context={active}
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
          {config.demo && (
            <p className="mb-4 rounded-lg border border-amber-200 bg-amber-50 px-4 py-2 text-xs text-amber-900 sm:hidden">
              Demostración · datos simulados
            </p>
          )}
          {location.pathname !== '/' && (
            <nav
              aria-label="Ruta de navegación"
              className="mb-5 flex flex-wrap items-center gap-2 text-xs text-muted"
            >
              <Link className="hover:underline" to="/">
                Inicio
              </Link>
              <span aria-hidden="true">/</span>
              {group &&
                group.path !== location.pathname &&
                menu.some((entry) => entry.href.split('?')[0] === group.path) && (
                  <>
                    <Link className="hover:underline" to={group.path}>
                      {group.label}
                    </Link>
                    <span aria-hidden="true">/</span>
                  </>
                )}
              <span aria-current="page">{section}</span>
              <span className="ml-auto sm:hidden">{roleLabels[active]}</span>
            </nav>
          )}
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
