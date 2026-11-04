import { AuthProvider } from '../features/auth/auth-provider';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { expect, it } from 'vitest';
import { sessionKey } from '../api/auth.queries';
import { DashboardLayout } from './dashboard-layout';

it('permite colapsar y volver a expandir la barra lateral de escritorio', async () => {
  const client = new QueryClient();
  client.setQueryData(sessionKey, {
    id: 'u1',
    name: 'Andrea Martínez',
    email: 'andrea@example.com',
    role: 'admin',
  });
  const user = userEvent.setup();
  render(
    <QueryClientProvider client={client}>
      <AuthProvider>
        <MemoryRouter>
          <Routes>
            <Route element={<DashboardLayout />}>
              <Route index element={<p>Contenido institucional</p>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
  await user.click(screen.getByRole('button', { name: 'Colapsar barra lateral' }));
  expect(screen.getByRole('button', { name: 'Expandir barra lateral' })).toHaveAttribute(
    'aria-expanded',
    'false',
  );
  expect(screen.getByLabelText('Navegación principal')).toHaveClass('lg:hidden');
  expect(screen.getByText('Contenido institucional')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Expandir barra lateral' }));
  expect(screen.getByLabelText('Navegación principal')).not.toHaveClass('lg:hidden');
  client.clear();
});
