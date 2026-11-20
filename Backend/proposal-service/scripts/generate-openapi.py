#!/usr/bin/env python3
"""Generate the routing/DTO contract from this service's Java records and controller signatures."""
import json
import re
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'src/main/java/com/example/proposalservice'
models=(JAVA/'api/ApiModels.java').read_text()
enum_source=models+'\n'+'\n'.join(path.read_text() for path in (JAVA/'infraestructure/persistence').glob('*.java'))
schemas={}
for name,body in re.findall(r'public enum (\w+)\s*\{([^}]+)\}',enum_source):
    schemas[name]={'type':'string','enum':[value.strip() for value in body.split(',')]}

def schema(kind):
    kind=kind.strip()
    if kind in schemas:return {'$ref':'#/components/schemas/'+kind}
    if kind.startswith('List<'):return {'type':'array','items':schema(kind[5:-1])}
    if kind=='UUID':return {'type':'string','format':'uuid'}
    if kind=='LocalDate':return {'type':'string','format':'date'}
    if kind in ('Integer','Long','int','long'):return {'type':'integer'}
    if kind=='BigDecimal':return {'type':'number'}
    if kind=='Boolean':return {'type':'boolean'}
    return {'type':'string'}

for match in re.finditer(r'public record (\w+)\(',models):
    name=match[1];start=match.end();depth=1;end=start
    while depth:
        char=models[end];depth+=(char=='(')-(char==')');end+=1
    raw=models[start:end-1];parts=[];part='';depth=0
    for char in raw:
        if char==',' and depth==0:parts.append(part.strip());part='';continue
        part+=char;depth+=(char in '(<')-(char in ')>')
    if part.strip():parts.append(part.strip())
    properties={};required=[]
    for part in parts:
        clean=re.sub(r'@\w+(?:\([^)]*\))?\s*','',part).strip()
        field_type,field=clean.rsplit(' ',1)
        prop=schema(field_type)
        size=re.search(r'@Size\(([^)]*)\)',part)
        if size:
            for bound,value in re.findall(r'(min|max)=(\d+)',size[1]):
                prop[bound+('Items' if field_type.startswith('List<') else 'Length')]=int(value)
        if '@NotBlank' in part and not field_type.startswith('List<'):prop['minLength']=max(prop.get('minLength',0),1)
        if '@Email' in part:prop['format']='email'
        if '@Positive ' in part:prop['minimum']=1
        if '@PositiveOrZero' in part:prop['minimum']=0
        if field_type=='BigDecimal':prop.update(multipleOf=0.01)
        alias=re.search(r'@JsonProperty\("([^"]+)"\)',part)
        if alias:field=alias[1]
        properties[field]=prop
        # Only outer field constraints imply the field itself is required.
        if re.search(r'@(NotNull|NotBlank|NotEmpty)\b',part.split('List<')[0]):required.append(field)
    schemas[name]={'type':'object','additionalProperties':False,'properties':properties}
    if required:schemas[name]['required']=required

