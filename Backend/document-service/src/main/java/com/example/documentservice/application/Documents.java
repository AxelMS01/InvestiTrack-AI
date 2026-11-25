package com.example.documentservice.application;

import com.example.documentservice.api.*;
import com.example.documentservice.api.ApiModels.*;
import com.example.documentservice.domain.FileRules;
import com.example.documentservice.infrastructure.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.example.documentservice.api.ApiException.*;
import static com.example.documentservice.infrastructure.DocumentStore.map;

@Service
@Transactional
public class Documents {
    private final DocumentStore store;
    private final ObjectStorage storage;
    private final RemoteServices remote;
    private final DomainEvents events;
    private final long maximum;
    public Documents(DocumentStore store,ObjectStorage storage,RemoteServices remote,DomainEvents events,@Value("${document.upload.max-bytes}") long maximum) {
        this.store=store;this.storage=storage;this.remote=remote;this.events=events;this.maximum=maximum;
    }
    public Map<String,Object> initialize(Caller caller,UUID target,Upload input,String key,String match) {
        FileRules.declared(input.fileName(),input.contentType(),input.sizeBytes(),maximum,input.sha256());
        return idempotent(caller.id()+":upload:"+target,key,input,()->{
            remote.context(caller,input.context());
            if(target!=null) {
                var doc=editable(caller,target);etag(doc,match);unheld(doc);
                require(!"DELETED".equals(doc.get("status")),409,"DOCUMENT_DELETED","Restore the document first.");
                require(context(doc).equals(input.context())&&doc.get("classification").equals(input.classification().name())&&doc.get("documentType").equals(input.documentType()),422,"VERSION_CONTEXT_MISMATCH","New versions must retain the document context, classification and type.");
            }
            UUID id=UUID.randomUUID();String temporary="uploads/"+id;Instant expires=Instant.now().plusSeconds(900);
            String url=storage.uploadUrl(temporary);
            store.insert("upload_session",map("id",id,"initiatedBy",caller.id(),"targetDocumentId",target,"requestBody",input,
                "temporaryStorageKey",temporary,"status","INITIALIZED","expiresAt",expires));
            return map("uploadId",id,"method","PUT","uploadUrl",url,"requiredHeaders",Map.of("Content-Type",input.contentType()),"expiresAt",expires);
        });
    }
    public Map<String,Object> upload(Caller caller,UUID id) {return uploadView(owned(caller,id,false));}
    public void cancel(Caller caller,UUID id) {
        var session=owned(caller,id,true);if("CANCELLED".equals(session.get("status")))return;
        require("INITIALIZED".equals(session.get("status")),409,"UPLOAD_ALREADY_COMPLETED","The upload can no longer be cancelled.");
        store.update("upload_session",id,map("status","CANCELLED"));
    }
    public Map<String,Object> complete(Caller caller,UUID id) {
        var session=owned(caller,id,true);
        if("COMPLETED".equals(session.get("status")))return uploadView(session);
        require("INITIALIZED".equals(session.get("status"))&&((Instant)session.get("expiresAt")).isAfter(Instant.now()),409,"UPLOAD_EXPIRED_OR_CANCELLED","The upload is no longer active.");
        Upload input=store.json().convertValue(session.get("requestBody"),Upload.class);remote.context(caller,input.context());
        byte[] bytes=storage.read(session.get("temporaryStorageKey").toString(),input.sizeBytes());
        require(bytes.length==input.sizeBytes()&&FileRules.sha256(bytes).equals(input.sha256()),422,"UPLOAD_INTEGRITY_MISMATCH","The object size or checksum differs from the declaration.");
        require(FileRules.matches(bytes,input.contentType()),415,"FILE_SIGNATURE_MISMATCH","The file signature does not match an approved format.");
        Map<String,Object> doc;int number=1;
        if(session.get("targetDocumentId")==null) {
            UUID document=UUID.randomUUID();doc=map("id",document,"logicalName",input.fileName(),"documentType",input.documentType(),"classification",input.classification().name(),
                "ownerUserId",caller.id(),"currentVersionNumber",1,"status","PROCESSING","context",input.context().values(),"version",1L,"accessVersion",1L,"createdBy",caller.id(),"updatedBy",caller.id());
            store.insert("document",doc);
        } else {
            doc=editable(caller,(UUID)session.get("targetDocumentId"));unheld(doc);
            require(!"DELETED".equals(doc.get("status"))&&context(doc).equals(input.context())&&doc.get("classification").equals(input.classification().name()),409,"DOCUMENT_CHANGED","The document context changed during upload.");
            number=((Number)doc.get("currentVersionNumber")).intValue()+1;
            save(doc,map("currentVersionNumber",number,"status","PROCESSING","accessVersion",access(doc)+1),caller.id());
        }
        UUID version=UUID.randomUUID();String committed="documents/"+doc.get("id")+"/"+version;
        // Replaying an unexpired PUT URL can only change the temporary key, never these validated bytes.
        storage.write(committed,bytes,input.contentType());
        store.insert("document_version",map("id",version,"documentId",doc.get("id"),"versionNumber",number,"originalFileName",input.fileName(),"declaredContentType",input.contentType(),
            "detectedContentType",input.contentType(),"sizeBytes",input.sizeBytes(),"sha256",input.sha256(),"storageKey",committed,"processingStatus","SCANNING","uploadedBy",caller.id()));
        UUID job=job(version,"MALWARE_SCAN");
        if(session.get("targetDocumentId")==null)addAssociation(doc,input.context(),"SUPPORTING",caller);
        store.update("upload_session",id,map("status","COMPLETED","documentId",doc.get("id"),"documentVersion",number,"completedAt",Instant.now()));
        emit(session.get("targetDocumentId")==null?"DocumentUploadCompleted":"DocumentVersionCreated",doc,caller.id().toString(),map("documentVersion",number,"sha256",input.sha256(),"jobId",job));
        return uploadView(store.row("upload_session",id));
    }
    public Map<String,Object> metadata(Caller caller,UUID id) {return view(readable(caller,id));}
    public Map<String,Object> patch(Caller caller,UUID id,Patch input,String match) {
        var doc=editable(caller,id);etag(doc,match);require(!"DELETED".equals(doc.get("status")),409,"DOCUMENT_DELETED","Restore the document first.");
        var changes=map();if(input.logicalName()!=null){require(!input.logicalName().isBlank(),400,"INVALID_NAME","A document name is required.");changes.put("logicalName",input.logicalName());}
        if(input.classification()!=null&&!input.classification().name().equals(doc.get("classification"))) {
            unheld(doc);changes.put("classification",input.classification().name());changes.put("accessVersion",access(doc)+1);
        }
        require(!changes.isEmpty(),400,"EMPTY_PATCH","No metadata changes were supplied.");save(doc,changes,caller.id());
        if(changes.containsKey("accessVersion"))emit("DocumentAccessChanged",doc,caller.id().toString(),map("accessVersion",access(doc)));
        return view(doc);
    }
    public void delete(Caller caller,UUID id,String match) {
        var doc=editable(caller,id);etag(doc,match);unheld(doc);
        require("AVAILABLE".equals(doc.get("status"))?remote.allowed(caller,"DOCUMENT_DELETE","DOCUMENT",id):caller.id().equals(doc.get("ownerUserId"))||caller.roles().contains("ADMIN"),404,"RESOURCE_NOT_FOUND","The document is not deletable by this caller.");
        require(!"DELETED".equals(doc.get("status")),409,"DOCUMENT_DELETED","The document is already deleted.");
        save(doc,map("status","DELETED","deletedAt",Instant.now(),"deletedBy",caller.id(),"accessVersion",access(doc)+1),caller.id());
        emit("DocumentDeleted",doc,caller.id().toString(),map("deletedAt",doc.get("deletedAt"),"accessVersion",access(doc)));
    }
    public Map<String,Object> restore(Caller caller,UUID id,String match) {
        var doc=editable(caller,id);etag(doc,match);
        require(caller.id().equals(doc.get("ownerUserId"))||caller.roles().contains("ADMIN"),403,"FORBIDDEN","An owner or administrator must restore the document.");
        require("DELETED".equals(doc.get("status")),409,"DOCUMENT_NOT_DELETED","The document is not deleted.");
        var v=version(id,number(doc));String status="CLEAN".equals(v.get("scanStatus"))?"AVAILABLE":"INFECTED".equals(v.get("scanStatus"))?"QUARANTINED":"PROCESSING";
        save(doc,map("status",status,"deletedAt",null,"deletedBy",null,"accessVersion",access(doc)+1),caller.id());
        emit("DocumentAccessChanged",doc,caller.id().toString(),map("accessVersion",access(doc)));return view(doc);
    }
    public Map<String,Object> versions(Caller caller,UUID id) {
        readable(caller,id);var rows=store.query("select id,version_number,original_file_name,declared_content_type,detected_content_type,size_bytes,sha256,processing_status,scan_status,extraction_status,indexing_status,uploaded_at,uploaded_by from document_version where document_id=:id order by version_number desc",map("id",id));return map("items",rows);
    }
    public Map<String,Object> versionMetadata(Caller caller,UUID id,int number) {readable(caller,id);return versionView(version(id,number));}
    public Map<String,Object> download(Caller caller,UUID id,Integer requested) {
        var doc=readable(caller,id);require(!"DELETED".equals(doc.get("status")),404,"RESOURCE_NOT_FOUND","The document is deleted.");
        require(remote.allowed(caller,"DOCUMENT_READ","DOCUMENT",id),404,"RESOURCE_NOT_FOUND","The document is unavailable or not visible.");
        var v=version(id,requested==null?number(doc):requested);clean(doc,v);
        return map("url",storage.downloadUrl(v.get("storageKey").toString(),v.get("originalFileName").toString()),"expiresAt",Instant.now().plusSeconds(60));
    }
    public Map<String,Object> processing(Caller caller,UUID id){var doc=readable(caller,id);var v=version(id,number(doc));return map("documentId",id,"documentVersion",number(doc),"status",doc.get("status"),"scan",v.get("scanStatus"),"extraction",v.get("extractionStatus"),"indexing",v.get("indexingStatus"),"thumbnail","UNSUPPORTED");}
    public Map<String,Object> context(UUID id) {var d=store.row("document",id);return map("documentId",id,"classification",d.get("classification"),"status",d.get("status"),"documentVersion",number(d),"accessVersion",access(d),"context",d.get("context"));}
    public Map<String,Object> reference(Caller caller,UUID id,Reference input) {
        var doc=store.row("document",id,true);var v=version(id,input.documentVersion());
        boolean valid=input.projectId().equals(context(doc).projectId())&&"AVAILABLE".equals(doc.get("status"))&&"CLEAN".equals(v.get("scanStatus"))&&input.documentVersion()==number(doc)
            &&remote.allowed(caller,"DOCUMENT_READ","DOCUMENT",id)&&remote.allowed(caller,"DOCUMENT_UPLOAD","PROJECT",input.projectId());
        return map("valid",valid,"reasonCode",valid?"VALID":"DOCUMENT_REFERENCE_INVALID","documentVersion",input.documentVersion(),"sha256",v.get("sha256"));
    }
    public void hold(Caller caller,UUID id,UUID reference,Reference input,String client) {
        store.row("document",id,true);
        var existing=store.one("select * from document_hold where id=:id",map("id",reference));
        if(existing.isPresent()) {
            var h=existing.get();require(id.equals(h.get("documentId"))&&client.equals(h.get("caller"))&&input.projectId().equals(h.get("projectId"))&&input.documentVersion()==((Number)h.get("documentVersion")).intValue()&&input.purpose().name().equals(h.get("purpose")),409,"HOLD_CONFLICT","The reference is already reserved for other content.");return;
        }
        require(Boolean.TRUE.equals(reference(caller,id,input).get("valid")),422,"DOCUMENT_REFERENCE_INVALID","Only current, authorized and clean content may be reserved.");
        store.insert("document_hold",map("id",reference,"documentId",id,"documentVersion",input.documentVersion(),"projectId",input.projectId(),"purpose",input.purpose(),"caller",client));
    }
    public void release(UUID id,UUID reference,String client) {
        store.row("document",id,true);
        store.execute("delete from document_hold where id=:reference and document_id=:id and caller=:client",map("reference",reference,"id",id,"client",client));
    }
    public Map<String,Object> associations(Caller caller,UUID id) {readable(caller,id);return map("items",store.query("select id,resource_type,resource_id,project_id,association_type,context,created_at,created_by from document_association where document_id=:id order by id",map("id",id)));}
    public Map<String,Object> associate(Caller caller,UUID id,Association input,String match,String key) {
        return idempotent(caller.id()+":associate:"+id,key,input,()->{
            var doc=editable(caller,id);etag(doc,match);remote.context(caller,input.context());
            require(sameParent(context(doc),input.context()),422,"CROSS_SCOPE_ASSOCIATION","Associations must remain in the document's primary project or proposal.");
            var result=addAssociation(doc,input.context(),input.associationType(),caller);save(doc,map(),caller.id());return result;
        });
    }
    public void dissociate(Caller caller,UUID id,UUID association,String match) {
        var doc=editable(caller,id);etag(doc,match);unheld(doc);
        require(store.execute("delete from document_association where id=:association and document_id=:id",map("association",association,"id",id))==1,404,"RESOURCE_NOT_FOUND","The association was not found.");save(doc,map(),caller.id());
    }
    public Map<String,Object> list(Caller caller,Map<String,String> filters) {
        var allowed=Set.of("q","projectId","proposalId","activityId","milestoneId","documentType","classification","processingStatus","uploadedBy","from","to","limit","cursor");
        require(allowed.containsAll(filters.keySet()),400,"INVALID_FILTER","An unsupported document filter was supplied.");
        int limit=Integer.parseInt(filters.getOrDefault("limit","25"));require(limit>0&&limit<=100,400,"INVALID_LIMIT","Limit must be between 1 and 100.");
        if(filters.containsKey("classification"))Classification.valueOf(filters.get("classification"));
        if(filters.containsKey("processingStatus"))require(Set.of("UPLOADED","SCANNING","EXTRACTING","READY","FAILED","INFECTED").contains(filters.get("processingStatus")),400,"INVALID_PROCESSING_STATUS","Unknown processing status.");
        if(filters.containsKey("from")&&filters.containsKey("to")) {
            Instant from=filterInstant(filters.get("from"),true),to=filterInstant(filters.get("to"),false);
            require(!from.isAfter(to),400,"INVALID_DATE_RANGE","from must be on or before to.");
        }
        var binding=new TreeMap<>(filters);binding.remove("cursor");String scope=FileRules.sha256((caller.id()+store.json().writeValueAsString(binding)).getBytes(StandardCharsets.UTF_8));
        UUID after=new UUID(0,0);
        if(filters.containsKey("cursor")) {
            try {String[] parts=new String(Base64.getUrlDecoder().decode(filters.get("cursor")),StandardCharsets.UTF_8).split(":");require(parts.length==2&&parts[0].equals(scope),400,"INVALID_CURSOR","The cursor belongs to a different query.");after=UUID.fromString(parts[1]);}catch(IllegalArgumentException e){throw new ApiException(400,"INVALID_CURSOR","Invalid cursor.");}
        }
        var params=map("after",after,"limit",100);
        var sql=new StringBuilder("select * from document where status='AVAILABLE' and classification<>'RESTRICTED' and id>:after");
        for(String name:List.of("projectId","proposalId","activityId","milestoneId"))if(filters.containsKey(name)){UUID.fromString(filters.get(name));sql.append(" and context->>'").append(name).append("'=:").append(name);params.put(name,filters.get(name));}
        for(var pair:Map.of("documentType","document_type","classification","classification").entrySet())if(filters.containsKey(pair.getKey())){sql.append(" and ").append(pair.getValue()).append("=:").append(pair.getKey());params.put(pair.getKey(),pair.getKey().equals("uploadedBy")?UUID.fromString(filters.get(pair.getKey())):filters.get(pair.getKey()));}
        if(filters.containsKey("uploadedBy")){sql.append(" and exists(select 1 from document_version v where v.document_id=document.id and v.version_number=document.current_version_number and v.uploaded_by=:uploadedBy)");params.put("uploadedBy",UUID.fromString(filters.get("uploadedBy")));}
        if(filters.containsKey("processingStatus")){sql.append(" and exists(select 1 from document_version v where v.document_id=document.id and v.version_number=document.current_version_number and v.processing_status=:processingStatus)");params.put("processingStatus",filters.get("processingStatus"));}
        if(filters.containsKey("q")){sql.append(" and position(lower(:q) in lower(logical_name))>0");params.put("q",filters.get("q"));}
        for(String bound:List.of("from","to"))if(filters.containsKey(bound)){sql.append(" and created_at").append(bound.equals("from")?">=":"<=").append(":").append(bound);String value=filters.get(bound);params.put(bound,filterInstant(value,bound.equals("from")));}
        sql.append(" order by id limit :limit");var items=new ArrayList<Map<String,Object>>();boolean more=false;UUID last=after;
        for(int batch=0;batch<10;batch++) {
            params.put("after",last);var rows=store.query(sql.toString(),params);if(rows.isEmpty()){more=false;break;}
            var resources=rows.stream().map(d->map("type","DOCUMENT","id",d.get("id"))).toList();var visible=remote.filter(caller,resources);
            boolean filled=false;
            for(var row:rows) {last=(UUID)row.get("id");if(visible.contains(last))items.add(view(row));if(items.size()==limit){filled=true;break;}}
            more=filled||rows.size()==100;
            if(filled||rows.size()<100)break;
        }
        String next=more?Base64.getUrlEncoder().withoutPadding().encodeToString((scope+":"+last).getBytes(StandardCharsets.UTF_8)):null;
        return map("items",items,"page",map("limit",limit,"nextCursor",next,"hasMore",more));
    }
    public void expireUploads(){store.execute("update upload_session set status='EXPIRED' where status='INITIALIZED' and expires_at<now()",Map.of());}
    private Map<String,Object> readable(Caller caller,UUID id) {
        var d=store.row("document",id);Context c=context(d);
        require(!"RESTRICTED".equals(d.get("classification"))&&remote.allowed(caller,c.projectId()!=null?"PROJECT_READ":"PROPOSAL_READ",c.projectId()!=null?"PROJECT":"PROPOSAL",c.projectId()!=null?c.projectId():c.proposalId()),404,"RESOURCE_NOT_FOUND","The document is absent or not visible.");
        if("AVAILABLE".equals(d.get("status")))require(remote.allowed(caller,"DOCUMENT_READ","DOCUMENT",id),404,"RESOURCE_NOT_FOUND","The document is absent or not visible.");
        else require(caller.id().equals(d.get("ownerUserId"))||caller.roles().contains("ADMIN"),404,"RESOURCE_NOT_FOUND","Only an owner or administrator may see processing or deleted metadata.");return d;
    }
    private Map<String,Object> editable(Caller caller,UUID id) {var d=store.row("document",id,true);remote.context(caller,context(d));return d;}
    private Map<String,Object> owned(Caller caller,UUID id,boolean lock){var s=store.row("upload_session",id,lock);require(caller.id().equals(s.get("initiatedBy")),404,"RESOURCE_NOT_FOUND","The upload is absent or not visible.");return s;}
    private Map<String,Object> uploadView(Map<String,Object> s){var result=map("uploadId",s.get("id"),"status",s.get("status"),"expiresAt",s.get("expiresAt"));if(s.get("documentId")!=null){result.put("documentId",s.get("documentId"));result.put("documentVersion",s.get("documentVersion"));}return result;}
    private Map<String,Object> addAssociation(Map<String,Object> doc,Context c,String type,Caller caller){
        String resource=c.activityId()!=null?"ACTIVITY":c.milestoneId()!=null?"MILESTONE":c.projectId()!=null?"PROJECT":"PROPOSAL";
        UUID resourceId=c.activityId()!=null?c.activityId():c.milestoneId()!=null?c.milestoneId():c.projectId()!=null?c.projectId():c.proposalId();
        var row=map("id",UUID.randomUUID(),"documentId",doc.get("id"),"resourceType",resource,"resourceId",resourceId,"projectId",c.projectId(),"associationType",type,"context",c.values(),"createdBy",caller.id());store.insert("document_association",row);return row;
    }
    public Map<String,Object> version(UUID id,int number){return store.one("select * from document_version where document_id=:id and version_number=:number",map("id",id,"number",number)).orElseThrow(()->new ApiException(404,"VERSION_NOT_FOUND","The document version was not found."));}
    public UUID job(UUID version,String type){UUID id=UUID.randomUUID();store.insert("document_processing_job",map("id",id,"documentVersionId",version,"jobType",type));return id;}
    public void clean(Map<String,Object> doc,Map<String,Object> v){require(!"DELETED".equals(doc.get("status"))&&"CLEAN".equals(v.get("scanStatus")),409,"DOCUMENT_UNAVAILABLE","Only scanned clean content is available.");}
    public void current(Map<String,Object> doc,Map<String,Object> v){clean(doc,v);require(number(doc)==((Number)v.get("versionNumber")).intValue()&&"AVAILABLE".equals(doc.get("status")),409,"STALE_DOCUMENT_VERSION","Only the current available version may be indexed.");}
    public void save(Map<String,Object> doc,Map<String,Object> changes,UUID actor){changes=new LinkedHashMap<>(changes);changes.put("version",((Number)doc.get("version")).longValue()+1);changes.put("updatedAt",Instant.now());changes.put("updatedBy",actor);store.update("document",(UUID)doc.get("id"),changes);doc.putAll(changes);}
    public void emit(String type,Map<String,Object> doc,String actor,Map<String,Object> data){data=new LinkedHashMap<>(data);data.put("documentId",doc.get("id"));events.write(type,doc,actor,data);}
    public static Context context(Map<String,Object> d){@SuppressWarnings("unchecked") var c=(Map<String,Object>)d.get("context");return new Context(uuid(c.get("proposalId")),uuid(c.get("projectId")),uuid(c.get("activityId")),uuid(c.get("milestoneId")));}
    private static Instant filterInstant(String value,boolean start){return value.length()==10?LocalDate.parse(value).atTime(start?LocalTime.MIN:LocalTime.MAX).toInstant(ZoneOffset.UTC):Instant.parse(value);}
    private static UUID uuid(Object o){return o==null?null:UUID.fromString(o.toString());}
    public static int number(Map<String,Object> d){return ((Number)d.get("currentVersionNumber")).intValue();}
    public static long access(Map<String,Object> d){return ((Number)d.get("accessVersion")).longValue();}
    private static boolean sameParent(Context a,Context b){return Objects.equals(a.projectId(),b.projectId())&&Objects.equals(a.proposalId(),b.proposalId());}
    private void unheld(Map<String,Object> doc){require(store.one("select id from document_hold where document_id=:id limit 1",map("id",doc.get("id"))).isEmpty(),409,"DOCUMENT_IN_USE","A durable project reference prevents this change.");}
    private static void etag(Map<String,Object> doc,String match){require(match!=null,428,"PRECONDITION_REQUIRED","If-Match is required.");require(match.equals("\""+doc.get("version")+"\""),412,"PRECONDITION_FAILED","The document changed. Refresh before retrying.");}
    private static Map<String,Object> view(Map<String,Object> d){var r=new LinkedHashMap<>(d);r.remove("currentVersionNumber");r.put("documentVersion",number(d));return r;}
    private static Map<String,Object> versionView(Map<String,Object> v){var result=new LinkedHashMap<>(v);result.remove("storageKey");return result;}
    @SuppressWarnings("unchecked")
    private Map<String,Object> idempotent(String scope,String key,Object input,Supplier<Map<String,Object>> action){
        if(key==null)return action.get();require(key.matches("[A-Za-z0-9._:-]{1,200}"),400,"INVALID_IDEMPOTENCY_KEY","Invalid idempotency key.");
        store.lock(scope+":"+key);String hash=FileRules.sha256(store.json().writeValueAsBytes(input));
        var prior=store.one("select * from idempotency_record where scope=:scope and key=:key",map("scope",scope,"key",key));
        if(prior.isPresent()){require(hash.equals(prior.get().get("requestHash")),409,"IDEMPOTENCY_CONFLICT","The key was used with a different request.");return (Map<String,Object>)prior.get().get("responseBody");}
        var response=action.get();store.insert("idempotency_record",map("id",UUID.randomUUID(),"scope",scope,"key",key,"requestHash",hash,"responseBody",response));return response;
    }
}
