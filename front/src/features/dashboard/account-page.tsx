import { useState } from 'react';
import { useDemoQuery } from '../../api/demo.queries';
import { useProposals } from '../../api/proposals.queries';
import { Button } from '../../components/button';
import { PageHeading } from '../../components/ui';
import { demoActivities } from '../../demo/activities';
import { demoEvaluations } from '../../demo/evaluations';
import { addDays, dateLabel } from '../../lib/format';
import { departmentLabels } from '../../types/department';
import { userRoles } from '../../types/user';
import { config } from '../../lib/config';
import { useAuth } from '../auth/auth-context';
import { can, roleLabels } from '../auth/model';
import { ActionLink, Feedback, WorkflowList, WorkflowState } from '../shared/workflow-ui';

function readPreference(id: string) {
  return localStorage.getItem(`invesctictrack.preference.${id}.reminders`) !== 'off';
}

export function ProfilePage() {
  const { user } = useAuth();
  return (
    user && (
      <>
        <PageHeading
          eyebrow="Cuenta institucional"
          title="Mi perfil"
          description="Consulta tu cuenta y configura los avisos en este navegador."
        />
        <dl className="panel mb-5 grid gap-4 p-6 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-muted">Nombre</dt>
            <dd>{user.name}</dd>
          </div>
          <div>
            <dt className="text-muted">Correo institucional</dt>
            <dd>{user.email}</dd>
          </div>
          <div>
            <dt className="text-muted">Departamento</dt>
            <dd>{user.departmentId ? departmentLabels[user.departmentId] : 'Sin asignar'}</dd>
          </div>
          <div>
            <dt className="text-muted">Roles</dt>
            <dd>
              {userRoles(user)
                .map((role) => roleLabels[role])
                .join(', ')}
            </dd>
          </div>
        </dl>
        <PreferenceForm key={user.id} id={user.id} />
      </>
    )
  );
}

export function PreferencesPage() {
  const { user } = useAuth();
  return (
    user && (
      <>
        <PageHeading
          eyebrow="Cuenta institucional"
          title="Preferencias"
          description="Ajusta los avisos de vencimiento en este navegador."
          action={<ActionLink to="/perfil">Volver al perfil</ActionLink>}
        />
        <PreferenceForm key={user.id} id={user.id} />
      </>
    )
  );
}

function PreferenceForm({ id }: { id: string }) {
  const [enabled, setEnabled] = useState(() => readPreference(id));
  const [saved, setSaved] = useState(false);
  return (
    <form
      className="panel max-w-xl space-y-4 p-6"
      onSubmit={(event) => {
        event.preventDefault();
        localStorage.setItem(`invesctictrack.preference.${id}.reminders`, enabled ? 'on' : 'off');
        setSaved(true);
      }}
    >
      <label className="flex gap-2 text-sm">
        <input
          type="checkbox"
          checked={enabled}
          onChange={(event) => {
            setEnabled(event.target.checked);
            setSaved(false);
          }}
        />
        Mostrar avisos de vencimiento en Notificaciones
      </label>
      <Button type="submit">Guardar preferencias</Button>
      <Feedback success={saved && 'Preferencias guardadas.'} />
    </form>
  );
}

export function NotificationsPage() {
  const { user } = useAuth();
  return user && <UserNotifications key={user.id} id={user.id} />;
}

function UserNotifications({ id }: { id: string }) {
  const { user } = useAuth();
  const activities = useDemoQuery(
    ['activities'],
    demoActivities.list,
    Boolean(user && config.demo && can(user, 'activity:read')),
  );
  const evaluations = useDemoQuery(
    ['evaluations'],
    demoEvaluations.list,
    Boolean(user && config.demo && can(user, 'evaluation:read')),
  );
  const proposals = useProposals(
    Boolean(user && can(user, 'proposal:update') && can(user, 'proposal:read')),
  );
  const reminders = readPreference(id);
  const readKey = `invesctictrack.preference.${id}.read`;
  const [read, setRead] = useState(() => new Set((localStorage.getItem(readKey) ?? '').split(',')));
  const [unread, setUnread] = useState(false);
  const notices = [
    ...(proposals.data ?? [])
      .filter((item) => item.ownerId === id && item.status === 'changes_requested')
      .map((item) => ({
        key: `proposal-${item.id}-${item.version}`,
        title: item.title,
        description: 'Cambios solicitados',
        path: `/propuestas/${item.id}`,
      })),
    ...(reminders
      ? (activities.data ?? []).filter(
          (item) =>
            item.assigneeId === id &&
            item.dueDate <= addDays(14) &&
            !['completed', 'cancelled'].includes(item.status),
        )
      : []
    ).map((item) => ({
      key: `task-${item.id}-${item.dueDate}`,
      title: item.title,
      description: `Actividad · ${dateLabel(item.dueDate)}`,
      path: `/actividades/${item.id}`,
    })),
    ...(reminders
      ? (evaluations.data ?? []).filter(
          (item) => item.reviewerId === id && item.status !== 'submitted' && !item.superseded,
        )
      : []
    ).map((item) => ({
      key: `review-${item.id}`,
      title: item.proposalTitle,
      description: item.dueDate ? `Evaluación · ${dateLabel(item.dueDate)}` : 'Evaluación asignada',
      path: `/evaluaciones/${item.id}/resultados`,
    })),
  ];
  const queries = [activities, evaluations, proposals].filter((query) => query.isEnabled);
  const state = queries.find((query) => query.error || query.isPending);
  const content = (
    <WorkflowList empty="No hay notificaciones en esta vista.">
      {notices
        .filter((item) => !unread || !read.has(item.key))
        .map((item) => (
          <article key={item.key} className="flex flex-wrap items-center justify-between gap-3 p-5">
            <div>
              <h2 className="font-medium">{item.title}</h2>
              <p className="text-sm text-muted">
                {item.description}
                {read.has(item.key) && ' · Leída'}
              </p>
            </div>
            <div className="flex flex-wrap gap-2">
              <ActionLink to={item.path}>Consultar</ActionLink>
              {!read.has(item.key) && (
                <Button
                  variant="secondary"
                  onClick={() => {
                    const next = new Set([...read, item.key]);
                    localStorage.setItem(readKey, [...next].slice(-300).join(','));
                    setRead(next);
                  }}
                >
                  Marcar como leída
                </Button>
              )}
            </div>
          </article>
        ))}
    </WorkflowList>
  );
  return (
    <>
      <PageHeading
        eyebrow="Mi cuenta"
        title="Notificaciones"
        description="Consulta avisos de propuestas, evaluaciones y actividades autorizadas."
        action={<ActionLink to="/perfil">Preferencias</ActionLink>}
      />
      <label className="mb-4 flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={unread}
          onChange={(event) => setUnread(event.target.checked)}
        />{' '}
        Mostrar solo pendientes de lectura
      </label>
      {state ? <WorkflowState query={state}>{content}</WorkflowState> : content}
    </>
  );
}
