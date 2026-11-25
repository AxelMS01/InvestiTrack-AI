package com.example.documentservice;

import com.example.documentservice.api.*;
import com.example.documentservice.api.ApiModels.*;
import com.example.documentservice.application.*;
import com.example.documentservice.domain.FileRules;
import com.example.documentservice.infrastructure.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static com.example.documentservice.infrastructure.DocumentStore.map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"document.messaging.enabled=false","document.jobs.enabled=false","spring.rabbitmq.dynamic=false","document.scan.callback-secret=test-signing-key"})
@AutoConfigureMockMvc
class DocumentServiceApplicationTests {
    static PostgreSQLContainer postgres;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String url=System.getenv("DOCUMENT_TEST_DATABASE_URL");
        if(url==null){postgres=new PostgreSQLContainer("postgres:16-alpine");postgres.start();p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}
        else {if(!url.matches(".*[/]([^/?]*test[^/?]*)(\\?.*)?$"))throw new IllegalArgumentException("Use a dedicated database whose name contains test.");p.add("spring.datasource.url",()->url);p.add("spring.datasource.username",()->System.getenv().getOrDefault("DOCUMENT_TEST_DATABASE_USERNAME","document_test"));p.add("spring.datasource.password",()->System.getenv().getOrDefault("DOCUMENT_TEST_DATABASE_PASSWORD",""));}
    }
    @Autowired MockMvc mvc;
    @Autowired Documents documents;
    @Autowired Processing processing;
    @Autowired DocumentStore store;
    @Autowired ObjectMapper json;
    @MockitoBean RemoteServices remote;
    @MockitoBean ObjectStorage storage;
    @MockitoBean JwtDecoder decoder;
    Caller owner,other;Context context;Upload input;
    final byte[] pdf="%PDF-1.7\nTest research".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Map<String,byte[]> objects;
    @BeforeEach void setup(){
        store.execute("truncate document,upload_session,idempotency_record,outbox_event,processed_event cascade",Map.of());objects=new HashMap<>();
        owner=new Caller(UUID.randomUUID(),Set.of("RESEARCHER"));other=new Caller(UUID.randomUUID(),Set.of("STUDENT"));context=new Context(null,UUID.randomUUID(),null,null);
        input=new Upload("research.pdf","application/pdf",pdf.length,FileRules.sha256(pdf),"RESEARCH_PROTOCOL",Classification.CONFIDENTIAL,context);
        when(remote.caller(any())).thenReturn(owner);when(remote.delegated(any())).thenReturn(owner);
        when(remote.allowed(any(),anyString(),anyString(),any())).thenReturn(true);
        when(remote.filter(any(),anyList())).thenAnswer(i->{List<Map<String,Object>> rows=i.getArgument(1);var ids=new HashSet<UUID>();rows.forEach(r->ids.add((UUID)r.get("id")));return ids;});
        when(storage.uploadUrl(anyString())).thenAnswer(i->"https://storage.test/put/"+i.getArgument(0));
        when(storage.downloadUrl(anyString(),anyString())).thenReturn("https://storage.test/download");
        when(storage.read(anyString(),anyLong())).thenAnswer(i->{byte[] b=objects.get(i.getArgument(0));if(b==null)throw ApiException.unavailable();return b.clone();});
        doAnswer(i->{objects.put(i.getArgument(0),((byte[])i.getArgument(1)).clone());return null;}).when(storage).write(anyString(),any(),anyString());
    }
    UUID initialized(){var result=documents.initialize(owner,null,input,null,null);UUID id=UUID.fromString(result.get("uploadId").toString());objects.put("uploads/"+id,pdf.clone());return id;}
    UUID uploaded(){return (UUID)documents.complete(owner,initialized()).get("documentId");}
    Scan scanInput(UUID id,ScanResult result){var v=documents.version(id,1);var job=store.one("select id from document_processing_job where document_version_id=:id and job_type='MALWARE_SCAN'",map("id",v.get("id"))).orElseThrow();return new Scan((UUID)job.get("id"),1,FileRules.sha256(pdf),result,"clamav","test",Instant.now());}
    UUID clean(){UUID id=uploaded();processing.scan(id,scanInput(id,ScanResult.CLEAN));return id;}
    String etag(UUID id){return "\""+store.row("document",id).get("version")+"\"";}
    RequestPostProcessor asUser(){return jwt().jwt(j->j.subject("oidc-subject").audience(List.of("research-platform-api")));}
    RequestPostProcessor workload(String client,String scope){return jwt().jwt(j->j.subject(client).audience(List.of("document-service")).claim("client_id",client)).authorities(new SimpleGrantedAuthority("SCOPE_"+scope));}
    @Test void uploadedBytesAreFrozenAndQuarantinedUntilClean(){
        UUID session=initialized();UUID id=(UUID)documents.complete(owner,session).get("documentId");var version=documents.version(id,1);
        assertThat(version.get("storageKey")).isNotEqualTo("uploads/"+session);objects.put("uploads/"+session,"malicious replacement".getBytes());
        assertThat(objects.get(version.get("storageKey"))).isEqualTo(pdf);
        assertThatThrownBy(()->documents.download(owner,id,null)).isInstanceOf(ApiException.class);
        assertThat(documents.complete(owner,session).get("documentId")).isEqualTo(id);
        processing.scan(id,scanInput(id,ScanResult.CLEAN));assertThat(documents.download(owner,id,null)).containsKey("url");
        assertThat(documents.versionMetadata(owner,id,1)).doesNotContainKey("storageKey");
        assertThat(store.query("select * from outbox_event",Map.of())).hasSize(2);
    }
    @Test void completionChecksChecksumAndSignatureAtomically(){
        UUID id=initialized();objects.put("uploads/"+id,"not a PDF".getBytes());
        assertThatThrownBy(()->documents.complete(owner,id)).isInstanceOf(ApiException.class);assertThat(store.query("select * from document",Map.of())).isEmpty();
        Upload spoof=new Upload("research.pdf","application/pdf",3,FileRules.sha256(new byte[]{1,2,3}),"OTHER",Classification.INTERNAL,context);
        var upload=documents.initialize(owner,null,spoof,null,null);UUID session=(UUID)upload.get("uploadId");objects.put("uploads/"+session,new byte[]{1,2,3});
        assertThatThrownBy(()->documents.complete(owner,session)).isInstanceOf(ApiException.class).hasMessageContaining("signature");
        assertThat(store.query("select * from outbox_event",Map.of())).isEmpty();
    }
    @Test void callbacksAreBoundToJobsAndCannotResurrectDeletedContent(){
        UUID id=uploaded();Scan result=scanInput(id,ScanResult.CLEAN);processing.scan(id,result);processing.scan(id,result);
        assertThat(store.query("select * from outbox_event",Map.of())).hasSize(2);
        assertThatThrownBy(()->processing.scan(id,new Scan(result.jobId(),1,result.sha256(),ScanResult.INFECTED,result.engine(),result.signatureVersion(),result.scannedAt()))).isInstanceOf(ApiException.class).hasMessageContaining("different result");
        documents.delete(owner,id,etag(id));processing.scan(id,result);assertThat(store.row("document",id).get("status")).isEqualTo("DELETED");
        assertThatThrownBy(()->documents.download(owner,id,null)).isInstanceOf(ApiException.class);
    }
    @Test void infectedAndErroredScansNeverEnableDownloads(){
        for(ScanResult result:List.of(ScanResult.INFECTED,ScanResult.ERROR)){UUID id=uploaded();processing.scan(id,scanInput(id,result));assertThatThrownBy(()->documents.download(owner,id,null)).isInstanceOf(ApiException.class);assertThat(store.row("document",id).get("status")).isIn("QUARANTINED","REJECTED");}
    }
    @Test void uploadsArePrivateCancellableExpiringAndIdempotent(){
        var a=documents.initialize(owner,null,input,"request-1",null);assertThat(documents.initialize(owner,null,input,"request-1",null)).containsEntry("uploadId",a.get("uploadId").toString());
        Upload changed=new Upload("other.pdf",input.contentType(),input.sizeBytes(),input.sha256(),input.documentType(),input.classification(),context);
        assertThatThrownBy(()->documents.initialize(owner,null,changed,"request-1",null)).isInstanceOf(ApiException.class);
        UUID id=UUID.fromString(a.get("uploadId").toString());assertThatThrownBy(()->documents.upload(other,id)).isInstanceOf(ApiException.class);
        documents.cancel(owner,id);documents.cancel(owner,id);assertThatThrownBy(()->documents.complete(owner,id)).isInstanceOf(ApiException.class);
        UUID expired=initialized();store.update("upload_session",expired,map("expiresAt",Instant.now().minusSeconds(1)));documents.expireUploads();assertThat(documents.upload(owner,expired).get("status")).isEqualTo("EXPIRED");
    }
    @Test void holdsPreventDeletionReclassificationAndNewVersions(){
        UUID id=clean(),reference=UUID.randomUUID();Reference ref=new Reference(context.projectId(),1,Purpose.FINAL_REPORT);
        documents.hold(owner,id,reference,ref,"project-service");documents.hold(owner,id,reference,ref,"project-service");
        assertThatThrownBy(()->documents.delete(owner,id,etag(id))).isInstanceOf(ApiException.class).hasMessageContaining("durable");
        assertThatThrownBy(()->documents.patch(owner,id,new Patch(null,Classification.PUBLIC),etag(id))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->documents.initialize(owner,id,input,null,etag(id))).isInstanceOf(ApiException.class);
        documents.release(id,reference,"other-client");assertThat(store.query("select * from document_hold",Map.of())).hasSize(1);
        documents.release(id,reference,"project-service");documents.delete(owner,id,etag(id));assertThat(documents.restore(owner,id,etag(id)).get("status")).isEqualTo("AVAILABLE");
    }
    @Test void versionsRetainHistoryButOnlyCurrentVersionCanBeIndexed(){
        UUID id=clean();var newUpload=documents.initialize(owner,id,input,"new-version",etag(id));UUID upload=UUID.fromString(newUpload.get("uploadId").toString());objects.put("uploads/"+upload,pdf);
        documents.complete(owner,upload);assertThat(documents.metadata(owner,id).get("documentVersion")).isEqualTo(2);
        assertThat((List<?>)documents.versions(owner,id).get("items")).hasSize(2);
        assertThatThrownBy(()->processing.content(id,1,null)).isInstanceOf(ApiException.class);
    }
    @Test void metadataRequiresConcurrencyAndCrossProjectAccessIsDenied() throws Exception {
        UUID id=clean();mvc.perform(patch("/api/v1/documents/"+id).with(asUser()).contentType("application/json").content("{\"logicalName\":\"Renamed\"}")).andExpect(status().isPreconditionRequired());
        mvc.perform(patch("/api/v1/documents/"+id).with(asUser()).header("If-Match","\"0\"").contentType("application/json").content("{\"logicalName\":\"Renamed\"}")).andExpect(status().isPreconditionFailed());
        when(remote.allowed(any(),anyString(),anyString(),any())).thenReturn(false);mvc.perform(get("/api/v1/documents/"+id).with(asUser())).andExpect(status().isNotFound());
        when(remote.filter(any(),anyList())).thenReturn(Set.of());assertThat((List<?>)documents.list(owner,Map.of()).get("items")).isEmpty();
    }
    @Test void listingAndAssociationsDoNotBroadenScope(){
        UUID a=clean(),b=clean();when(remote.filter(any(),anyList())).thenAnswer(i->{List<Map<String,Object>> rows=i.getArgument(1);return rows.stream().anyMatch(r->r.get("id").equals(a))?Set.of(a):Set.of();});
        var results=documents.list(owner,Map.of("q","research","projectId",context.projectId().toString()));assertThat((List<?>)results.get("items")).hasSize(1);
        assertThatThrownBy(()->documents.associate(owner,a,new Association(new Context(null,UUID.randomUUID(),null,null),"EVIDENCE"),etag(a),null)).isInstanceOf(ApiException.class);
        assertThat(documents.list(owner,Map.of("uploadedBy",other.id().toString())).get("items")).isEqualTo(List.of());
    }
    @Test void extractionAndIndexingAreIntegrityCheckedPaginatedAndRevalidated(){
        UUID id=clean();var version=documents.version(id,1);var job=store.one("select id from document_processing_job where document_version_id=:id and job_type='TEXT_EXTRACTION'",map("id",version.get("id"))).orElseThrow();UUID jobId=(UUID)job.get("id");
        var segments=new ArrayList<Map<String,Object>>();for(int i=0;i<101;i++)segments.add(map("position",i,"page",1,"text","Research evidence "+i));
        byte[] bytes=json.writeValueAsBytes(segments);String ref="extractions/"+jobId+".json";objects.put(ref,bytes);
        var extraction=new Extraction(jobId,1,input.sha256(),ExtractionStatus.SUCCEEDED,ref,FileRules.sha256(bytes),101,null);
        processing.extraction(id,extraction);processing.extraction(id,extraction);objects.put(ref,"worker overwrite".getBytes());
        var first=processing.content(id,1,null);assertThat((List<?>)first.get("segments")).hasSize(100);assertThat((List<?>)processing.content(id,1,first.get("nextCursor").toString()).get("segments")).hasSize(1);
        UUID extractionId=(UUID)first.get("extractionId");var indexed=new Indexing(1,extractionId,"index-v1",IndexStatus.INDEXED,Instant.now(),null);processing.index(id,indexed);processing.index(id,indexed);
        long access=Documents.access(store.row("document",id));assertThat(processing.source(owner,id,new Source(owner.id(),1,access)).get("allowed")).isEqualTo(true);
        when(remote.allowed(any(),eq("AI_SOURCE_READ"),anyString(),any())).thenReturn(false);assertThat(processing.source(owner,id,new Source(owner.id(),1,access)).get("allowed")).isEqualTo(false);
        assertThatThrownBy(()->processing.source(owner,id,new Source(other.id(),1,access))).isInstanceOf(ApiException.class);
        documents.patch(owner,id,new Patch(null,Classification.INTERNAL),etag(id));assertThat(processing.source(owner,id,new Source(owner.id(),1,access)).get("allowed")).isEqualTo(false);
    }
    @Test void concurrentCompletionCreatesOneVersionAndOneScanJob() throws Exception {
        UUID session=initialized();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->documents.complete(owner,session));var b=pool.submit(()->documents.complete(owner,session));
            assertThat(a.get(10,java.util.concurrent.TimeUnit.SECONDS).get("documentId")).isEqualTo(b.get(10,java.util.concurrent.TimeUnit.SECONDS).get("documentId"));
        }
        assertThat(store.query("select * from document_version",Map.of())).hasSize(1);
        assertThat(store.query("select * from document_processing_job",Map.of())).hasSize(1);
        assertThat(store.query("select * from outbox_event",Map.of())).hasSize(1);
    }
    @Test void deletionKeepsOwnerMetadataForConditionalRestoration() throws Exception {
        UUID id=clean();documents.delete(owner,id,etag(id));
        mvc.perform(get("/api/v1/documents/"+id).with(asUser())).andExpect(status().isOk()).andExpect(header().string("ETag",etag(id))).andExpect(jsonPath("$.status").value("DELETED"));
        mvc.perform(post("/api/v1/documents/"+id+"/restoration").with(asUser()).header("If-Match",etag(id))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AVAILABLE"));
    }
    @Test void outboxRequiresBrokerConfirmationAndRetainsFailuresForRetry() {
        uploaded();var rabbit=mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);var publisher=new OutboxPublisher(store,rabbit);
        doAnswer(i->{org.springframework.amqp.rabbit.connection.CorrelationData c=i.getArgument(3);c.getFuture().complete(new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(false,"unavailable"));return null;})
            .when(rabbit).send(eq("invesctictrack.domain"),eq("document.DocumentUploadCompleted.v1"),any(org.springframework.amqp.core.Message.class),any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
        publisher.publish();var pending=store.query("select * from outbox_event",Map.of()).getFirst();assertThat(pending.get("publishedAt")).isNull();assertThat(pending.get("attemptCount")).isEqualTo(1);assertThat(pending.get("nextAttemptAt")).isNotNull();
        store.update("outbox_event",(UUID)pending.get("id"),map("nextAttemptAt",Instant.now().minusSeconds(1)));
        doAnswer(i->{org.springframework.amqp.rabbit.connection.CorrelationData c=i.getArgument(3);c.getFuture().complete(new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(true,null));return null;})
            .when(rabbit).send(eq("invesctictrack.domain"),eq("document.DocumentUploadCompleted.v1"),any(org.springframework.amqp.core.Message.class),any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
        publisher.publish();assertThat(store.row("outbox_event",(UUID)pending.get("id")).get("publishedAt")).isNotNull();
    }
    @Test void securitySeparatesWorkersAndRequiresSignedScanResults() throws Exception {
        UUID id=uploaded();String uri="/internal/v1/documents/"+id;
        mvc.perform(get("/api/v1/documents")).andExpect(status().isUnauthorized());
        mvc.perform(get(uri+"/access-context").with(asUser())).andExpect(status().isForbidden());
        mvc.perform(get(uri+"/access-context").with(workload("identity-service","document:context"))).andExpect(status().isOk());
        mvc.perform(get(uri+"/access-context").with(workload("ai-service","document:context"))).andExpect(status().isForbidden());
        String body=json.writeValueAsString(scanInput(id,ScanResult.CLEAN));
        mvc.perform(post(uri+"/scan-results").with(workload("malware-scanner","document:scan")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec("test-signing-key".getBytes(),"HmacSHA256"));String signature=HexFormat.of().formatHex(mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        mvc.perform(post(uri+"/scan-results").with(workload("malware-scanner","document:scan")).header("X-Result-Signature",signature).contentType("application/json").content(body)).andExpect(status().isNoContent());
        var delegated=jwt().jwt(j->j.subject("oidc-subject").audience(List.of("document-service")).claim("client_id","project-execution-service").claim("user_id",owner.id().toString())).authorities(new SimpleGrantedAuthority("SCOPE_document:reference"));
        mvc.perform(post(uri+"/reference-validation").with(delegated).contentType("application/json").content(json.writeValueAsString(new Reference(context.projectId(),1,Purpose.FINAL_REPORT)))).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        mvc.perform(post(uri+"/reference-validation").with(workload("project-service","document:reference")).contentType("application/json").content(json.writeValueAsString(new Reference(context.projectId(),1,Purpose.FINAL_REPORT)))).andExpect(status().isForbidden());
    }
}
