import { useNavigate } from 'react-router-dom';
import { demoUsers } from '../../demo/database';
import { useAuth } from './auth-context';
import { roleLabels } from './model';
import { roleSchema, userRoles, type Role } from '../../types/user';

export function RoleSwitcher({ workspace }: { workspace?: Role }) {
  const auth = useAuth();
  const navigate = useNavigate();
  if (!auth.user) return null;
  const roles = userRoles(auth.user);
  return (
    <div className="flex flex-wrap items-center justify-end gap-2">
      {roles.length > 1 && (
        <label className="flex flex-col text-[10px] text-muted">
          Espacio de trabajo
          <select
            className="input max-w-36 text-xs"
            value={workspace ?? auth.workspace ?? auth.user.role}
            onChange={(event) => {
              auth.switchWorkspace?.(roleSchema.parse(event.target.value));
              void navigate('/');
            }}
          >
            {roles.map((role) => (
              <option key={role} value={role}>
                {roleLabels[role]}
              </option>
            ))}
          </select>
        </label>
      )}
      {auth.simulatorEnabled && (
        <label className="flex flex-col text-[10px] text-muted">
          Simulador de perfiles
          <select
            className="input max-w-36 text-xs"
            disabled={auth.switching}
            value={demoUsers.find((item) => item.id === auth.user?.id)?.role ?? auth.user.role}
            onChange={(event) => auth.switchRole(roleSchema.parse(event.target.value))}
          >
            {roleSchema.options.map((role) => (
              <option key={role} value={role}>
                {roleLabels[role]}
              </option>
            ))}
          </select>
        </label>
      )}
      {auth.switchError && (
        <p role="alert" className="field-error">
          {auth.switchError.message}
        </p>
      )}
    </div>
  );
}
