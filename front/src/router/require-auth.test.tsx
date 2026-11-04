import { AuthProvider } from '../features/auth/auth-provider';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { RequireAuth } from './require-auth';
import { authApi } from '../api/auth.api';
import { permissions, type User } from '../features/auth/model';
import { ApiError } from '../api/http-client';

function renderRoute(user: User | null | ApiError, pathname = '/usuarios') {
  const session = vi.spyOn(authApi, 'me');
  if (user instanceof ApiError) session.mockRejectedValue(user);
  else session.mockResolvedValue(user);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AuthProvider>
        <MemoryRouter initialEntries={[pathname]}>
          <Routes>
            <Route path="/login" element={<p>Acceso de sesión</p>} />
            <Route path="/sesion-expirada" element={<p>Sesión expirada</p>} />
            <Route path="/sin-acceso" element={<p>Acceso denegado</p>} />
            <Route element={<RequireAuth roles={permissions.manageUsers} />}>
              <Route path="/usuarios" element={<p>Directorio administrativo</p>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
}
describe('Protección de rutas', () => {
  it('muestra la página 401 cuando el servidor informa que la sesión expiró', async () => {
    renderRoute(new ApiError(401, 'Sesión expirada'));
    expect(await screen.findByText('Sesión expirada')).toBeInTheDocument();
  });
  it('redirige al login cuando no existe sesión', async () => {
    renderRoute(null);
    expect(await screen.findByText('Acceso de sesión')).toBeInTheDocument();
  });
  it('bloquea el acceso directo de un investigador a administración', async () => {
    renderRoute({ id: 'u3', name: 'Elena Torres', email: 'elena@example.com', role: 'researcher' });
    expect(await screen.findByText('Acceso denegado')).toBeInTheDocument();
    expect(screen.queryByText('Directorio administrativo')).not.toBeInTheDocument();
  });
  it('permite consultar administración con el rol correcto', async () => {
    renderRoute({ id: 'u1', name: 'Andrea Martínez', email: 'andrea@example.com', role: 'admin' });
    expect(await screen.findByText('Directorio administrativo')).toBeInTheDocument();
  });
});
