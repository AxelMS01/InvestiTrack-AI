import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { queryClient } from './app/query-client';
import { router } from './router/index';
import { ErrorBoundary } from './components/error-boundary';
import './styles.css';
import { AuthProvider } from './features/auth/auth-provider';

const root = document.getElementById('root');
if (!root) throw new Error('No se encontró el contenedor de la aplicación.');

createRoot(root).render(
  <StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <RouterProvider router={router} />
        </AuthProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>,
);
