import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { expect, it, vi } from 'vitest';
vi.mock('../../lib/config', () => ({ config: { demo: true, apiUrl: '/api' } }));
import { AuthProvider } from './auth-provider';
import { RoleSwitcher } from './role-switcher';
import { RequireAuth } from '../../router/require-auth';
import { authApi } from '../../api/auth.api';
import { sessionKey } from '../../api/auth.queries';

it('revalida rutas y descarta caché del administrador al alternar al investigador', async () => {
  const user = userEvent.setup();
  const admin = await authApi.demoLogin('admin');
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(sessionKey, admin);
  client.setQueryData(['confidential'], 'Datos administrativos');
  render(
    <QueryClientProvider client={client}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/usuarios']}>
          <RoleSwitcher />
          <Routes>
            <Route element={<RequireAuth roles={['admin']} />}>
              <Route path="/usuarios" element={<p>Administración protegida</p>} />
            </Route>
            <Route path="/sin-acceso" element={<p>Acceso denegado por rol</p>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
  expect(await screen.findByText('Administración protegida')).toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText('Rol institucional activo'), 'researcher');
  expect(await screen.findByText('Acceso denegado por rol')).toBeInTheDocument();
  expect(screen.queryByText('Administración protegida')).not.toBeInTheDocument();
  expect(client.getQueryData(['confidential'])).toBeUndefined();
  expect(screen.getByLabelText('Rol institucional activo')).toHaveValue('researcher');
  client.clear();
});
