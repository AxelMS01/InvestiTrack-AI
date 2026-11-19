import { z } from 'zod';
import { config } from '../lib/config';
import { ApiError, request } from './http-client';
import {
  credentialsSchema,
  can,
  userSchema,
  type Credentials,
  type Role,
  type User,
} from '../features/auth/model';
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

export const authApi = {
  me: async (): Promise<User | null> =>
    config.demo ? currentDemoUser() : request('/auth/me', userSchema.nullable()),
  login: async (credentials: Credentials) => {
    if (config.demo)
      throw new ApiError(403, 'Selecciona un perfil de demostración para iniciar sesión.');
    return request('/auth/login', userSchema, {
      method: 'POST',
      body: JSON.stringify(credentialsSchema.parse(credentials)),
    });
  },
  demoLogin: async (role: Role): Promise<User> => {
    if (!config.demo) throw new Error('El modo de demostración está desactivado.');
    const profile = demoUsers.find((item) => item.role === role);
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
    await request('/auth/logout', z.undefined(), { method: 'POST' });
  },
  users: async (): Promise<User[]> => {
    if (!config.demo) return request('/users', z.array(userSchema));
    const user = currentDemoUser();
    if (!user || !can(user, 'user:read'))
      throw new ApiError(403, 'Solo administración puede consultar usuarios.');
    return readDatabase().users;
  },
};
