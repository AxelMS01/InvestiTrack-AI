import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { expect, it, vi } from 'vitest';
vi.mock('../../lib/config', () => ({ config: { demo: true, apiUrl: '/api' } }));
import { AuthProvider } from '../auth/auth-provider';
import { authApi } from '../../api/auth.api';
import { sessionKey } from '../../api/auth.queries';
import { AssistantChatPage } from './assistant-chat-page';

it('envía una consulta y permite desplegar su respaldo autorizado', async () => {
  localStorage.clear();
  const researcher = await authApi.demoLogin('researcher');
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(sessionKey, researcher);
  const user = userEvent.setup();
  render(
    <QueryClientProvider client={client}>
      <AuthProvider>
        <MemoryRouter>
          <AssistantChatPage />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
  expect(await screen.findByText('Comienza con una pregunta')).toBeInTheDocument();
  await user.type(screen.getByLabelText('Tu pregunta'), 'Resume el avance de mi departamento');
  await user.click(screen.getByRole('button', { name: 'Enviar pregunta' }));
  expect(await screen.findByText(/Respuesta simulada basada exclusivamente/)).toBeInTheDocument();
  const summary = screen.getByText(/\[1\] Ficha institucional · INV-2026-001/);
  const card = summary.closest('details');
  expect(card).not.toHaveAttribute('open');
  await user.click(summary);
  expect(card).toHaveAttribute('open');
  expect(screen.queryByText(/INV-2026-002/)).not.toBeInTheDocument();
  client.clear();
});
