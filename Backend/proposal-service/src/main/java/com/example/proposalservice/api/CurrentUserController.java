package com.example.proposalservice.api;

import com.example.proposalservice.api.ApiModels.*;
import com.example.proposalservice.application.ProposalApplicationService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class CurrentUserController {
    private final ProposalApplicationService service;
    public CurrentUserController(ProposalApplicationService service){this.service=service;}
    @GetMapping("/me/proposals")
    public CollectionResponse<Map<String,Object>> proposals(@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor) {
        return service.proposals(Caller.current(),null,List.of(),null,null,null,null,null,limit,cursor,"-updatedAt");
    }
    @GetMapping({"/me/evaluation-assignments","/evaluation-assignments"})
    public CollectionResponse<Map<String,Object>> assignments(@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String cursor){return service.myAssignments(Caller.current(),limit,cursor);}
}
