import { useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useDemoQuery, useWorkflowMutation } from '../../api/demo.queries';
import { authApi } from '../../api/auth.api';
import { Button } from '../../components/button';
import { Input } from '../../components/input';
import { PageHeading } from '../../components/ui';
import { accountInputSchema, demoAdministration } from '../../demo/administration';
import { departmentLabels, departmentSchema } from '../../types/department';
import { accountStatusLabels, institutionalRoles, roleSchema, userRoles } from '../../types/user';
import type { User } from '../../types';
import { useAuth } from '../auth/auth-context';
import { can, roleLabels } from '../auth/model';
import { ActionLink, Feedback, Field, WorkflowList, WorkflowState } from '../shared/workflow-ui';

export function UserCreatePage() {
  return (
    <>
      <PageHeading
        eyebrow="Usuarios"
        title="Crear cuenta"
        description="Registra una cuenta pendiente para asignarle roles y activarla."
        action={<ActionLink to="/usuarios">Volver a usuarios</ActionLink>}
      />
      <UserForm />
    </>
  );
}

export function UserDetailPage() {
  const { id = '' } = useParams();
  const { user } = useAuth();
  const query = useDemoQuery(['user', id], () => demoAdministration.detail(id));
  return (
    <WorkflowState query={query}>
      {query.data && (
        <>
          <PageHeading
            eyebrow="Usuarios"
            title={query.data.name}
            description={`${query.data.email} · ${accountStatusLabels[query.data.status ?? 'ACTIVE']}`}
            action={<ActionLink to="/usuarios">Volver a usuarios</ActionLink>}
          />
          <div className="mb-4 flex flex-wrap items-center gap-3">
            <p className="text-sm">
              Roles ·{' '}
              {userRoles(query.data)
                .map((role) => roleLabels[role])
                .join(', ')}
            </p>
            {user && user.id !== id && can(user, 'role:assign') && (
              <ActionLink to={`/administracion/roles?usuario=${id}`}>Asignar roles</ActionLink>
            )}
          </div>
          {user && can(user, 'user:update') && <UserForm key={id} account={query.data} />}
          {user && user.id !== id && can(user, 'user:deactivate') && (
            <StatusForm id={id} active={query.data.status === 'ACTIVE'} />
          )}
        </>
      )}
    </WorkflowState>
  );
}

function UserForm({ account }: { account?: User }) {
  const mutation = useWorkflowMutation(demoAdministration.save);
  const [error, setError] = useState('');
  const navigate = useNavigate();
  return (
    <form
      className="panel max-w-3xl space-y-4 p-6"
      onSubmit={async (event) => {
        event.preventDefault();
        const fields = new FormData(event.currentTarget);
        const profile = accountInputSchema.safeParse({
          ...Object.fromEntries(fields),
          departmentId: fields.get('departmentId') || undefined,
        });
        if (!profile.success) {
          setError('Revisa el nombre, el correo y el departamento.');
          return;
        }
        setError('');
        const saved = await mutation
          .mutateAsync({ id: account?.id, profile: profile.data })
          .catch(() => null);
        if (saved && !account) void navigate(`/usuarios/${saved.id}`);
      }}
    >
      <Field label="Nombre completo">
        <Input name="name" required minLength={3} maxLength={160} defaultValue={account?.name} />
      </Field>
      <Field label="Correo institucional">
        <Input name="email" type="email" required defaultValue={account?.email} />
      </Field>
      <Field label="Departamento">
        <select className="input" name="departmentId" defaultValue={account?.departmentId ?? ''}>
          <option value="">Sin asignar</option>
          {departmentSchema.options.map((id) => (
            <option value={id} key={id}>
              {departmentLabels[id]}
            </option>
          ))}
        </select>
      </Field>
      {error && (
        <p className="field-error" role="alert">
          {error}
        </p>
      )}
      <Feedback error={mutation.error} success={mutation.isSuccess && 'Cuenta guardada.'} />
      <Button type="submit" disabled={mutation.isPending}>
        {account ? 'Guardar cambios' : 'Crear cuenta pendiente'}
      </Button>
    </form>
  );
}

