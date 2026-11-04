import { z } from 'zod';
import { config } from '../lib/config';
import { ApiError, request } from './http-client';
import {
  credentialsSchema,
  userSchema,
  type Credentials,
  type Role,
  type User,
} from '../features/auth/model';

let demoUser: User | null = null;
export const demoUsers: User[] = [
  {
    id: 'u1',
    name: 'Andrea Martínez',
    email: 'andrea@institucion.edu.mx',
    role: 'admin',
    departmentId: 'environment',
  },
  {
    id: 'u2',
    name: 'Carlos Mendoza',
    email: 'carlos@institucion.edu.mx',
    role: 'coordinator',
    departmentId: 'health',
  },
  {
    id: 'u3',
    name: 'Elena Torres',
    email: 'elena@institucion.edu.mx',
    role: 'researcher',
    departmentId: 'environment',
  },
  {
    id: 'u4',
    name: 'Daniel Ruiz',
    email: 'daniel@institucion.edu.mx',
    role: 'reviewer',
    departmentId: 'engineering',
    assignedProjectIds: ['p3'],
  },
];

export const authApi = {
  me: () => (config.demo ? Promise.resolve(demoUser) : request('/auth/me', userSchema.nullable())),
  login: (credentials: Credentials) =>
    request('/auth/login', userSchema, {
      method: 'POST',
      body: JSON.stringify(credentialsSchema.parse(credentials)),
    }),
  demoLogin: (role: Role) => {
    if (!config.demo) throw new Error('El modo de demostración está desactivado.');
    const user = demoUsers.find((item) => item.role === role);
    if (!user) throw new Error('Perfil no disponible.');
    demoUser = user;
    return Promise.resolve(user);
  },
  logout: async () => {
    if (config.demo) {
      demoUser = null;
      return;
    }
    await request('/auth/logout', z.undefined(), { method: 'POST' });
  },
  users: async () => {
    if (!config.demo) return request('/users', z.array(userSchema));
    if (demoUser?.role !== 'admin')
      throw new ApiError(403, 'Solo administración puede consultar usuarios.');
    return demoUsers;
  },
};
