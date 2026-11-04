import { useAuth } from './auth-context';
import { roleLabels } from './model';
import { roleSchema } from '../../types';

export function RoleSwitcher() {
  const auth = useAuth();
  if (!auth.simulatorEnabled || !auth.user) return null;
  return (
    <div className="flex items-center gap-2">
      <label htmlFor="active-role" className="sr-only">
        Rol institucional activo
      </label>
      <select
        id="active-role"
        className="input max-w-40 text-xs"
        disabled={auth.switching}
        value={auth.user.role}
        onChange={(event) => auth.switchRole(roleSchema.parse(event.target.value))}
      >
        {roleSchema.options.map((role) => (
          <option key={role} value={role}>
            {roleLabels[role]}
          </option>
        ))}
      </select>
      {auth.switchError && (
        <p role="alert" className="field-error">
          {auth.switchError.message}
        </p>
      )}
    </div>
  );
}