paths={}
for filename,prefix in [('ProposalController.java','/api/v1/proposals'),('InternalProposalController.java','/internal/v1/proposals/{proposalId}'),('CurrentUserController.java','/api/v1'),('ProposalCatalogController.java','/api/v1'),('ProjectionController.java','/internal/v1')]:
    source=(JAVA/'api'/filename).read_text()
    pattern=r'@(Get|Post|Put|Patch|Delete)Mapping(?:\((.*?)\))?\s*public\s+(.*?)(?=\n    @|\n})'
    for match in re.finditer(pattern,source,re.S):
        method=match[1].lower();routes=re.findall(r'"([^"]+)"',match[2]) if match[2] else [''];block=match[3]
        for route in routes:
            path=prefix+route
            op={'tags':['internal' if prefix.startswith('/internal') else 'proposals'],'operationId':method+'_'+re.sub(r'[^a-zA-Z0-9]+','_',path).strip('_'),'summary':method.upper()+' '+path,'responses':{str(code):{'$ref':'#/components/responses/Status'+str(code)} for code in [200,201,204,400,401,403,404,409,412,422,428,503]}}
            parameters=[]
            for parameter in re.findall(r'\{([^}]+)\}',path):parameters.append({'name':parameter,'in':'path','required':True,'schema':{'type':'string',**({'format':'uuid'} if parameter!='aggregate' else {})}})
            if '@RequestHeader(value="If-Match"' in block:parameters.append({'name':'If-Match','in':'header','required':True,'schema':{'type':'string'},'description':'One quoted numeric ETag. Creates use the parent ETag; updates use the resource ETag.'})
            if '@RequestHeader(value="Idempotency-Key"' in block:parameters.append({'name':'Idempotency-Key','in':'header','required':False,'schema':{'type':'string','minLength':1,'maxLength':200}})
            if '@RequestParam(defaultValue="25") int limit' in block or '@RequestParam(defaultValue="100") int limit' in block:
                parameters.extend([{'name':'limit','in':'query','schema':{'type':'integer','minimum':1,'maximum':100,'default':100 if 'defaultValue="100"' in block else 25}},{'name':'cursor','in':'query','schema':{'type':'string'}}])
            if '@RequestParam UUID subjectId' in block:parameters.append({'name':'subjectId','in':'query','required':True,'schema':{'type':'string','format':'uuid'}})
            if '@RequestParam String projection' in block:parameters.append({'name':'projection','in':'query','required':True,'schema':{'type':'string','enum':['PROPOSALS','EVALUATIONS']}})
            dto=re.search(r'@RequestBody (\w+) input',block)
            if dto:op['requestBody']={'required':True,'content':{'application/json':{'schema':{'$ref':'#/components/schemas/'+dto[1]}}}}
            if parameters:op['parameters']=parameters
            if prefix.startswith('/internal'):op['description']='Workload token with proposal-service audience, client_id matching sub, allowlisted client, and the route-specific scope. Projection endpoints require reporting-service.'
            paths.setdefault(path,{})[method]=op
for path in ['/api/v1/proposals']:
    paths[path]['get'].setdefault('parameters',[]).extend({'name':name,'in':'query','schema':{'type':'array','items':{'$ref':'#/components/schemas/ProposalStatus'}} if name=='status' else {'type':'string'},**({'style':'form','explode':True} if name=='status' else {})} for name in ['q','status','researchArea','researcherId','evaluatorId','submittedFrom','submittedTo','sort'])
for path,methods in paths.items():
    if path.endswith('/project-creation-data'):
        methods['get'].setdefault('parameters',[]).append({'name':'approvalId','in':'query','schema':{'type':'string','format':'uuid'},'description':'Pins an immutable approval; omitted only for the Project manual-command compatibility lookup.'})
responses={}
for status,title in [(200,'Successful query or update'),(201,'Resource created'),(204,'Operation completed'),(400,'Invalid request'),(401,'Missing or invalid authentication'),(403,'Access denied'),(404,'Absent or not visible'),(409,'State or uniqueness conflict'),(412,'Stale ETag'),(422,'Domain validation failed'),(428,'If-Match required'),(503,'Storage or dependency unavailable')]:
    responses['Status'+str(status)]={'description':title}
    if status>=400:responses['Status'+str(status)]['content']={'application/problem+json':{'schema':{'$ref':'#/components/schemas/Problem'}}}
schemas['Problem']={'type':'object','required':['type','title','status','detail','instance','code','correlationId'],'properties':{key:{'type':'integer' if key=='status' else 'string'} for key in ['type','title','status','detail','instance','code','correlationId']}}
spec={'openapi':'3.0.3','info':{'title':'InvestiTrack Proposal and Evaluation','version':'1.0.0','description':'Proposal drafts, review rounds, evaluator assignments, immutable evaluations, approved project plans, and internal owner/messaging contracts. Collections return items/page; mutable resources return quoted numeric ETags. Approval requires creationPlan.'},'servers':[{'url':'/'}],'security':[{'bearerAuth':[]}],'paths':paths,'components':{'securitySchemes':{'bearerAuth':{'type':'http','scheme':'bearer','bearerFormat':'JWT'}},'schemas':schemas,'responses':responses}}
(ROOT/'src/main/resources/openapi.json').write_text(json.dumps(spec,indent=2)+'\n')
print('Generated',sum(len(operations) for operations in paths.values()),'operations')
