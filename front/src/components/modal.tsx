import { useEffect, useId, useRef, type ReactNode } from 'react';
import { X } from 'lucide-react';
import { Button } from './button';

export function Modal({
  open,
  onClose,
  title,
  children,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const dialog = ref.current;
    if (open && dialog && !dialog.open) dialog.showModal();
    if (!open && dialog?.open) dialog.close();
  }, [open]);
  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      onClose={() => {
        if (open) onClose();
      }}
      className="m-auto w-[calc(100%_-_2rem)] max-w-lg rounded-xl border border-line bg-white p-6 text-ink shadow-xl backdrop:bg-black/40"
    >
      <div className="mb-5 flex items-center justify-between gap-4">
        <h2 id={titleId} className="section-title">
          {title}
        </h2>
        <Button variant="secondary" aria-label="Cerrar diálogo" onClick={onClose}>
          <X size={16} />
        </Button>
      </div>
      {children}
    </dialog>
  );
}
