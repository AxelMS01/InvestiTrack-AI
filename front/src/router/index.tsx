import { createBrowserRouter } from 'react-router-dom';
import { publicRoutes } from './public-routes';
import { protectedRoutes } from './protected-routes';
import { RouteErrorPage } from './status-pages';

export const router = createBrowserRouter([
  { errorElement: <RouteErrorPage />, children: [...publicRoutes, ...protectedRoutes] },
]);
