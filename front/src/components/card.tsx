import type { ComponentProps } from 'react';

export function Card({ className = '', ...props }: ComponentProps<'section'>) {
  return <section className={`panel ${className}`} {...props} />;
}
