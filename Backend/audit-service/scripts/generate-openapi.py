#!/usr/bin/env python3
"""Generate the public and trusted-ingestion contracts, including response models."""
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def ref(name):return {'$ref':'#/components/schemas/'+name}
def obj(props,required=None):return {'type':'object','properties':props,**({'required':required} if required else {})}
text={'type':'string'};uuid={'type':'string','format':'uuid'};timestamp={'type':'string','format':'date-time'};number={'type':'integer','format':'int64'}
filters=['actorId','action','resourceType','resourceId','projectId','outcome','from','to','correlationId','sourceService']
schemas={
 'Actor':obj({'type':{'type':'string','enum':['USER','SERVICE','SYSTEM']},'id':{'type':'string','maxLength':255}},['type','id']),
 'Resource':obj({'type':{'type':'string','pattern':'^[A-Z][A-Z0-9_]{0,49}$'},'id':{'type':'string','format':'uuid','nullable':True}},['type']),
 'Outcome':{'type':'string','enum':['SUCCESS','FAILURE','DENIED']},
 'AuditFilters':{'type':'object','additionalProperties':False,'properties':{k:{'type':'string','maxLength':255} for k in filters}},
 'AuditEvent':obj({'id':uuid,'sequenceNumber':number,'occurredAt':timestamp,'receivedAt':timestamp,'actor':ref('Actor'),'action':text,'resource':ref('Resource'),'projectId':{'type':'string','format':'uuid','nullable':True},'outcome':ref('Outcome'),'correlationId':text,'sourceService':text,'metadata':{'type':'object','description':'Only allowlisted UUIDs, codes, dates, counters and role codes. No raw text, credentials or signed URLs.'},'previousHash':{'type':'string','nullable':True},'eventHash':text}),
 'ExportInput':{'type':'object','additionalProperties':False,'required':['filters'],'properties':{'filters':ref('AuditFilters')}},
 'ExportJob':obj({'id':uuid,'requestedBy':uuid,'filterDefinition':ref('AuditFilters'),'upperSequence':number,'status':{'type':'string','enum':['QUEUED','RUNNING','COMPLETED','FAILED','EXPIRED']},'format':{'type':'string','enum':['CSV']},'recordCount':{'type':'integer','nullable':True},'requestedAt':timestamp,'startedAt':{'type':'string','format':'date-time','nullable':True},'completedAt':{'type':'string','format':'date-time','nullable':True},'expiresAt':{'type':'string','format':'date-time','nullable':True},'failureCode':{'type':'string','nullable':True}}),
 'DownloadAuthorization':obj({'url':text,'expiresAt':timestamp},['url','expiresAt']),
 'Accepted':obj({'eventId':uuid,'accepted':{'type':'boolean'}},['eventId','accepted']),
 'Problem':obj({k:number if k=='status' else text for k in ['type','title','status','detail','instance','code','correlationId']},['type','title','status','detail','instance','code','correlationId'])
}
schemas['AuditInput']={'type':'object','additionalProperties':False,'properties':{'eventId':uuid,'occurredAt':timestamp,'actor':ref('Actor'),'action':{'type':'string','pattern':'^[A-Za-z][A-Za-z0-9_.:-]{0,99}$'},'resource':ref('Resource'),'outcome':ref('Outcome'),'correlationId':{'type':'string','pattern':'^[A-Za-z0-9._:-]{1,100}$'},'metadata':{'type':'object','maxProperties':100,'additionalProperties':True}},'required':['eventId','occurredAt','actor','action','resource','outcome','correlationId']}
schemas['AuditPage']=obj({'items':{'type':'array','items':ref('AuditEvent')},'page':obj({'limit':{'type':'integer','minimum':1,'maximum':100},'hasMore':{'type':'boolean'},'nextCursor':{'type':'string','nullable':True}})})
paths={}
def operation(path,method,name,success,result=None,body=None):
    responses={str(code):{'description':description,'content':{'application/problem+json':{'schema':ref('Problem')}}} for code,description in [(400,'Invalid input'),(401,'Valid bearer token required'),(403,'Role, client or scope denied'),(404,'Absent or not owned'),(409,'Contradictory event, idempotency conflict or export unavailable'),(413,'Body exceeds 256 KiB'),(422,'Invalid event time or export limit'),(503,'Required dependency unavailable')]}
    for code in success:
        responses[str(code)]={'description':{200:'Successful query or identical replay',201:'Audit event durably accepted',202:'Export queued'}[code]}
        if result:responses[str(code)]['content']={'application/json':{'schema':ref(result)}}
    op={'operationId':name,'summary':name,'tags':['trusted ingestion' if path.startswith('/internal') else 'audit'],'responses':responses}
    params=[]
    if '{id}' in path:params.append({'name':'id','in':'path','required':True,'schema':uuid})
    if params:op['parameters']=params
    if body:op['requestBody']={'required':True,'content':{'application/json':{'schema':ref(body)}}}
    paths.setdefault(path,{})[method]=op;return op
