package com.example.projectservice.api;

import com.example.projectservice.api.ApiModels.*;
import com.example.projectservice.application.ProjectService;
import com.example.projectservice.infrastructure.RemoteServices;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class ProjectController {
    private final ProjectService service;
    private final RemoteServices remote;
    public ProjectController(ProjectService service,RemoteServices remote){this.service=service;this.remote=remote;}
    private ResponseEntity<Map<String,Object>> response(Map<String,Object> body) {
        var result=ResponseEntity.ok();if(body.get("version") instanceof Number version)result.eTag("\""+version.longValue()+"\"");return result.body(body);
    }
    private ResponseEntity<Map<String,Object>> result(ProjectService.Result result,String collection) {
        var response=ResponseEntity.status(result.status());
        if(result.status()==201)response.location(URI.create(collection+"/"+(collection.endsWith("/evidence")?result.body().get("documentId"):result.body().getOrDefault("id",result.body().get("userId")))));
        if(result.body().get("version") instanceof Number version)response.eTag("\""+version.longValue()+"\"");return response.body(result.body());
    }
    private ResponseEntity<Page<Map<String,Object>>> page(Page<Map<String,Object>> page,Caller caller,UUID project) {
        return ResponseEntity.ok().eTag("\""+service.projectVersion(caller,project)+"\"").body(page);
    }
    private Map<String,String> filters(Map<String,String> raw,Set<String> allowed) {
        var result=new HashMap<>(raw);result.keySet().removeAll(Set.of("limit","cursor","status"));
        ApiException.require(allowed.containsAll(result.keySet()),400,"INVALID_FILTER","An unsupported filter or sort was supplied.");return result;
    }
    @GetMapping({"/projects","/me/projects"})
    public Page<Map<String,Object>> projects(@AuthenticationPrincipal Jwt jwt,@RequestParam Map<String,String> filters,
        @RequestParam(required=false,defaultValue="") List<String> status,@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor) {
        return service.projects(remote.caller(jwt),filters(filters,Set.of("q","researchArea","memberId","coordinatorId","delayed","deadlineFrom","deadlineTo","sort")),status,limit,cursor);
    }
    @PostMapping("/projects")
    public ResponseEntity<Map<String,Object>> create(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody CreateProject input,
        @RequestHeader(value="Idempotency-Key",required=false) String key) {
        var caller=remote.caller(jwt);return result(service.idempotent(caller,"POST /projects",key,input,()->service.create(caller,input)),"/api/v1/projects");
    }
    @GetMapping("/projects/{project}")
    public ResponseEntity<Map<String,Object>> project(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project){return response(service.get(remote.caller(jwt),project));}
    @PatchMapping("/projects/{project}")
    public ResponseEntity<Map<String,Object>> patch(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@Valid @RequestBody PatchProject input,@RequestHeader(value="If-Match",required=false) String match){return response(service.patch(remote.caller(jwt),project,input,match));}
    @PostMapping("/projects/{project}/transitions")
    public ResponseEntity<Map<String,Object>> transition(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@Valid @RequestBody ProjectTransition input,@RequestHeader(value="If-Match",required=false) String match){return response(service.transition(remote.caller(jwt),project,input,match));}
    @GetMapping("/projects/{project}/history")
    public ResponseEntity<Page<Map<String,Object>>> history(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor){var c=remote.caller(jwt);return page(service.history(c,project,limit,cursor),c,project);}
    @GetMapping("/projects/{project}/progress")
    public ResponseEntity<Map<String,Object>> progress(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project){return response(service.progress(remote.caller(jwt),project));}
    @PostMapping("/projects/{project}/progress/recalculation")
    public ResponseEntity<Map<String,Object>> recalculate(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@RequestHeader(value="If-Match",required=false) String match){return response(service.recalculate(remote.caller(jwt),project,match));}
    @GetMapping("/projects/{project}/members")
    public ResponseEntity<Page<Map<String,Object>>> members(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor){var c=remote.caller(jwt);return page(service.members(c,project,limit,cursor),c,project);}
    @GetMapping("/projects/{project}/members/{user}")
    public ResponseEntity<Map<String,Object>> member(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID user){return response(service.member(remote.caller(jwt),project,user));}
    @PostMapping("/projects/{project}/members")
    public ResponseEntity<Map<String,Object>> addMember(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@Valid @RequestBody Member input,@RequestHeader(value="If-Match",required=false) String match,@RequestHeader(value="Idempotency-Key",required=false) String key){var c=remote.caller(jwt);return result(service.idempotent(c,"POST /projects/"+project+"/members",key,input,()->new ProjectService.Result(201,service.addMember(c,project,input,match))),"/api/v1/projects/"+project+"/members");}
    @PatchMapping("/projects/{project}/members/{user}")
    public ResponseEntity<Map<String,Object>> patchMember(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID user,@Valid @RequestBody PatchMember input,@RequestHeader(value="If-Match",required=false) String match){return response(service.patchMember(remote.caller(jwt),project,user,input,match));}
    @DeleteMapping("/projects/{project}/members/{user}")
    public ResponseEntity<Void> removeMember(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID user,@RequestHeader(value="If-Match",required=false) String match){service.removeMember(remote.caller(jwt),project,user,match);return ResponseEntity.noContent().build();}
    @GetMapping("/projects/{project}/final-report")
    public ResponseEntity<Map<String,Object>> report(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project){return response(service.finalReport(remote.caller(jwt),project));}
    @PutMapping("/projects/{project}/final-report")
    public ResponseEntity<Map<String,Object>> report(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@Valid @RequestBody Report input,@RequestHeader(value="If-Match",required=false) String match){return response(service.putReport(remote.caller(jwt),project,input,match));}
    @PostMapping("/projects/{project}/final-report/submission")
    public ResponseEntity<Map<String,Object>> submitReport(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@RequestHeader(value="If-Match",required=false) String match){return response(service.submitReport(remote.caller(jwt),project,match));}
    @PostMapping("/projects/{project}/final-report/reopening")
    public ResponseEntity<Map<String,Object>> reopenReport(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@Valid @RequestBody Reason input,@RequestHeader(value="If-Match",required=false) String match){return response(service.reopenReport(remote.caller(jwt),project,input,match));}
    @GetMapping("/projects/{project}/publications")
    public ResponseEntity<Page<Map<String,Object>>> publications(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor){var c=remote.caller(jwt);return page(service.publications(c,project,limit,cursor),c,project);}
    @GetMapping("/projects/{project}/publications/{publication}")
    public ResponseEntity<Map<String,Object>> publication(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID publication){return response(service.publication(remote.caller(jwt),project,publication));}
    @PostMapping("/projects/{project}/publications")
    public ResponseEntity<Map<String,Object>> addPublication(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@Valid @RequestBody PublicationInput input,@RequestHeader(value="If-Match",required=false) String match,@RequestHeader(value="Idempotency-Key",required=false) String key){var c=remote.caller(jwt);return result(service.idempotent(c,"POST /projects/"+project+"/publications",key,input,()->new ProjectService.Result(201,service.addPublication(c,project,input,match))),"/api/v1/projects/"+project+"/publications");}
    @PatchMapping("/projects/{project}/publications/{publication}")
    public ResponseEntity<Map<String,Object>> patchPublication(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID publication,@Valid @RequestBody PublicationPatch input,@RequestHeader(value="If-Match",required=false) String match){return response(service.patchPublication(remote.caller(jwt),project,publication,input,match));}
    @DeleteMapping("/projects/{project}/publications/{publication}")
    public ResponseEntity<Void> removePublication(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID publication,@RequestHeader(value="If-Match",required=false) String match){service.deletePublication(remote.caller(jwt),project,publication,match);return ResponseEntity.noContent().build();}
    @GetMapping("/reference/publication-types")
    public Map<String,Object> publicationTypes(@AuthenticationPrincipal Jwt jwt){remote.caller(jwt);return Map.of("items",Arrays.stream(PublicationType.values()).map(type->Map.of("code",type.name())).toList());}

    @GetMapping("/projects/{project}/activities")
    public ResponseEntity<Page<Map<String,Object>>> activities(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@RequestParam Map<String,String> filters,@RequestParam(required=false,defaultValue="") List<String> status,@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor) {var c=remote.caller(jwt);return page(service.items(c,project,"activity",filters(filters,Set.of("responsibleUserId","milestoneId","overdue","deadlineFrom","deadlineTo","sort")),status,limit,cursor),c,project);}
    @GetMapping("/projects/{project}/activities/{item}")
    public ResponseEntity<Map<String,Object>> activity(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID item){return response(service.item(remote.caller(jwt),project,item,"activity"));}
    @PostMapping("/projects/{project}/activities")
    public ResponseEntity<Map<String,Object>> addActivity(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@Valid @RequestBody ActivityInput input,@RequestHeader(value="If-Match",required=false) String match,@RequestHeader(value="Idempotency-Key",required=false) String key){var c=remote.caller(jwt);return result(service.idempotent(c,"POST /projects/"+project+"/activities",key,input,()->new ProjectService.Result(201,service.addActivity(c,project,input,match))),"/api/v1/projects/"+project+"/activities");}
    @PatchMapping("/projects/{project}/activities/{item}")
    public ResponseEntity<Map<String,Object>> patchActivity(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID item,@Valid @RequestBody ActivityPatch input,@RequestHeader(value="If-Match",required=false) String match){return response(service.patchActivity(remote.caller(jwt),project,item,input,match));}
    @DeleteMapping("/projects/{project}/activities/{item}")
    public ResponseEntity<Void> deleteActivity(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID item,@RequestHeader(value="If-Match",required=false) String match){service.deleteItem(remote.caller(jwt),project,item,"activity",match);return ResponseEntity.noContent().build();}
    @PostMapping("/projects/{project}/activities/{item}/transitions")
    public ResponseEntity<Map<String,Object>> transitionActivity(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID item,@Valid @RequestBody ActivityTransition input,@RequestHeader(value="If-Match",required=false) String match){return response(service.itemTransition(remote.caller(jwt),project,item,"activity",input.targetStatus().name(),input.reason(),match));}
    @GetMapping("/projects/{project}/activities/{item}/evidence")
    public ResponseEntity<Page<Map<String,Object>>> evidenceActivity(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID item,@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor){var c=remote.caller(jwt);return ResponseEntity.ok().eTag("\""+ProjectService.version(service.item(c,project,item,"activity"))+"\"").body(service.evidence(c,project,item,"activity",limit,cursor));}
    @PostMapping("/projects/{project}/activities/{item}/evidence")
    public ResponseEntity<Map<String,Object>> linkActivityEvidence(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID item,@Valid @RequestBody Evidence input,@RequestHeader(value="If-Match",required=false) String match,@RequestHeader(value="Idempotency-Key",required=false) String key){var c=remote.caller(jwt);return result(service.idempotent(c,"POST /projects/"+project+"/activities/"+item+"/evidence",key,input,()->new ProjectService.Result(201,service.addEvidence(c,project,item,"activity",input,match))),"/api/v1/projects/"+project+"/activities/"+item+"/evidence");}
    @DeleteMapping("/projects/{project}/activities/{item}/evidence/{document}")
    public ResponseEntity<Void> unlinkActivityEvidence(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID item,@PathVariable UUID document,@RequestHeader(value="If-Match",required=false) String match){service.removeEvidence(remote.caller(jwt),project,item,"activity",document,match);return ResponseEntity.noContent().build();}

    @GetMapping("/projects/{project}/milestones")
    public ResponseEntity<Page<Map<String,Object>>> milestones(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@RequestParam Map<String,String> filters,@RequestParam(required=false,defaultValue="") List<String> status,@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor) {var c=remote.caller(jwt);return page(service.items(c,project,"milestone",filters(filters,Set.of("responsibleUserId","milestoneId","overdue","deadlineFrom","deadlineTo","sort")),status,limit,cursor),c,project);}
    @GetMapping("/projects/{project}/milestones/{item}")
    public ResponseEntity<Map<String,Object>> milestone(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID item){return response(service.item(remote.caller(jwt),project,item,"milestone"));}
    @PostMapping("/projects/{project}/milestones")
    public ResponseEntity<Map<String,Object>> addMilestone(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@Valid @RequestBody MilestoneInput input,@RequestHeader(value="If-Match",required=false) String match,@RequestHeader(value="Idempotency-Key",required=false) String key){var c=remote.caller(jwt);return result(service.idempotent(c,"POST /projects/"+project+"/milestones",key,input,()->new ProjectService.Result(201,service.addMilestone(c,project,input,match))),"/api/v1/projects/"+project+"/milestones");}
    @PatchMapping("/projects/{project}/milestones/{item}")
    public ResponseEntity<Map<String,Object>> patchMilestone(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID item,@Valid @RequestBody MilestonePatch input,@RequestHeader(value="If-Match",required=false) String match){return response(service.patchMilestone(remote.caller(jwt),project,item,input,match));}
    @DeleteMapping("/projects/{project}/milestones/{item}")
    public ResponseEntity<Void> deleteMilestone(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID item,@RequestHeader(value="If-Match",required=false) String match){service.deleteItem(remote.caller(jwt),project,item,"milestone",match);return ResponseEntity.noContent().build();}
    @PostMapping("/projects/{project}/milestones/{item}/transitions")
    public ResponseEntity<Map<String,Object>> transitionMilestone(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID item,@Valid @RequestBody MilestoneTransition input,@RequestHeader(value="If-Match",required=false) String match){return response(service.itemTransition(remote.caller(jwt),project,item,"milestone",input.targetStatus().name(),input.reason(),match));}
    @GetMapping("/projects/{project}/milestones/{item}/evidence")
    public ResponseEntity<Page<Map<String,Object>>> evidenceMilestone(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID item,@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor){var c=remote.caller(jwt);return ResponseEntity.ok().eTag("\""+ProjectService.version(service.item(c,project,item,"milestone"))+"\"").body(service.evidence(c,project,item,"milestone",limit,cursor));}
    @PostMapping("/projects/{project}/milestones/{item}/evidence")
    public ResponseEntity<Map<String,Object>> linkMilestoneEvidence(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID item,@Valid @RequestBody Evidence input,@RequestHeader(value="If-Match",required=false) String match,@RequestHeader(value="Idempotency-Key",required=false) String key){var c=remote.caller(jwt);return result(service.idempotent(c,"POST /projects/"+project+"/milestones/"+item+"/evidence",key,input,()->new ProjectService.Result(201,service.addEvidence(c,project,item,"milestone",input,match))),"/api/v1/projects/"+project+"/milestones/"+item+"/evidence");}
    @DeleteMapping("/projects/{project}/milestones/{item}/evidence/{document}")
    public ResponseEntity<Void> unlinkMilestoneEvidence(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID item,@PathVariable UUID document,@RequestHeader(value="If-Match",required=false) String match){service.removeEvidence(remote.caller(jwt),project,item,"milestone",document,match);return ResponseEntity.noContent().build();}

    @GetMapping("/projects/{project}/milestones/{milestone}/activities")
    public ResponseEntity<Page<Map<String,Object>>> milestoneActivities(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID project,@PathVariable UUID milestone,@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor){var c=remote.caller(jwt);service.item(c,project,milestone,"milestone");return page(service.items(c,project,"activity",Map.of("milestoneId",milestone.toString()),List.of(),limit,cursor),c,project);}
}