function StatusForm({ id, active }: { id: string; active: boolean }) {
  const mutation = useWorkflowMutation(demoAdministration.changeStatus);
  return (
    <form
      className="panel mt-5 max-w-3xl space-y-4 p-6"
      onSubmit={(event) => {
        event.preventDefault();
        const fields = new FormData(event.currentTarget);
        mutation.mutate({
          id,
          status: active ? 'INACTIVE' : 'ACTIVE',
          reason: String(fields.get('reason') ?? ''),
        });
      }}
    >
      <h2 className="section-title">{active ? 'Desactivar cuenta' : 'Activar cuenta'}</h2>
      <Field label="Motivo del cambio">
        <Input name="reason" minLength={5} maxLength={400} required />
      </Field>
      <label className="flex gap-2 text-sm">
        <input type="checkbox" required />
        Confirmo el cambio de estado de esta cuenta.
      </label>
      <Feedback
        error={mutation.error}
        success={mutation.isSuccess && 'Estado de la cuenta actualizado.'}
      />
      <Button type="submit" disabled={mutation.isPending}>
        {active ? 'Desactivar' : 'Activar'}
      </Button>
    </form>
  );
}

export function RoleAssignmentPage() {
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();
  const id = params.get('usuario') ?? '';
  const query = useDemoQuery(['role-directory'], authApi.users);
  const selected = query.data?.find((item) => item.id === id && item.id !== user?.id);
  return (
    <>
      <PageHeading
        eyebrow="Administración"
        title="Asignación de roles"
        description="Selecciona una cuenta para asignarle roles institucionales."
        action={<ActionLink to="/usuarios">Volver a usuarios</ActionLink>}
      />
      <WorkflowState query={query}>
        <div className="panel mb-5 max-w-3xl p-6">
          <Field label="Cuenta">
            <select
              className="input"
              value={selected?.id ?? ''}
              onChange={(event) =>
                setParams(event.target.value ? { usuario: event.target.value } : {})
              }
            >
              <option value="">Selecciona una cuenta</option>
              {query.data
                ?.filter((item) => item.id !== user?.id)
                .map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.name} · {item.email}
                  </option>
                ))}
            </select>
          </Field>
        </div>
        {selected && <RoleForm key={selected.id} account={selected} />}
      </WorkflowState>
    </>
  );
}

function RoleForm({ account }: { account: User }) {
  const mutation = useWorkflowMutation(demoAdministration.assignRoles);
  return (
    <form
      className="panel max-w-3xl space-y-4 p-6"
      onSubmit={(event) => {
        event.preventDefault();
        mutation.mutate({
          id: account.id,
          roles: new FormData(event.currentTarget).getAll('role').map(String),
        });
      }}
    >
      <fieldset className="space-y-3">
        <legend className="section-title mb-3">Roles de {account.name}</legend>
        {roleSchema.options.map((role) => (
          <label className="flex gap-2 text-sm" key={role}>
            <input
              type="checkbox"
              name="role"
              value={institutionalRoles[role]}
              defaultChecked={userRoles(account).includes(role)}
            />
            {roleLabels[role]}
          </label>
        ))}
      </fieldset>
      <Feedback error={mutation.error} success={mutation.isSuccess && 'Roles actualizados.'} />
      <Button type="submit" disabled={mutation.isPending}>
        Guardar roles
      </Button>
    </form>
  );
}

export function AuditPage() {
  const query = useDemoQuery(['audit'], demoAdministration.audit);
  return (
    <>
      <PageHeading
        eyebrow="Administración"
        title="Auditoría de accesos"
        description="Consulta cambios de cuenta y asignaciones de roles."
        action={<ActionLink to="/usuarios">Volver a usuarios</ActionLink>}
      />
      <WorkflowState query={query}>
        <WorkflowList empty="Todavía no se han registrado cambios de acceso.">
          {(query.data ?? []).map((item) => (
            <article className="space-y-2 p-5" key={item.id}>
              <h2 className="font-medium">{item.action}</h2>
              <p className="text-sm text-muted">
                {item.actor} → {item.target} · {new Date(item.at).toLocaleString('es-MX')}
              </p>
            </article>
          ))}
        </WorkflowList>
      </WorkflowState>
    </>
  );
}
