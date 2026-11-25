#!/usr/bin/env python3
"""Generate OpenAPI from the document service DTOs and controller mappings."""
import json
import re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'src/main/java/com/example/documentservice/api'
models=(JAVA/'ApiModels.java').read_text()
schemas={name:{'type':'string','enum':[v.strip() for v in body.split(',')]} for name,body in re.findall(r'public enum (\w+)\s*\{([^}]+)\}',models)}
records=list(re.finditer(r'public record (\w+)\(',models))
record_names={m[1] for m in records}
def schema(kind):
    if kind in schemas or kind in record_names:return {'$ref':'#/components/schemas/'+kind}
    if kind=='UUID':return {'type':'string','format':'uuid'}
    if kind=='Instant':return {'type':'string','format':'date-time'}
    if kind in ('Integer','Long','int','long'):return {'type':'integer'}
    return {'type':'string'}
for match in records:
    name=match[1];start=match.end();depth=1;end=start
    while depth:
        char=models[end];depth+=(char=='(')-(char==')');end+=1
    parts=re.split(r',\s*(?![^()]*\))',models[start:end-1]);properties={};required=[]
    if name=='Caller':continue
    for part in parts:
        clean=re.sub(r'@\w+(?:\([^)]*\))?\s*','',part).strip();kind,field=clean.rsplit(' ',1);prop=schema(kind)
        for bound,value in re.findall(r'(min|max)=(\d+)',part):prop[bound+'Length']=int(value)
        if '@NotBlank' in part:prop['minLength']=max(1,prop.get('minLength',0))
        if '@Positive ' in part:prop['minimum']=1
        if '@PositiveOrZero' in part:prop['minimum']=0
        pattern=re.search(r'@Pattern\(regexp="([^"]+)"\)',part)
        if pattern:prop['pattern']=pattern[1]
        properties[field]=prop
        if re.search(r'@(NotNull|NotBlank)\b',part) or kind in ('int','long'):required.append(field)
    schemas[name]={'type':'object','additionalProperties':False,'properties':properties}
    if required:schemas[name]['required']=required

def obj(fields,required=None):
    return {'type':'object','properties':fields,**({'required':required} if required else {})}
