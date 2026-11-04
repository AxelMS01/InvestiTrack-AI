import { Button } from '../../components/button';
import { Input } from '../../components/input';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useNavigate } from 'react-router-dom';
import { Save } from 'lucide-react';
import { BackLink, PageHeading } from '../../components/ui';
import { areas, projectInputSchema, type ProjectInput } from '../../types/project';
import { useCreateProject } from '../../api/projects.queries';

export function ProjectCreatePage() {
  const navigate = useNavigate();
  const mutation = useCreateProject();
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ProjectInput>({
    resolver: zodResolver(projectInputSchema),
    defaultValues: {
      title: '',
      description: '',
      area: 'Ciencias ambientales',
      budget: 0,
      startDate: '',
      endDate: '',
    },
  });
  const fieldProps = (key: keyof ProjectInput) => ({
    'aria-invalid': Boolean(errors[key]),
    'aria-describedby': errors[key] ? `${key}-error` : undefined,
  });
  const error = (key: keyof ProjectInput) =>
    errors[key] && (
      <p id={`${key}-error`} className="field-error">
        {errors[key]?.message}
      </p>
    );
  return (
    <>
      <BackLink />
      <PageHeading
        eyebrow="De la idea a la investigación"
        title="Nuevo proyecto"
        description="Registra la información inicial de tu proyecto. Los campos marcados con * son obligatorios."
      />
      <form
        className="grid items-start gap-6 xl:grid-cols-[1fr_300px]"
        noValidate
        onSubmit={handleSubmit(async (values) => {
          const project = await mutation.mutateAsync(values).catch(() => null);
          if (project) void navigate(`/proyectos/${project.id}`);
        })}
      >
        <section className="panel p-6 sm:p-8">
          <h2 className="section-title">Información del proyecto</h2>
          <p className="mt-2 text-xs text-muted">Una descripción clara es el primer paso.</p>
          <fieldset disabled={mutation.isPending} className="mt-7 space-y-6">
            <legend className="sr-only">Datos de registro</legend>
            <div>
              <label className="field-label" htmlFor="title">
                Título del proyecto *
              </label>
              <Input
                id="title"
                className="input"
                placeholder="¿Qué quieres investigar?"
                maxLength={160}
                {...register('title')}
                {...fieldProps('title')}
              />
              {error('title')}
            </div>
            <div>
              <label className="field-label" htmlFor="description">
                Descripción y objetivo *
              </label>
              <textarea
                id="description"
                rows={5}
                className="input resize-y"
                placeholder="Describe el problema, el objetivo y el impacto esperado…"
                maxLength={3000}
                {...register('description')}
                {...fieldProps('description')}
              />
              {error('description')}
            </div>
            <div className="grid gap-6 sm:grid-cols-2">
              <div>
                <label className="field-label" htmlFor="project-area">
                  Línea de investigación *
                </label>
                <select
                  id="project-area"
                  className="input"
                  {...register('area')}
                  {...fieldProps('area')}
                >
                  {areas.map((area) => (
                    <option key={area}>{area}</option>
                  ))}
                </select>
                {error('area')}
              </div>
              <div>
                <label className="field-label" htmlFor="budget">
                  Presupuesto estimado (MXN) *
                </label>
                <Input
                  id="budget"
                  type="number"
                  min={0}
                  max={1000000000}
                  step="0.01"
                  className="input"
                  {...register('budget', { valueAsNumber: true })}
                  {...fieldProps('budget')}
                />
                {error('budget')}
              </div>
              <div>
                <label className="field-label" htmlFor="startDate">
                  Fecha de inicio *
                </label>
                <Input
                  id="startDate"
                  type="date"
                  className="input"
                  {...register('startDate')}
                  {...fieldProps('startDate')}
                />
                {error('startDate')}
              </div>
              <div>
                <label className="field-label" htmlFor="endDate">
                  Fecha de cierre *
                </label>
                <Input
                  id="endDate"
                  type="date"
                  className="input"
                  {...register('endDate')}
                  {...fieldProps('endDate')}
                />
                {error('endDate')}
              </div>
            </div>
          </fieldset>
          {mutation.error && (
            <p role="alert" className="mt-6 rounded-lg bg-red-50 p-4 text-sm text-red-800">
              {mutation.error.message}
            </p>
          )}
          <div className="mt-8 flex flex-wrap justify-end gap-3 border-t border-line pt-6">
            <Link to="/proyectos" className="btn-secondary">
              Cancelar
            </Link>
            <Button type="submit" className="btn-primary" disabled={mutation.isPending}>
              <Save size={16} />
              {mutation.isPending ? 'Guardando…' : 'Registrar proyecto'}
            </Button>
          </div>
        </section>
        <aside className="rounded-xl border border-accent/15 bg-sage/50 p-6">
          <p className="eyebrow">Antes de comenzar</p>
          <h2 className="mt-3 text-lg font-semibold">Toda idea tiene un punto de partida.</h2>
          <p className="mt-4 text-sm leading-7 text-muted">
            El proyecto se registrará en estado{' '}
            <strong className="font-medium text-forest">En planeación</strong> y tu perfil quedará
            asignado como responsable.
          </p>
          <p className="mt-4 border-t border-accent/10 pt-4 text-xs leading-6 text-muted">
            Revisa el periodo y el presupuesto antes de guardar. Esta información alimenta el
            portafolio institucional.
          </p>
        </aside>
      </form>
    </>
  );
}
