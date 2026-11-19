import { z } from 'zod';
import { accountStatusSchema, institutionalRoleSchema, userSchema } from '../types/user';
import { departmentSchema } from '../types/department';
import { fail, requireDemoUser } from './access';
import { readDatabase, updateDatabase, type DemoDatabase } from './database';

export const accountInputSchema = z.object({
  name: z.string().trim().min(3).max(160),
  email: z.email(),
  departmentId: departmentSchema.optional(),
});
function log(data: DemoDatabase, actorId: string, targetId: string, action: string) {
  data.audit.unshift({
    id: crypto.randomUUID(),
    actorId,
    targetId,
    action,
    at: new Date().toISOString(),
  });
}
function account(data: DemoDatabase, id: string) {
  return data.users.find((user) => user.id === id) ?? fail(404, 'No se encontró la cuenta.');
}

export const demoAdministration = {
  detail(id: string) {
    requireDemoUser('user:read');
    return account(readDatabase(), id);
  },
  save(input: { id?: string; profile: z.infer<typeof accountInputSchema> }) {
    const actor = requireDemoUser(input.id ? 'user:update' : 'user:create');
    const parsed = accountInputSchema.safeParse(input.profile);
    if (!parsed.success) fail(422, 'Revisa el nombre, el correo y el departamento.');
    return updateDatabase((data) => {
      const profile = { ...parsed.data, email: parsed.data.email.toLowerCase() };
      if (
        data.users.some(
          (user) => user.id !== input.id && user.email.toLowerCase() === profile.email,
        )
      )
        fail(409, 'Ya existe una cuenta con ese correo.');
      const user = input.id
        ? account(data, input.id)
        : userSchema.parse({
            ...profile,
            id: crypto.randomUUID(),
            roles: ['STUDENT'],
            status: 'PENDING',
          });
      Object.assign(user, { ...profile, departmentId: profile.departmentId });
      if (!input.id) data.users.push(user);
      log(data, actor.id, user.id, input.id ? 'Perfil actualizado' : 'Cuenta pendiente creada');
      return user;
    });
  },
  assignRoles(input: { id: string; roles: string[] }) {
    const actor = requireDemoUser('role:assign');
    const roles = z.array(institutionalRoleSchema).min(1).safeParse(input.roles);
    if (!roles.success) fail(422, 'Elige al menos un rol institucional.');
    if (actor.id === input.id) fail(403, 'Otra persona administradora debe actualizar tus roles.');
    return updateDatabase((data) => {
      const user = account(data, input.id);
      const updated = userSchema.parse({ ...user, roles: roles.data });
      data.users[data.users.indexOf(user)] = updated;
      log(data, actor.id, user.id, `Roles asignados · ${updated.roles.join(', ')}`);
      return updated;
    });
  },
  changeStatus(input: { id: string; status: string; reason: string }) {
    const actor = requireDemoUser('user:deactivate');
    const status = accountStatusSchema.safeParse(input.status);
    if (!status.success || status.data === 'PENDING' || input.reason.trim().length < 5)
      fail(422, 'Indica un motivo de al menos 5 caracteres.');
    if (actor.id === input.id) fail(403, 'Otra persona administradora debe modificar tu cuenta.');
    return updateDatabase((data) => {
      const user = account(data, input.id);
      if (user.status === status.data) fail(409, 'La cuenta ya tiene ese estado.');
      user.status = status.data;
      log(
        data,
        actor.id,
        user.id,
        `${status.data === 'ACTIVE' ? 'Cuenta activada' : 'Cuenta desactivada'} · ${input.reason.trim()}`,
      );
      return user;
    });
  },
  audit() {
    requireDemoUser('audit:read');
    const data = readDatabase();
    return data.audit.map((entry) => ({
      ...entry,
      actor: data.users.find((user) => user.id === entry.actorId)?.name ?? entry.actorId,
      target: data.users.find((user) => user.id === entry.targetId)?.name ?? entry.targetId,
    }));
  },
};
