package com.example.proposalservice.api;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.util.UUID;
import static com.example.proposalservice.api.ApiException.require;

/** Application user IDs are explicit signed claims; institutional subjects need not be UUIDs. */
public record Caller(UUID id, boolean coordinator, boolean administrator, boolean researcher, boolean auditor) {
    public static Caller current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        require(auth instanceof JwtAuthenticationToken, 401, "UNAUTHENTICATED", "A bearer token is required.");
        var jwt = ((JwtAuthenticationToken) auth).getToken();
        UUID id;
        try { id = UUID.fromString(jwt.getClaimAsString("user_id")); }
        catch (RuntimeException ex) { throw new ApiException(403, "USER_MAPPING_REQUIRED", "Token must contain the mapped application UUID in user_id."); }
        var roles = jwt.getClaimAsStringList("roles");
        return new Caller(id, roles != null && roles.contains("COORDINATOR"),
            roles != null && roles.contains("ADMINISTRATOR"), roles != null && roles.contains("RESEARCHER"),
            roles != null && roles.contains("AUDITOR"));
    }
    public void requireCoordinator() { require(coordinator,403,"FORBIDDEN","Coordinator role is required."); }
}
