package com.example.proposalservice.api;

import org.springframework.web.context.request.*;
import java.util.UUID;
import static com.example.proposalservice.api.ApiException.require;

/** Current account UUID/roles resolved by the security filter through live Identity data. */
public record Caller(UUID id,boolean coordinator,boolean administrator,boolean researcher,boolean auditor,boolean evaluator) {
    public static final String ATTRIBUTE="proposalCaller";
    public Caller(UUID id,boolean coordinator,boolean administrator,boolean researcher,boolean auditor){this(id,coordinator,administrator,researcher,auditor,false);}
    public static Caller current() {
        var attrs=RequestContextHolder.getRequestAttributes();
        var caller=attrs instanceof ServletRequestAttributes s?s.getRequest().getAttribute(ATTRIBUTE):null;
        require(caller instanceof Caller,401,"UNAUTHENTICATED","A current authenticated account is required.");return (Caller)caller;
    }
    public void requireCoordinator(){require(coordinator,403,"FORBIDDEN","Coordinator role is required.");}
}
