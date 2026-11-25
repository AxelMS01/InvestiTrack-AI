package com.example.reportservice.api;

import com.example.reportservice.api.ApiModels.*;
import com.example.reportservice.application.*;
import com.example.reportservice.infrastructure.RemoteServices;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import static com.example.reportservice.api.ApiException.require;

@RestController
@RequestMapping("/api/v1")
public class ReportingController {
    private final Queries queries;
    private final Reports reports;
    private final RemoteServices remote;
    public ReportingController(Queries queries,Reports reports,RemoteServices remote){this.queries=queries;this.reports=reports;this.remote=remote;}
    @GetMapping("/dashboards/{view:researcher|coordinator|evaluator}")
    public Map<String,Object> dashboard(@AuthenticationPrincipal Jwt jwt,@PathVariable String view,@RequestParam Map<String,String> filters) {
        filters(filters,Set.of("researchArea","status","from","to","coordinatorId"));return queries.dashboard(remote.caller(jwt),view,null,filters);
    }
    @GetMapping("/dashboards/projects/{projectId}")
    public Map<String,Object> project(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID projectId,@RequestParam Map<String,String> filters) {
        filters(filters,Set.of("researchArea","status","from","to","coordinatorId"));return queries.dashboard(remote.caller(jwt),"project",projectId,filters);
    }
    @GetMapping({"/deadlines","/projects/{projectId}/deadlines"})
    public Map<String,Object> deadlines(@AuthenticationPrincipal Jwt jwt,@PathVariable(required=false) UUID projectId,@RequestParam Map<String,String> filters) {
        filters(filters,Set.of("state","type","from","to","responsibleUserId","projectId","limit","cursor"));return queries.deadlines(remote.caller(jwt),projectId,filters);
    }
    @GetMapping("/report-definitions") public Map<String,Object> definitions(@AuthenticationPrincipal Jwt jwt){return Map.of("items",reports.definitions(remote.caller(jwt)));}
    @GetMapping("/report-definitions/{code}") public Map<String,Object> definition(@AuthenticationPrincipal Jwt jwt,@PathVariable String code){return reports.definition(remote.caller(jwt),code);}
    @PostMapping("/report-jobs")
    public ResponseEntity<Map<String,Object>> create(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody CreateReport input,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        var response=reports.create(remote.caller(jwt),input,key);return ResponseEntity.accepted().location(URI.create("/api/v1/report-jobs/"+response.get("id"))).body(response);
    }
    @GetMapping("/report-jobs") public Map<String,Object> list(@AuthenticationPrincipal Jwt jwt,@RequestParam Map<String,String> filters){filters(filters,Set.of("limit","cursor"));return queries.page(reports.list(remote.caller(jwt)),filters,"id");}
    @GetMapping("/report-jobs/{id}") public Map<String,Object> get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return reports.get(remote.caller(jwt),id);}
    @DeleteMapping("/report-jobs/{id}") public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){reports.cancel(remote.caller(jwt),id);return ResponseEntity.noContent().build();}
    @PostMapping("/report-jobs/{id}/download-authorization") public Map<String,Object> authorize(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return reports.authorize(remote.caller(jwt),id);}
    @GetMapping("/report-jobs/{id}/content")
    public ResponseEntity<byte[]> content(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestParam String token) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"report-"+id+".csv\"")
            .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(reports.download(remote.caller(jwt),id,token));
    }
    @GetMapping("/reference/statuses")
    public Map<String,Object> statuses(@AuthenticationPrincipal Jwt jwt) {
        remote.caller(jwt);
        var catalog=Map.of("PROJECT",List.of("APPROVED","ACTIVE","DELAYED","COMPLETED","CLOSED","CANCELLED"),
            "ACTIVITY",List.of("PENDING","IN_PROGRESS","BLOCKED","COMPLETED","CANCELLED"),
            "MILESTONE",List.of("PENDING","IN_PROGRESS","COMPLETED","CANCELLED"),
            "PROPOSAL",List.of("DRAFT","SUBMITTED","UNDER_REVIEW","CHANGES_REQUESTED","APPROVED","REJECTED","WITHDRAWN"),
            "EVALUATOR_ASSIGNMENT",List.of("ASSIGNED","ACCEPTED","DECLINED","COMPLETED","REVOKED"),
            "REPORT_JOB",List.of("QUEUED","RUNNING","COMPLETED","FAILED","CANCELLED","EXPIRED"),
            "DEADLINE",List.of("FUTURE","UPCOMING","OVERDUE","COMPLETED"));
        var items=new ArrayList<Map<String,String>>();catalog.forEach((type,statuses)->statuses.forEach(code->items.add(Map.of("resourceType",type,"code",code,"label",code.charAt(0)+code.substring(1).toLowerCase(Locale.ROOT).replace('_',' ')))));
        items.sort(Comparator.comparing((Map<String,String> item)->item.get("resourceType")).thenComparing(item->item.get("code")));
        return Map.of("items",items);
    }
    private void filters(Map<String,String> f,Set<String> allowed) {
        require(allowed.containsAll(f.keySet())&&f.values().stream().noneMatch(String::isBlank),400,"INVALID_FILTER","Unsupported or empty filter.");
        for(String key:List.of("from","to"))if(f.containsKey(key))LocalDate.parse(f.get(key));
        if(f.containsKey("from")&&f.containsKey("to"))require(f.get("from").compareTo(f.get("to"))<=0,400,"INVALID_DATE_RANGE","from must not exceed to.");
        for(String key:List.of("coordinatorId","projectId","responsibleUserId"))if(f.containsKey(key))UUID.fromString(f.get(key));
        if(f.containsKey("type"))require(Set.of("ACTIVITY","MILESTONE").contains(f.get("type")),400,"INVALID_FILTER","Invalid deadline type.");
        if(f.containsKey("state"))require(Set.of("UPCOMING","OVERDUE","FUTURE","COMPLETED").contains(f.get("state")),400,"INVALID_FILTER","Invalid deadline state.");
    }
}
