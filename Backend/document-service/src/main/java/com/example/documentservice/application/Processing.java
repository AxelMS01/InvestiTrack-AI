package com.example.documentservice.application;

import com.example.documentservice.api.*;
import com.example.documentservice.api.ApiModels.*;
import com.example.documentservice.domain.FileRules;
import com.example.documentservice.infrastructure.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static com.example.documentservice.api.ApiException.*;
import static com.example.documentservice.infrastructure.DocumentStore.map;

@Service
@Transactional
public class Processing {
    private final Documents documents;
    private final DocumentStore store;
    private final ObjectStorage storage;
    private final RemoteServices remote;
    private final String scanSecret;
    public Processing(Documents documents,DocumentStore store,ObjectStorage storage,RemoteServices remote,@Value("${document.scan.callback-secret}") String scanSecret){this.documents=documents;this.store=store;this.storage=storage;this.remote=remote;this.scanSecret=scanSecret;}
    public void signature(String body,String supplied) {
        require(!scanSecret.isBlank(),503,"SCANNER_NOT_CONFIGURED","A scanner result verification key is required.");
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(scanSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            require(supplied!=null&&supplied.matches("[0-9a-f]{64}")&&MessageDigest.isEqual(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)),HexFormat.of().parseHex(supplied)),403,"INVALID_SCAN_SIGNATURE","The scan result signature is invalid.");
        }catch(ApiException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}
    }
    public void scan(UUID id,Scan input) {
        var doc=store.row("document",id,true);var version=documents.version(id,input.documentVersion());
        var job=matching(input.jobId(),version,"MALWARE_SCAN",input.sha256());
        if(duplicate(job,input))return;
        require(!"DELETED".equals(doc.get("status")),409,"DOCUMENT_DELETED","A deleted document cannot be processed.");
        require(!input.scannedAt().isAfter(Instant.now().plusSeconds(60))&&!input.scannedAt().isBefore((Instant)version.get("uploadedAt")),422,"INVALID_SCAN_TIME","The scan must refer to this upload.");
        String scan=input.result().name();String processing=scan.equals("CLEAN")?"READY":scan.equals("INFECTED")?"INFECTED":"FAILED";
        store.update("document_version",(UUID)version.get("id"),map("scanStatus",scan,"processingStatus",processing,"extractionStatus",scan.equals("CLEAN")?"PENDING":"BLOCKED","indexingStatus",scan.equals("CLEAN")?"PENDING":"BLOCKED"));
        finish(job,input,scan.equals("CLEAN"),scan.equals("CLEAN")?null:"SCAN_"+scan);
        if(Documents.number(doc)==input.documentVersion())documents.save(doc,map("status",scan.equals("CLEAN")?"AVAILABLE":scan.equals("INFECTED")?"QUARANTINED":"REJECTED","accessVersion",Documents.access(doc)+1),(UUID)doc.get("ownerUserId"));
        else documents.save(doc,map(),(UUID)doc.get("ownerUserId"));
        var data=map("documentVersion",input.documentVersion(),"jobId",input.jobId());
        if(scan.equals("CLEAN"))data.put("extractionJobId",documents.job((UUID)version.get("id"),"TEXT_EXTRACTION"));else data.put("reasonCode","SCAN_"+scan);
        documents.emit(scan.equals("CLEAN")?"DocumentScanPassed":"DocumentScanFailed",doc,"document-service",data);
    }
    public void extraction(UUID id,Extraction input) {
        var doc=store.row("document",id,true);var version=documents.version(id,input.documentVersion());
        var job=matching(input.jobId(),version,"TEXT_EXTRACTION",input.sha256());if(duplicate(job,input))return;
        documents.current(doc,version);
        if(input.status()==ExtractionStatus.FAILED) {
            require(input.errorCode()!=null&&!input.errorCode().isBlank(),422,"ERROR_CODE_REQUIRED","Failed extraction requires an error code.");
            store.update("document_version",(UUID)version.get("id"),map("extractionStatus","FAILED","indexingStatus","BLOCKED"));finish(job,input,false,input.errorCode());return;
        }
        String ref="extractions/"+input.jobId()+".json";
        require(ref.equals(input.contentRef())&&input.textSha256()!=null&&input.segmentCount()!=null,422,"INVALID_EXTRACTION_REFERENCE","Successful extraction requires the managed job reference, hash and segment count.");
        byte[] bytes=storage.read(ref,20*1024*1024);
        require(FileRules.sha256(bytes).equals(input.textSha256()),422,"EXTRACTION_HASH_MISMATCH","Extracted content does not match its checksum.");
        var segments=segments(bytes);require(segments.size()==input.segmentCount(),422,"INVALID_SEGMENT_COUNT","The extracted segment count differs.");
        // Freeze the verified text; worker retries cannot overwrite indexed content.
        UUID extraction=UUID.randomUUID();String committed="text/"+extraction+".json";storage.write(committed,bytes,"application/json");
        store.insert("extracted_content",map("id",extraction,"documentVersionId",version.get("id"),"contentStorageKey",committed,"contentSha256",input.textSha256(),"segmentCount",segments.size()));
        store.update("document_version",(UUID)version.get("id"),map("extractionStatus","SUCCEEDED"));finish(job,input,true,null);
        UUID indexJob=documents.job((UUID)version.get("id"),"AI_INDEX");documents.save(doc,map(),(UUID)doc.get("ownerUserId"));
        documents.emit("DocumentTextExtracted",doc,"document-service",map("documentVersion",input.documentVersion(),"extractionId",extraction,"accessVersion",Documents.access(doc),"jobId",indexJob));
    }
    public Map<String,Object> content(UUID id,int number,String cursor) {
        var doc=store.row("document",id);var version=documents.version(id,number);documents.current(doc,version);var extraction=extracted(version);
        int position=0;
        if(cursor!=null){String[] parts=cursor.split(":");require(parts.length==2&&parts[0].equals(extraction.get("id").toString()),400,"INVALID_CURSOR","Cursor does not match the extraction.");position=Integer.parseInt(parts[1]);}
        byte[] bytes=storage.read(extraction.get("contentStorageKey").toString(),20*1024*1024);
        require(FileRules.sha256(bytes).equals(extraction.get("contentSha256")),503,"EXTRACTION_INTEGRITY_FAILURE","The stored extraction checksum differs.");
        var all=segments(bytes);require(position>=0&&position<=all.size(),400,"INVALID_CURSOR","Invalid segment position.");
        var page=new ArrayList<Map<String,Object>>();int size=0;
        while(position<all.size()&&page.size()<100){var segment=all.get(position);int length=store.json().writeValueAsBytes(segment).length;if(size+length>900*1024)break;page.add(segment);size+=length;position++;}
        return map("documentId",id,"documentVersion",number,"extractionId",extraction.get("id"),"classification",doc.get("classification"),"accessVersion",Documents.access(doc),"context",doc.get("context"),"segments",page,"nextCursor",position<all.size()?extraction.get("id")+":"+position:null);
    }
    public void index(UUID id,Indexing input) {
        var doc=store.row("document",id,true);var version=documents.version(id,input.documentVersion());var extraction=extracted(version);
        require(input.extractionId().equals(extraction.get("id")),409,"EXTRACTION_MISMATCH","The extraction is not associated with this content.");
        var job=store.one("select * from document_processing_job where document_version_id=:id and job_type='AI_INDEX' for update",map("id",version.get("id"))).orElseThrow(()->new ApiException(409,"JOB_NOT_FOUND","Indexing was not requested."));
        if(duplicate(job,input))return;documents.current(doc,version);
        boolean success=input.status()==IndexStatus.INDEXED;
        require(success?input.indexedAt()!=null&& !input.indexedAt().isAfter(Instant.now().plusSeconds(60)):input.errorCode()!=null&&!input.errorCode().isBlank(),422,"INVALID_INDEX_RESULT","Indexing requires a success timestamp or failure code.");
        store.update("document_version",(UUID)version.get("id"),map("indexingStatus",input.status()));finish(job,input,success,input.errorCode());
    }
    public Map<String,Object> source(Caller caller,UUID id,Source input) {
        require(caller.id().equals(input.subjectId()),403,"SUBJECT_MISMATCH","The subject must match the signed delegated user.");
        var maybe=store.one("select * from document where id=:id",map("id",id));boolean allowed=false;long access=0;
        if(maybe.isPresent()) {
            var doc=maybe.get();access=Documents.access(doc);
            if("AVAILABLE".equals(doc.get("status"))&&Documents.number(doc)==input.documentVersion()&&access==input.expectedAccessVersion()) {
                var version=documents.version(id,input.documentVersion());allowed="CLEAN".equals(version.get("scanStatus"))&&"SUCCEEDED".equals(version.get("extractionStatus"))&&"INDEXED".equals(version.get("indexingStatus"))&&remote.allowed(caller,"AI_SOURCE_READ","DOCUMENT",id);
            }
        }
        return map("allowed",allowed,"reasonCode",allowed?"AUTHORIZED":"SOURCE_UNAVAILABLE_OR_DENIED","documentId",id,"documentVersion",input.documentVersion(),"accessVersion",access,"checkedAt",Instant.now());
    }
    public Map<String,Object> workerContent(UUID id,UUID jobId,String client) {
        var doc=store.row("document",id);require(!"DELETED".equals(doc.get("status")),409,"DOCUMENT_DELETED","The document is deleted.");
        var job=store.row("document_processing_job",jobId);var version=store.row("document_version",(UUID)job.get("documentVersionId"));
        require(id.equals(version.get("documentId")),404,"JOB_NOT_FOUND","The job does not belong to this document.");
        require((client.equals("malware-scanner")&&job.get("jobType").equals("MALWARE_SCAN"))||(client.equals("text-extractor")&&job.get("jobType").equals("TEXT_EXTRACTION")),403,"FORBIDDEN","The worker cannot access this job.");
        require("PENDING".equals(job.get("status")),409,"JOB_COMPLETED","The job is already completed.");
        if(client.equals("text-extractor"))documents.current(doc,version);
        var result=map("documentVersion",version.get("versionNumber"),"sha256",version.get("sha256"),"url",storage.downloadUrl(version.get("storageKey").toString(),version.get("originalFileName").toString()),"expiresAt",Instant.now().plusSeconds(60));
        if(client.equals("text-extractor"))result.put("contentRef","extractions/"+jobId+".json");return result;
    }
    private Map<String,Object> matching(UUID jobId,Map<String,Object> version,String type,String hash){var job=store.row("document_processing_job",jobId,true);require(version.get("id").equals(job.get("documentVersionId"))&&type.equals(job.get("jobType"))&&hash.equals(version.get("sha256")),409,"PROCESSING_JOB_MISMATCH","The job, version and checksum must match.");return job;}
    private boolean duplicate(Map<String,Object> job,Object input){if(job.get("resultMetadata")==null)return false;var expected=store.json().valueToTree(input);require(expected.equals(store.json().valueToTree(job.get("resultMetadata"))),409,"PROCESSING_RESULT_CONFLICT","The job already has a different result.");return true;}
    private void finish(Map<String,Object> job,Object input,boolean success,String failure){store.update("document_processing_job",(UUID)job.get("id"),map("status",success?"SUCCEEDED":"FAILED","completedAt",Instant.now(),"resultMetadata",input,"failureCode",failure));}
    private Map<String,Object> extracted(Map<String,Object> version){return store.one("select * from extracted_content where document_version_id=:id",map("id",version.get("id"))).orElseThrow(()->new ApiException(409,"EXTRACTION_NOT_READY","No extracted content is available."));}
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> segments(byte[] bytes) {
        List<?> list;try {list=store.json().readValue(bytes,List.class);}catch(RuntimeException e){throw new ApiException(422,"INVALID_EXTRACTION","Extracted content must be a JSON segment array.");}
        require(list.size()<=100000,422,"INVALID_EXTRACTION","Too many extracted segments.");var result=new ArrayList<Map<String,Object>>();
        for(int i=0;i<list.size();i++) {
            require(list.get(i) instanceof Map,422,"INVALID_EXTRACTION","Invalid segment.");var s=(Map<String,Object>)list.get(i);
            require(Set.of("position","page","section","text").containsAll(s.keySet())&&s.get("position") instanceof Number&&((Number)s.get("position")).intValue()==i&&s.get("text") instanceof String&&s.get("text").toString().getBytes(StandardCharsets.UTF_8).length<=16384,422,"INVALID_EXTRACTION","Segments must be ordered and contain at most 16 KiB of text.");
            require(!s.containsKey("page")||(s.get("page") instanceof Number&&((Number)s.get("page")).intValue()>0),422,"INVALID_EXTRACTION","Page must be positive.");
            require(!s.containsKey("section")||(s.get("section") instanceof String&&s.get("section").toString().length()<=500),422,"INVALID_EXTRACTION","Invalid section label.");result.add(s);
        }return result;
    }
}
