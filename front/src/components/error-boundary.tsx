import { Component, type ErrorInfo, type ReactNode } from 'react';

export class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  componentDidCatch(error: Error, info: ErrorInfo) {
    if (import.meta.env.DEV) console.error(error, info.componentStack);
  }
  render() {
    if (this.state.failed)
      return (
        <main className="mx-auto max-w-xl p-12">
          <h1 className="text-2xl font-semibold">Ocurrió un error inesperado</h1>
          <p className="my-4 text-muted">Recarga la aplicación para volver a intentarlo.</p>
          <button className="btn-primary" onClick={() => window.location.reload()}>
            Recargar
          </button>
        </main>
      );
    return this.props.children;
  }
}
