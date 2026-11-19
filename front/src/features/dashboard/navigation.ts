import {
  CalendarDays,
  ClipboardCheck,
  FileText,
  FolderKanban,
  LayoutDashboard,
  ShieldCheck,
  Sparkles,
  UserRound,
  type LucideIcon,
} from 'lucide-react';
import { config } from '../../lib/config';
import type { Role, User } from '../../types';
import { can, type Capability } from '../auth/model';

type Item = { href: string; label: string; icon: LucideIcon; permission?: Capability };
const item = (href: string, label: string, icon: LucideIcon, permission?: Capability): Item => ({
  href,
  label,
  icon,
  permission,
});
const assistant = item('/asistente', 'Asistente IA', Sparkles, 'ai:ask');
const profile = item('/perfil', 'Perfil', UserRound);
const tasks = item('/actividades?vista=mias', 'Mis actividades', ClipboardCheck, 'activity:read');
const evidence = item('/evidencias', 'Evidencias', FileText, 'project:evidence-read');
export const workspacePaths: Record<Role, string> = {
  admin: '/panel/administrador',
  coordinator: '/panel/coordinador',
  researcher: '/panel/investigador',
  reviewer: '/panel/evaluador',
  student: '/panel/estudiante',
};
export const workspaceTitles: Record<Role, string> = {
  admin: 'Resumen administrativo',
  coordinator: 'Centro de coordinación',
  researcher: 'Mi investigación',
  reviewer: 'Centro de evaluaciones',
  student: 'Mis actividades de investigación',
};
const connected = new Set([
  '/usuarios',
  '/propuestas',
  '/proyectos',
  '/perfil',
  '/administracion/configuracion',
]);
const workspaces: Record<Role, Item[]> = {
  admin: [
    item('/usuarios', 'Usuarios', UserRound, 'user:read'),
    item('/administracion/roles', 'Asignación de roles', ShieldCheck, 'role:assign'),
    item('/administracion/unidades', 'Unidades académicas', FolderKanban, 'academic-unit:manage'),
    item('/administracion/auditoria', 'Auditoría', ClipboardCheck, 'audit:read'),
    item('/administracion/configuracion', 'Preferencias', UserRound, 'user:read'),
    profile,
  ],
  coordinator: [
    item('/coordinacion', 'Mi coordinación', LayoutDashboard, 'academic-unit:read'),
    item('/propuestas', 'Propuestas recibidas', FileText, 'proposal:read'),
    item(
      '/coordinacion/evaluadores',
      'Asignar evaluadores de proyecto',
      UserRound,
      'project:assign-evaluator',
    ),
    item('/evaluaciones/asignar', 'Asignación de evaluadores', UserRound, 'evaluation:assign'),
    item('/evaluaciones', 'Evaluaciones y decisiones', ClipboardCheck, 'evaluation:read'),
    item('/proyectos', 'Proyectos supervisados', FolderKanban, 'project:read'),
    item(
      '/solicitudes-estudiantes',
      'Solicitudes de estudiantes',
      UserRound,
      'project:student-requests',
    ),
    item('/actividades/calendario', 'Seguimiento', CalendarDays, 'activity:read'),
    item('/reportes', 'Reportes', FileText, 'report:read'),
    item('/reportes?vista=cierre', 'Cierre de proyectos', ClipboardCheck, 'project:close'),
    assistant,
    profile,
  ],
  researcher: [
    item('/propuestas', 'Mis propuestas', FileText, 'proposal:read'),
    item('/proyectos', 'Mis proyectos', FolderKanban, 'project:read'),
    item(
      '/solicitudes-estudiantes',
      'Estudiantes y colaboradores',
      UserRound,
      'project:student-propose',
    ),
    tasks,
    item('/actividades/hitos', 'Hitos', CalendarDays, 'activity:read'),
    item('/actividades/calendario', 'Seguimiento', CalendarDays, 'activity:read'),
    item('/documentos', 'Documentos', FileText, 'document:read'),
    evidence,
    item('/reportes?vista=informes', 'Informe final', FileText, 'report:read'),
    assistant,
    profile,
  ],
  reviewer: [
    item('/proyectos', 'Proyectos asignados', FolderKanban, 'project:read'),
    item(
      '/evidencias?vista=pendientes',
      'Evidencias pendientes',
      ClipboardCheck,
      'project:evidence-grade',
    ),
    item('/evidencias', 'Evaluar evidencia', FileText, 'project:evidence-grade'),
    item(
      '/evidencias?vista=calificaciones',
      'Historial de calificaciones',
      FileText,
      'project:evidence-grade-read',
    ),
    item(
      '/evaluaciones?estado=pendientes',
      'Evaluaciones pendientes',
      ClipboardCheck,
      'evaluation:read',
    ),
    item('/propuestas', 'Propuestas asignadas', FileText, 'proposal:read'),
    item(
      '/evaluaciones?estado=in_progress',
      'Evaluaciones en borrador',
      ClipboardCheck,
      'evaluation:read',
    ),
    item(
      '/evaluaciones?estado=submitted',
      'Evaluaciones enviadas',
      ClipboardCheck,
      'evaluation:read',
    ),
    assistant,
    profile,
  ],
  student: [
    item('/proyectos', 'Mis proyectos', FolderKanban, 'project:read'),
    tasks,
    item('/actividades/calendario', 'Próximas entregas', CalendarDays, 'activity:read'),
    item('/evidencias/nueva', 'Entregar evidencias', FileText, 'project:evidence-submit'),
    item('/evidencias', 'Mis entregas', FileText, 'project:evidence-read'),
    item(
      '/evidencias?vista=correcciones',
      'Correcciones solicitadas',
      ClipboardCheck,
      'project:evidence-read',
    ),
    item(
      '/evidencias?vista=calificaciones',
      'Mis calificaciones',
      FileText,
      'project:evidence-grade-read',
    ),
    item('/documentos', 'Documentos compartidos', FileText, 'document:read'),
    assistant,
    profile,
  ],
};
export function workspaceNavigation(user: User, role: Role) {
  return [
    item('/', 'Inicio', LayoutDashboard),
    ...workspaces[role].filter(
      ({ href, permission }) =>
        (config.demo || connected.has(href)) && (!permission || can(user, permission)),
    ),
  ];
}
