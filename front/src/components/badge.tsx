import type { ComponentProps } from 'react';

export function Badge({ className = '', ...props }: ComponentProps<'span'>) {
  return <span className={`status ${className}`} {...props} />;
}
