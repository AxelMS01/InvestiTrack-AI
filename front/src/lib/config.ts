// Demo is enabled explicitly; an absent variable defaults to the real API.
export const config = {
  demo: import.meta.env.VITE_DEMO_MODE === 'true',
  apiUrl: (import.meta.env.VITE_API_URL || '/api').replace(/\/$/, ''),
};
