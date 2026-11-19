import { useFieldArray, useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useBeforeUnload, useBlocker, useNavigate, useParams } from 'react-router-dom';
import { Plus, Trash2, Send } from 'lucide-react';
import { useCreateProposal, useProposal } from '../../api/proposals.queries';
import { Button } from '../../components/button';
import { Input } from '../../components/input';
import { AsyncState, PageHeading } from '../../components/ui';
import { currency } from '../../lib/format';
import { areas } from '../../types/project';
import { proposalInputSchema, type ProposalInput } from './schema';
import { type RegisteredProposal } from '../../types/proposal';
import { canEditProposal } from '../../demo/access';
import { useAuth } from '../auth/auth-context';
import { useCallback, useRef } from 'react';

export function ProposalCreatePage() {
  return <ProposalForm />;
}
export function ProposalEditPage() {
  const { id = '' } = useParams();
  const query = useProposal(id);
  const { user } = useAuth();
  if (query.isPending) return <AsyncState loading />;
  if (query.error) return <AsyncState error={query.error} retry={() => void query.refetch()} />;
  if (!user || !canEditProposal(user, query.data))
    return <AsyncState error={new Error('Esta propuesta no se puede editar con tu cuenta.')} />;
  return <ProposalForm key={query.data.id} proposal={query.data} />;
}
function ProposalForm({ proposal }: { proposal?: RegisteredProposal }) {
  const navigate = useNavigate();
  const create = useCreateProposal();
  const {
    register,
    control,
    handleSubmit,
    getValues,
    reset,
    formState: { errors, isDirty },
  } = useForm<ProposalInput>({
    resolver: zodResolver(proposalInputSchema),
    defaultValues: proposal?.details ?? {
      title: '',
      abstract: '',
      objective: '',
      methodology: '',
      area: 'Ciencias ambientales',
      startDate: '',
      endDate: '',
      team: [{ name: '', email: '', contribution: '' }],
      budgetItems: [{ concept: '', category: 'materials', amount: 0 }],
      requestedFunding: 0,
      involvesParticipants: false,
      ethicsApproval: false,
      declaration: false,
    },
  });
  const navigatesAfterSave = useRef(false);
  const canEditField = (name: keyof ProposalInput) =>
    proposal?.status !== 'changes_requested' || proposal.allowedFields.includes(name);
  useBlocker(({ currentLocation, nextLocation }) => {
    if (
      !isDirty ||
      navigatesAfterSave.current ||
      currentLocation.pathname === nextLocation.pathname
    )
      return false;
    return !window.confirm('¿Salir sin guardar los cambios?');
  });
  useBeforeUnload(
    useCallback(
      (event) => {
        if (!isDirty || navigatesAfterSave.current) return;
        event.preventDefault();
        event.returnValue = '';
      },
      [isDirty],
    ),
  );
  async function save(values: ProposalInput, review: boolean) {
    const normalized = {
      ...values,
      requestedFunding: Number.isFinite(values.requestedFunding) ? values.requestedFunding : 0,
      budgetItems: values.budgetItems.map((item) => ({
        ...item,
        amount: Number.isFinite(item.amount) ? item.amount : 0,
      })),
    };
    const result = await create
      .mutateAsync({ details: normalized, id: proposal?.id, version: proposal?.version })
      .catch(() => null);
    if (!result) return;
    navigatesAfterSave.current = true;
    reset(result.details);
    void navigate(`/propuestas/${result.id}${review ? '/revision' : ''}`, {
      state: { saved: true },
    });
  }
  const team = useFieldArray({ control, name: 'team' });
  const budget = useFieldArray({ control, name: 'budgetItems' });
  const amounts = useWatch({ control, name: 'budgetItems' });
  const participants = useWatch({ control, name: 'involvesParticipants' });
  const total =
    amounts.reduce(
      (sum, item) => sum + (Number.isFinite(item.amount) ? Math.round(item.amount * 100) : 0),
      0,
    ) / 100;
  const error = (id: string, message?: string) =>
    message ? (
      <p id={`${id}-error`} className="field-error">
        {message}
      </p>
    ) : null;
  const field = (id: string, invalid: boolean) => ({
    'aria-invalid': invalid,
    'aria-describedby': invalid ? `${id}-error` : undefined,
  });
  return (
    <>
      <PageHeading
        eyebrow="Propuestas · Registro científico"
        title={proposal ? 'Editar propuesta' : 'Crear propuesta'}
        description="Guarda tus avances como borrador y revisa el protocolo antes de enviarlo a coordinación."
      />
      {proposal?.status === 'changes_requested' && (
        <p className="panel mb-5 p-4 text-sm">
          {
            proposal.history
              .slice()
              .reverse()
              .find((event) => event.status === 'changes_requested')?.note
          }
        </p>
      )}
      {isDirty && (
        <p role="status" className="mb-3 text-sm text-muted">
          Tienes cambios sin guardar.
        </p>
      )}
      <form
        noValidate
        onSubmit={(event) => void handleSubmit((values) => save(values, true))(event)}
        className="space-y-6"
      >
        <fieldset disabled={create.isPending} className="panel space-y-6 p-6">
          <legend className="px-2 text-sm font-semibold">1. Protocolo de investigación</legend>
          <div>
            <label className="field-label" htmlFor="proposal-title">
              Título de la propuesta
            </label>
            <Input
              id="proposal-title"
              readOnly={!canEditField('title')}
              {...register('title')}
              {...field('proposal-title', Boolean(errors.title))}
            />
            {error('proposal-title', errors.title?.message)}
          </div>
          <div>
            <label htmlFor="proposal-area" className="field-label">
              Línea de investigación
            </label>
            <select
              id="proposal-area"
              className="input"
              {...register('area')}
              disabled={!canEditField('area')}
            >
              {areas.map((area) => (
                <option key={area}>{area}</option>
              ))}
            </select>
          </div>
          {(['abstract', 'objective', 'methodology'] as const).map((name) => (
            <div key={name}>
              <label htmlFor={name} className="field-label">
                {
                  {
                    abstract: 'Resumen científico',
                    objective: 'Objetivo general',
                    methodology: 'Metodología',
                  }[name]
                }
              </label>
              <textarea
                id={name}
                rows={name === 'objective' ? 3 : 4}
                className="input"
                {...register(name)}
                readOnly={!canEditField(name)}
                {...field(name, Boolean(errors[name]))}
              />
              {error(name, errors[name]?.message)}
            </div>
          ))}
          <div className="grid gap-5 sm:grid-cols-2">
            {(['startDate', 'endDate'] as const).map((name) => (
              <div key={name}>
                <label htmlFor={name} className="field-label">
                  {name === 'startDate' ? 'Fecha de inicio' : 'Fecha de cierre'}
                </label>
                <Input
                  id={name}
                  type="date"
                  {...register(name)}
                  readOnly={!canEditField(name)}
                  {...field(name, Boolean(errors[name]))}
                />
                {error(name, errors[name]?.message)}
              </div>
            ))}
          </div>
        </fieldset>
        <fieldset
          disabled={create.isPending || !canEditField('team')}
          className="panel space-y-5 p-6"
        >
          <legend className="px-2 text-sm font-semibold">2. Equipo colaborador</legend>
          <p className="text-xs leading-6 text-muted">
            El responsable se asigna desde tu sesión. Agrega entre 1 y 10 colaboradores con correos
            únicos.
          </p>
          {team.fields.map((member, index) => (
            <div key={member.id} className="rounded-lg border border-line p-4">
              <div className="mb-4 flex items-center justify-between">
                <h2 className="text-sm font-medium">Colaborador {index + 1}</h2>
                <Button
                  variant="secondary"
                  disabled={team.fields.length === 1}
                  aria-label={`Eliminar colaborador ${index + 1}`}
                  onClick={() => team.remove(index)}
                >
                  <Trash2 size={15} />
                </Button>
              </div>
              <div className="grid gap-4 sm:grid-cols-2">
                {(['name', 'email', 'contribution'] as const).map((name) => {
                  const id = `team-${index}-${name}`;
                  const message = errors.team?.[index]?.[name]?.message;
                  return (
                    <div key={name} className={name === 'contribution' ? 'sm:col-span-2' : ''}>
                      <label htmlFor={id} className="field-label">
                        {
                          {
                            name: 'Nombre completo',
                            email: 'Correo del colaborador',
                            contribution: 'Contribución al proyecto',
                          }[name]
                        }{' '}
                        {index + 1}
                      </label>
                      <Input
                        id={id}
                        type={name === 'email' ? 'email' : 'text'}
                        {...register(`team.${index}.${name}`)}
                        {...field(id, Boolean(message))}
                      />
                      {error(id, message)}
                    </div>
                  );
                })}
              </div>
            </div>
          ))}
          <Button
            variant="secondary"
            onClick={() => team.append({ name: '', email: '', contribution: '' })}
            disabled={team.fields.length >= 10}
          >
            <Plus size={15} />
            Agregar colaborador
          </Button>
        </fieldset>
        <fieldset disabled={create.isPending} className="panel space-y-5 p-6">
          <legend className="px-2 text-sm font-semibold">3. Presupuesto desglosado · MXN</legend>
          {budget.fields.map((item, index) => (
            <div
              key={item.id}
              className="grid items-start gap-3 rounded-lg border border-line p-4 sm:grid-cols-[1fr_150px_150px_auto]"
            >
              <div>
                <label htmlFor={`concept-${index}`} className="field-label">
                  Concepto {index + 1}
                </label>
                <Input
                  id={`concept-${index}`}
                  {...register(`budgetItems.${index}.concept`)}
                  readOnly={!canEditField('budgetItems')}
                  {...field(`concept-${index}`, Boolean(errors.budgetItems?.[index]?.concept))}
                />
                {error(`concept-${index}`, errors.budgetItems?.[index]?.concept?.message)}
              </div>
              <div>
                <label htmlFor={`category-${index}`} className="field-label">
                  Categoría {index + 1}
                </label>
                <select
                  id={`category-${index}`}
                  className="input"
                  {...register(`budgetItems.${index}.category`)}
                  disabled={!canEditField('budgetItems')}
                >
                  <option value="personnel">Personal</option>
                  <option value="equipment">Equipo</option>
                  <option value="services">Servicios</option>
                  <option value="materials">Materiales</option>
                </select>
              </div>
              <div>
                <label htmlFor={`amount-${index}`} className="field-label">
                  Monto {index + 1}
                </label>
                <Input
                  id={`amount-${index}`}
                  type="number"
                  step="0.01"
                  min="0.01"
                  {...register(`budgetItems.${index}.amount`, { valueAsNumber: true })}
                  readOnly={!canEditField('budgetItems')}
                  {...field(`amount-${index}`, Boolean(errors.budgetItems?.[index]?.amount))}
                />
                {error(`amount-${index}`, errors.budgetItems?.[index]?.amount?.message)}
              </div>
              <Button
                variant="secondary"
                className="sm:mt-6"
                aria-label={`Eliminar partida ${index + 1}`}
                disabled={!canEditField('budgetItems') || budget.fields.length === 1}
                onClick={() => budget.remove(index)}
              >
                <Trash2 size={15} />
              </Button>
            </div>
          ))}
          {error('budget', errors.budgetItems?.message ?? errors.budgetItems?.root?.message)}
          <Button
            variant="secondary"
            onClick={() => budget.append({ concept: '', category: 'materials', amount: 0 })}
            disabled={!canEditField('budgetItems') || budget.fields.length >= 20}
          >
            <Plus size={15} />
            Agregar partida
          </Button>
          <p className="text-sm font-semibold" aria-live="polite">
            Presupuesto total: {currency(total)} MXN
          </p>
          <div className="max-w-md">
            <label htmlFor="requestedFunding" className="field-label">
              Financiamiento solicitado (MXN)
            </label>
            <Input
              id="requestedFunding"
              type="number"
              min="0.01"
              step="0.01"
              {...register('requestedFunding', { valueAsNumber: true })}
              readOnly={!canEditField('requestedFunding')}
              {...field('requestedFunding', Boolean(errors.requestedFunding))}
            />
            {error('requestedFunding', errors.requestedFunding?.message)}
            <p className="mt-2 text-xs text-muted">
              No puede superar el desglose. Límite total: $1,000,000 MXN.
            </p>
          </div>
        </fieldset>
        <fieldset disabled={create.isPending} className="panel space-y-5 p-6">
          <legend className="px-2 text-sm font-semibold">4. Ética e integridad</legend>
          <label className="flex items-start gap-3 text-sm">
            <input
              type="checkbox"
              className="mt-1 accent-accent"
              {...register('involvesParticipants')}
              disabled={!canEditField('involvesParticipants')}
            />
            La investigación involucra participantes humanos.
          </label>
          {participants && (
            <div>
              <label className="flex items-start gap-3 text-sm">
                <input
                  id="ethicsApproval"
                  type="checkbox"
                  className="mt-1 accent-accent"
                  {...register('ethicsApproval')}
                  disabled={!canEditField('ethicsApproval')}
                  {...field('ethicsApproval', Boolean(errors.ethicsApproval))}
                />
                Cuento con aprobación del comité de ética.
              </label>
              {error('ethicsApproval', errors.ethicsApproval?.message)}
            </div>
          )}
          <div>
            <label className="flex items-start gap-3 text-sm">
              <input
                id="declaration"
                type="checkbox"
                className="mt-1 accent-accent"
                {...register('declaration')}
                disabled={!canEditField('declaration')}
                {...field('declaration', Boolean(errors.declaration))}
              />
              Confirmo la integridad de los datos y la participación del equipo declarado.
            </label>
            {error('declaration', errors.declaration?.message)}
          </div>
        </fieldset>
        {create.error && (
          <p role="alert" className="field-error">
            {create.error.message}
          </p>
        )}
        {Object.keys(errors).length > 0 && (
          <p role="alert" className="field-error">
            Revisa los campos señalados antes de enviar la propuesta.
          </p>
        )}
        <div className="flex flex-wrap justify-end gap-3">
          <Link
            className="btn-secondary"
            to={proposal ? `/propuestas/${proposal.id}` : '/propuestas'}
          >
            Cancelar
          </Link>
          <Button
            variant="secondary"
            disabled={create.isPending}
            onClick={() => void save(getValues(), false)}
          >
            Guardar borrador
          </Button>
          <Button type="submit" disabled={create.isPending}>
            <Send size={16} />
            {create.isPending ? 'Guardando…' : 'Revisar antes de enviar'}
          </Button>
        </div>
      </form>
    </>
  );
}