def ref(name):return {'$ref':'#/components/schemas/'+name}
string={'type':'string'};uuid={'type':'string','format':'uuid'};instant={'type':'string','format':'date-time'};integer={'type':'integer'}
schemas['Document']=obj({'id':uuid,'logicalName':string,'documentType':string,'classification':ref('Classification'),'status':{'type':'string','enum':['PROCESSING','AVAILABLE','QUARANTINED','REJECTED','DELETED']},'ownerUserId':uuid,'documentVersion':integer,'version':integer,'accessVersion':integer,'context':ref('Context'),'createdAt':instant,'updatedAt':instant})
schemas['DocumentVersion']=obj({'id':uuid,'documentId':uuid,'versionNumber':integer,'originalFileName':string,'declaredContentType':string,'detectedContentType':string,'sizeBytes':integer,'sha256':string,'processingStatus':string,'scanStatus':string,'extractionStatus':string,'indexingStatus':string,'uploadedAt':instant,'uploadedBy':uuid})
schemas['UploadInstructions']=obj({'uploadId':uuid,'method':{'type':'string','enum':['PUT']},'uploadUrl':{'type':'string','format':'uri'},'requiredHeaders':{'type':'object','additionalProperties':string},'expiresAt':instant})
schemas['UploadState']=obj({'uploadId':uuid,'status':string,'expiresAt':instant,'documentId':uuid,'documentVersion':integer})
schemas['Download']=obj({'url':{'type':'string','format':'uri'},'expiresAt':instant})
schemas['AccessContext']=obj({'documentId':uuid,'classification':ref('Classification'),'status':string,'documentVersion':integer,'accessVersion':integer,'context':ref('Context')})
schemas['SourceValidation']=obj({'allowed':{'type':'boolean'},'reasonCode':string,'documentId':uuid,'documentVersion':integer,'accessVersion':integer,'checkedAt':instant})
schemas['ReferenceValidation']=obj({'valid':{'type':'boolean'},'reasonCode':string,'documentVersion':integer,'sha256':string})
schemas['Segment']=obj({'position':{'type':'integer','minimum':0},'page':{'type':'integer','minimum':1},'section':{'type':'string','maxLength':500},'text':{'type':'string','description':'At most 16 KiB UTF-8.'}},['position','text'])
schemas['IndexingContent']=obj({'documentId':uuid,'documentVersion':integer,'extractionId':uuid,'classification':ref('Classification'),'accessVersion':integer,'context':ref('Context'),'segments':{'type':'array','maxItems':100,'items':ref('Segment')},'nextCursor':{'type':'string','nullable':True}})
schemas['DocumentPage']=obj({'items':{'type':'array','items':ref('Document')},'page':obj({'limit':integer,'nextCursor':{'type':'string','nullable':True},'hasMore':{'type':'boolean'}})})
schemas['ProcessingState']=obj({'documentId':uuid,'documentVersion':integer,'status':string,'scan':string,'extraction':string,'indexing':string,'thumbnail':{'type':'string','enum':['UNSUPPORTED']}})
schemas['AssociationView']=obj({'id':uuid,'documentId':uuid,'resourceType':string,'resourceId':uuid,'projectId':uuid,'associationType':string,'context':ref('Context'),'createdAt':instant,'createdBy':uuid})
schemas['WorkerContent']=obj({'documentVersion':integer,'sha256':string,'url':{'type':'string','format':'uri'},'expiresAt':instant,'contentRef':string})
schemas['Problem']=obj({k:integer if k=='status' else string for k in ['type','title','status','detail','instance','code','correlationId']},['type','title','status','detail','instance','code','correlationId'])
paths={}
responses_by_method={'initialize':'UploadInstructions','upload':'UploadState','complete':'UploadState','list':'DocumentPage','metadata':'Document','patch':'Document','restore':'Document','version':'DocumentVersion','newVersion':'UploadInstructions','download':'Download','downloadVersion':'Download','processing':'ProcessingState','associate':'AssociationView','context':'AccessContext','content':'IndexingContent','source':'SourceValidation','reference':'ReferenceValidation','workerContent':'WorkerContent'}
for filename,prefix in [('DocumentController.java','/api/v1'),('InternalDocumentController.java','/internal/v1/documents/{id}')]:
    source=(JAVA/filename).read_text()
    matches=list(re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping\("([^"]+)"\)',source))
    for i,match in enumerate(matches):
        block=source[match.end():matches[i+1].start() if i+1<len(matches) else len(source)]
        method=match[1].lower();path=prefix+match[2];internal=prefix.startswith('/internal')
        name=re.search(r'public (?:[^\n]*?) (\w+)\(',block)[1]
        success=204 if '@ResponseStatus(HttpStatus.NO_CONTENT)' in block else 201 if 'ResponseEntity.created' in block else 202 if 'ResponseEntity.accepted' in block else 200
        operation={'operationId':name,'tags':['workers and service contracts' if internal else 'documents'],'summary':method.upper()+' '+path,'responses':{str(success):{'description':{200:'Successful result',201:'Created',202:'Accepted for processing',204:'Completed'}[success]}}}
        result=responses_by_method.get(name)
        if name in ('versions','associations'):result_schema=obj({'items':{'type':'array','items':ref('DocumentVersion' if name=='versions' else 'AssociationView')}})
        elif name=='preview':result_schema=obj({'supported':{'type':'boolean','enum':[False]}})
        else:result_schema=ref(result) if result else {'type':'object'}
        if success!=204:operation['responses'][str(success)]['content']={'application/json':{'schema':result_schema}}
        if success in (201,202):operation['responses'][str(success)]['headers']={'Location':{'schema':string,'description':'URI of the upload session or association.'}}
        if name in ('metadata','patch','restore'):operation['responses'][str(success)]['headers']={'ETag':{'schema':string,'description':'Quoted aggregate version; send as If-Match.'}}
        for code in [400,401,403,404,409,412,413,415,422,428,503]:operation['responses'][str(code)]={'description':str(code)+' Problem','content':{'application/problem+json':{'schema':ref('Problem')}}}
        parameters=[{'name':p,'in':'path','required':True,'schema':{'type':'integer','minimum':1} if p=='number' else uuid} for p in re.findall(r'\{([^}]+)\}',path)]
        for header in ['If-Match','Idempotency-Key','X-Result-Signature']:
            if header in block:parameters.append({'name':header,'in':'header','required':header!='Idempotency-Key','schema':string})
        for optional,kind,field in re.findall(r'@RequestParam(\(required=false\))? (int|String) (\w+)',block):parameters.append({'name':field,'in':'query','required':not bool(optional),'schema':schema(kind)})
        if name=='list':parameters.extend({'name':f,'in':'query','schema':{'type':'integer','minimum':1,'maximum':100,'default':25} if f=='limit' else string} for f in ['q','projectId','proposalId','activityId','milestoneId','documentType','classification','processingStatus','uploadedBy','from','to','limit','cursor'])
        if parameters:operation['parameters']=parameters
        dto=re.search(r'@RequestBody (\w+) input',block)
        if name=='scan':dto_name='Scan'
        else:dto_name=dto[1] if dto else None
        if dto_name:operation['requestBody']={'required':True,'content':{'application/json':{'schema':ref(dto_name)}}}
        if internal:operation['description']='Requires document-service audience, an allowlisted client_id and the route-specific document:* scope. Context: identity-service; scan: malware-scanner; extraction: text-extractor; index/source: ai-service; references/holds: project-execution-service (or project-service). Source validation, reference validation and hold creation require a delegated user_id claim. Other operations require workload sub=client_id.'
        paths.setdefault(path,{})[method]=operation
spec={'openapi':'3.0.3','info':{'title':'InvestiTrack Document Management','version':'1.0.0','description':'Private immutable object versions, quarantine and signed worker callbacks. Version/association collections return items. Search returns only available documents in the current authorized scope. Previews and thumbnails are unsupported. Idempotency-Key replays the original response; If-Match is one quoted aggregate ETag. Scan callbacks require HMAC-SHA256 of the exact UTF-8 body in X-Result-Signature. Bodies and storage keys never appear in public metadata.'},'servers':[{'url':'/'}],'security':[{'bearerAuth':[]}],'paths':paths,'components':{'schemas':schemas,'securitySchemes':{'bearerAuth':{'type':'http','scheme':'bearer','bearerFormat':'JWT'}}}}
(ROOT/'src/main/resources/openapi.json').write_text(json.dumps(spec,indent=2)+'\n')
print('Generated',sum(map(len,paths.values())),'operations')
