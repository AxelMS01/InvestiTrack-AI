import { config } from '../lib/config';
import { ApiError, request } from './http-client';
import { can, userSchema, type Role, type User } from '../features/auth/model';
import { accessToken, endSession } from './oidc';
import { readAll } from './institutional-workflow.api';
import { demoUsers, readDatabase } from '../demo/database';
export { demoUsers } from '../demo/database';

let demoUserId: string | null = null;
let demoGeneration = 0;
export function demoSessionGeneration() {
  return demoGeneration;
}

export function currentDemoUser(): User | null {
  if (!config.demo || !demoUserId) return null;
  return (
    readDatabase().users.find((user) => user.id === demoUserId && user.status === 'ACTIVE') ?? null
  );
}

export const demoProfiles = demoUsers.map((user, index) => ({
  value: index < 5 ? user.role : user.id,
  id: user.id,
  name: user.name,
  role: user.role,
}));

export const authApi = {
  me: async (): Promise<User | null> => {
    if (config.demo) return currentDemoUser();
    if (!(await accessToken())) return null;
    const user = await request('/me', userSchema);
    // UI capabilities are presentation-level and use different names than Identity's policy catalog.
    return { ...user, capabilities: undefined };
  },
  demoLogin: async (profileId: Role | string): Promise<User> => {
    if (!config.demo) throw new Error('El modo de demostración está desactivado.');
    const profile = demoProfiles.find((item) => item.value === profileId);
    const user = readDatabase().users.find(
      (item) => item.id === profile?.id && item.status === 'ACTIVE',
    );
    if (!user) throw new Error('Perfil no disponible.');
    demoUserId = user.id;
    demoGeneration++;
    return user;
  },
  logout: async () => {
    if (config.demo) {
      demoUserId = null;
      demoGeneration++;
      return;
    }
    await endSession();
  },
  users: async (): Promise<User[]> => {
    if (!config.demo) return readAll('/users', userSchema);
    const user = currentDemoUser();
    if (!user || !can(user, 'user:read'))
      throw new ApiError(403, 'Solo administración puede consultar usuarios.');
    return readDatabase().users;
  },
};
