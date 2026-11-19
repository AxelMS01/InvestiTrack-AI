import { useParams } from 'react-router-dom';
import { useDemoQuery, useWorkflowMutation } from '../../api/demo.queries';
import { Button } from '../../components/button';
import { Input } from '../../components/input';
import { PageHeading } from '../../components/ui';
import { demoCoordination } from '../../demo/coordination';
import { ActionLink, Feedback, Field, WorkflowList, WorkflowState } from '../shared/workflow-ui';

export function AcademicUnitsPage() {
  const { id } = useParams();
  const query = useDemoQuery(['academic-units'], demoCoordination.units);
  const accounts = useDemoQuery(
    ['unit-accounts', id],
    () => demoCoordination.unitAccounts(id ?? ''),
    Boolean(id),
  );
  const mutation = useWorkflowMutation(demoCoordination.saveUnit);
  const assignment = useWorkflowMutation(demoCoordination.assignUnit);
  const active = query.data?.find((item) => item.id === id);
  return (
    <>
      <PageHeading
        eyebrow="Administración"
        title={active?.name ?? 'Unidades académicas'}
        description="Gestiona unidades y sus vínculos institucionales."
        action={id && <ActionLink to="/administracion/unidades">Volver a unidades</ActionLink>}
      />
      <WorkflowState query={query}>
        {id && !active ? (
          <p role="alert" className="field-error">
            La unidad no está disponible.
          </p>
        ) : (
          <>
            {!id && (
              <WorkflowList empty="No hay unidades registradas.">
                {(query.data ?? []).map((item) => (
                  <article
                    key={item.id}
                    className="flex flex-wrap items-center justify-between gap-3 p-5"
                  >
                    <div>
                      <h2 className="font-medium">{item.name}</h2>
                      <p className="text-sm text-muted">
                        {item.code} · {item.active ? 'Activa' : 'Inactiva'}
                      </p>
                    </div>
                    <ActionLink to={`/administracion/unidades/${item.id}`}>
                      Gestionar unidad
                    </ActionLink>
                  </article>
                ))}
              </WorkflowList>
            )}
            <form
              key={id ?? 'new'}
              className="panel mt-5 max-w-3xl space-y-4 p-5"
              onSubmit={(event) => {
                event.preventDefault();
                const form = event.currentTarget;
                const fields = new FormData(form);
                mutation.mutate(
                  {
                    id,
                    name: String(fields.get('name') ?? ''),
                    code: String(fields.get('code') ?? ''),
                    active: fields.has('active'),
                  },
                  {
                    onSuccess: () => {
                      if (!id) form.reset();
                    },
                  },
                );
              }}
            >
              <h2 className="section-title">{id ? 'Editar unidad' : 'Registrar unidad'}</h2>
              <Field label="Código">
                <Input
                  name="code"
                  minLength={2}
                  maxLength={50}
                  required
                  defaultValue={active?.code}
                />
              </Field>
              <Field label="Nombre">
                <Input
                  name="name"
                  minLength={2}
                  maxLength={250}
                  required
                  defaultValue={active?.name}
                />
              </Field>
              <label className="flex items-center gap-2 text-sm">
                <input name="active" type="checkbox" defaultChecked={active?.active ?? true} />
                Unidad activa
              </label>
              <Button type="submit" disabled={mutation.isPending}>
                Guardar unidad
              </Button>
              <Feedback error={mutation.error} success={mutation.isSuccess && 'Unidad guardada.'} />
            </form>
            {id && active?.active && (
              <section className="mt-5 space-y-3">
                <h2 className="section-title">Pertenencia y coordinación</h2>
                <WorkflowState query={accounts}>
                  <WorkflowList empty="No hay cuentas activas.">
                    {(accounts.data ?? []).map((account) => (
                      <form
                        key={account.id + String(account.member) + String(account.coordinates)}
                        className="flex flex-wrap items-center justify-between gap-3 p-5"
                        onSubmit={(event) => {
                          event.preventDefault();
                          const fields = new FormData(event.currentTarget);
                          assignment.mutate({
                            unitId: id,
                            userId: account.id,
                            member: fields.has('member'),
                            coordinates: fields.has('coordinates'),
                          });
                        }}
                      >
                        <div className="min-w-0 break-words">
                          <p className="font-medium">{account.name}</p>
                          <p className="text-sm text-muted">{account.email}</p>
                        </div>
                        <label className="flex gap-2 text-sm">
                          <input name="member" type="checkbox" defaultChecked={account.member} />
                          Pertenece a la unidad
                        </label>
                        {account.roles.includes('COORDINATOR') && (
                          <label className="flex gap-2 text-sm">
                            <input
                              name="coordinates"
                              type="checkbox"
                              defaultChecked={account.coordinates}
                            />
                            Coordina la unidad
                          </label>
                        )}
                        <Button type="submit" variant="secondary" disabled={assignment.isPending}>
                          Actualizar vínculo
                        </Button>
                      </form>
                    ))}
                  </WorkflowList>
                </WorkflowState>
                <Feedback
                  error={assignment.error}
                  success={assignment.isSuccess && 'Vínculo actualizado.'}
                />
              </section>
            )}
          </>
        )}
      </WorkflowState>
    </>
  );
}
