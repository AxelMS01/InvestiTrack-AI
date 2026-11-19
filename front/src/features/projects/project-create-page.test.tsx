import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
vi.mock('../../lib/config', () => ({ config: { demo: true, apiUrl: '/api' } }));
import { authApi } from '../../api/auth.api';
import { ProjectCreatePage } from './project-create-page';
import { projectsApi } from '../../api/projects.api';
import { AuthProvider } from '../auth/auth-provider';
import { approveProposal } from '../../test/workflow-fixtures';

describe('Conversión desde el formulario', () => {
  beforeEach(() => localStorage.clear());
  it('muestra la propuesta aprobada y navega al proyecto confirmado', async () => {
    const approved = await approveProposal();
    await authApi.demoLogin('researcher');
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <AuthProvider>
          <MemoryRouter initialEntries={['/proyectos/nuevo']}>
            <Routes>
              <Route path="/proyectos/nuevo" element={<ProjectCreatePage />} />
              <Route path="/proyectos/:id" element={<p>Ficha del proyecto creado</p>} />
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>,
    );
    const select = await screen.findByLabelText('Propuesta aprobada');
    const button = screen.getByRole('button', { name: 'Crear proyecto desde propuesta' });
    expect(button).toBeDisabled();
    await user.selectOptions(select, approved.id);
    await user.click(button);
    expect(await screen.findByText('Ficha del proyecto creado')).toBeInTheDocument();
    expect((await projectsApi.list()).some((item) => item.proposalId === approved.id)).toBe(true);
    client.clear();
  });
});
