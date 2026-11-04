import type { ComponentProps } from 'react';

export function Table({ className = '', ...props }: ComponentProps<'table'>) {
  return (
    <div className="overflow-x-auto">
      <table className={`w-full text-left text-sm ${className}`} {...props} />
    </div>
  );
}