search=operation('/api/v1/audit-events','get','searchAuditEvents',[200],'AuditPage')
search['parameters']=[{'name':f,'in':'query','schema':text} for f in filters]+[{'name':'limit','in':'query','schema':{'type':'integer','minimum':1,'maximum':100,'default':25}},{'name':'cursor','in':'query','schema':text}]
search['description']='ADMIN or AUDITOR from current Identity state. Search pages use a fixed ingestion boundary, sorted by occurredAt descending then UUID descending. Cursors are bound to the caller, filters and limit. from/to accept UTC timestamps or inclusive calendar dates.'
operation('/api/v1/audit-events/{id}','get','getAuditEvent',[200],'AuditEvent')
create=operation('/api/v1/audit-export-jobs','post','createAuditExport',[202],'ExportJob','ExportInput')
create['parameters']=[{'name':'Idempotency-Key','in':'header','required':False,'schema':{'type':'string','pattern':'^[A-Za-z0-9._:-]{1,200}$'}}]
create['responses']['202']['headers']={'Location':{'description':'Poll this export job.','schema':text}}
create['description']='Captures current maximum ingestion sequence. Maximum 10000 matching events and 10 MiB CSV; larger exports fail with EXPORT_TOO_LARGE. Artifacts expire in 24 hours. Replays return the original response.'
operation('/api/v1/audit-export-jobs/{id}','get','getAuditExport',[200],'ExportJob')
operation('/api/v1/audit-export-jobs/{id}/download-authorization','post','authorizeAuditExportDownload',[200],'DownloadAuthorization')
content=operation('/api/v1/audit-export-jobs/{id}/content','get','downloadAuditExport',[200])
content['parameters'].append({'name':'token','in':'query','required':True,'schema':{'type':'string','pattern':'^[A-Za-z0-9_-]{43}$'}})
content['responses']['200']['content']={'text/csv':{'schema':{'type':'string','format':'binary'}}}
content['description']='Requires the requesting user bearer token, a current ADMIN/AUDITOR role and a 60-second random download grant. Administrators cannot open another auditor\'s job.'
internal=operation('/internal/v1/audit-events','post','appendAuditEvent',[201,200],'Accepted','AuditInput')
internal['description']='Not exposed by the gateway. Requires audit-service audience, audit:append scope, allowlisted client_id and sub=client_id. Source service is derived from credentials. Resource types are restricted per producer. eventId is durable idempotency identity: equal input 200, conflicting input or producer 409. Bodies are limited to 256 KiB.'
spec={'openapi':'3.0.3','info':{'title':'InvestiTrack Audit Service','version':'1.0.0','description':'Append-only audit history, private asynchronous CSV exports, and trusted idempotent ingestion. All public reads require a current administrator or institution-wide auditor. No public API creates or mutates audit events. Raw producer payloads are never retained in the database.'},'servers':[{'url':'/'}],'security':[{'bearerAuth':[]}],'paths':paths,'components':{'schemas':schemas,'securitySchemes':{'bearerAuth':{'type':'http','scheme':'bearer','bearerFormat':'JWT'}}}}
(ROOT/'src/main/resources/openapi.json').write_text(json.dumps(spec,indent=2)+'\n')
print('Generated',sum(map(len,paths.values())),'operations')
