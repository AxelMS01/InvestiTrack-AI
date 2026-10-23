import { ChevronRight, Menu, PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import { config } from '../lib/config';
import { initials } from '../lib/format';
import type { User } from '../types';
import { RoleSwitcher } from '../features/auth/role-switcher';

export function InstitutionalHeader({
  user,
  section,
  mobileOpen,
  collapsed,
  onOpenMenu,
  onToggleSidebar,
}: {
  user: User;
  section: string;
  mobileOpen: boolean;
  collapsed: boolean;
  onOpenMenu: () => void;
  onToggleSidebar: () => void;
}) {
  return (
    <header className="flex h-20 items-center justify-between gap-4 border-b border-line bg-white px-5 sm:px-9">
      <div className="flex items-center gap-3">
        <button
          className="rounded-lg p-2 lg:hidden"
          aria-label="Abrir menú"
          aria-controls="institutional-sidebar"
          aria-expanded={mobileOpen}
          onClick={onOpenMenu}
        >
          <Menu size={22} />
        </button>
        <button
          className="hidden rounded-lg p-2 lg:block"
          aria-label={collapsed ? 'Expandir barra lateral' : 'Colapsar barra lateral'}
          aria-controls="institutional-sidebar"
          aria-expanded={!collapsed}
          onClick={onToggleSidebar}
        >
          {collapsed ? <PanelLeftOpen size={20} /> : <PanelLeftClose size={20} />}
        </button>
        <span className="hidden text-xs text-muted sm:inline">Mi espacio</span>
        <ChevronRight className="hidden text-slate-300 sm:block" size={14} aria-hidden="true" />
        <span className="hidden text-xs font-medium sm:inline">{section}</span>
      </div>
      <div className="flex items-center gap-4">
        <RoleSwitcher />
        {config.demo && (
          <span className="hidden rounded-full border border-amber-200 bg-amber-50 px-3 py-1 text-[10px] font-semibold text-amber-800 sm:inline-flex">
            DEMOSTRACIÓN
          </span>
        )}
        <span className="hidden text-xs text-muted md:inline">Plataforma institucional</span>
        <span className="avatar hidden bg-sage text-forest sm:inline-flex" aria-label={user.name}>
          {initials(user.name)}
        </span>
      </div>
    </header>
  );
}
