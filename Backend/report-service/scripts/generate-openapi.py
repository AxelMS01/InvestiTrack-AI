#!/usr/bin/env python3
"""Generate the versioned Reporting API contract without runtime schema dependencies."""
import json
from pathlib import Path

paths = {}
def operation(method, path, summary, *, body=None, params=(), success='200', internal=False):
    parameters=[]
    for part in path.split('/'):
        if part.startswith('{'):
            name=part[1:-1]
            parameters.append({'name':name,'in':'path','required':True,'schema':{'type':'string',**({'format':'uuid'} if name in ('jobId','projectId') else {})}})
    for name in params:
        parameters.append({'name':name,'in':'query','required':name=='token','schema':{'type':'integer','minimum':1,'maximum':100} if name=='limit' else {'type':'string'}})
    responses={success:{'description':'Success'},**{str(s):{'description':d,'content':{'application/problem+json':{'schema':{'$ref':'#/components/schemas/Problem'}}}} for s,d in [(400,'Invalid request'),(401,'Unauthenticated'),(403,'Forbidden'),(404,'Absent or not visible'),(409,'State conflict or expired snapshot'),(422,'Unsupported format or bounded query exceeded'),(503,'Dependency unavailable')]}}
    if success!='204':
        media='text/csv' if path.endswith('/content') else 'application/json'
        responses[success]['content']={media:{'schema':{'type':'string','format':'binary'} if media=='text/csv' else {'type':'object'}}}
    op={'summary':summary,'security':[{'workload' if internal else 'bearer':[]}],'responses':responses}
    if parameters:op['parameters']=parameters
    if body:op['requestBody']={'required':True,'content':{'application/json':{'schema':body}}}
    if method=='post' and path=='/api/v1/report-jobs':op.setdefault('parameters',[]).append({'name':'Idempotency-Key','in':'header','schema':{'type':'string','maxLength':200}})
    paths.setdefault(path,{})[method]=op

for view in ('researcher','coordinator','evaluator'):
    operation('get',f'/api/v1/dashboards/{view}',f'Authorized {view} dashboard',params=('researchArea','status','from','to','coordinatorId'))
operation('get','/api/v1/dashboards/projects/{projectId}','Authorized project dashboard',params=('researchArea','status','from','to','coordinatorId'))
for path in ('/api/v1/deadlines','/api/v1/projects/{projectId}/deadlines'):
    operation('get',path,'Authorized deadlines ordered by date and resource',params=('state','type','from','to','responsibleUserId','projectId','limit','cursor'))
operation('get','/api/v1/report-definitions','Available report definitions and parameter schemas')
operation('get','/api/v1/report-definitions/{reportCode}','Get a report definition')
operation('post','/api/v1/report-jobs','Queue a report using current authorized scope',success='202',body={'type':'object','additionalProperties':False,'required':['reportCode','format','parameters'],'properties':{'reportCode':{'enum':['PROJECT_PORTFOLIO_STATUS','PROJECT_DEADLINES']},'format':{'enum':['CSV']},'parameters':{'type':'object','description':'Validated against the selected report definition; historical asOfDate is unsupported.'}}})
operation('get','/api/v1/report-jobs','List caller report jobs',params=('limit','cursor'))
operation('get','/api/v1/report-jobs/{jobId}','Get caller or administrator job metadata')
operation('delete','/api/v1/report-jobs/{jobId}','Cancel a queued job or remove an artifact',success='204')
operation('post','/api/v1/report-jobs/{jobId}/download-authorization','Revalidate access and issue a 60-second download grant')
operation('get','/api/v1/report-jobs/{jobId}/content','Download CSV with bearer token and fresh project authorization',params=('token',))
operation('get','/api/v1/reference/statuses','Status display labels')
operation('post','/internal/v1/projections/rebuilds','Stage and atomically reconcile an owner snapshot',success='202',internal=True,body={'type':'object','required':['projection','scope','reason'],'additionalProperties':False,'properties':{'projection':{'enum':['PROJECTS','DEADLINES','EVALUATIONS']},'scope':{'type':'object','additionalProperties':False,'properties':{'projectIds':{'type':'array','minItems':1,'maxItems':100,'uniqueItems':True,'items':{'type':'string','format':'uuid'}}}},'reason':{'type':'string','minLength':1,'maxLength':500}}})
operation('get','/internal/v1/projections/rebuilds/{jobId}','Get rebuild progress and sanitized error code',internal=True)

doc={'openapi':'3.0.3','info':{'title':'Dashboard & Reporting Service','version':'1.0.0','description':'Eventually consistent read models; current authorization is mandatory. UTC deadlines. CSV exports expire after 24 hours. Internal routes are never public gateway routes.'},'paths':paths,'components':{'securitySchemes':{'bearer':{'type':'http','scheme':'bearer','bearerFormat':'JWT'},'workload':{'type':'http','scheme':'bearer','bearerFormat':'JWT','description':'report-service audience, reporting:rebuild scope, allowlisted client_id=sub.'}},'schemas':{'Problem':{'type':'object','required':['type','title','status','detail','instance','code','correlationId'],'properties':{key:{'type':'integer' if key=='status' else 'string'} for key in ['type','title','status','detail','instance','code','correlationId']}}}}}
Path(__file__).resolve().parents[1].joinpath('src/main/resources/openapi.json').write_text(json.dumps(doc,indent=2)+'\n')
