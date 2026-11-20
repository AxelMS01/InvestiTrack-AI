package com.example.identityservice.application;

import com.example.identityservice.api.*;
import com.example.identityservice.api.ApiModels.*;
import com.example.identityservice.domain.User;
import com.example.identityservice.domain.User.Status;
import com.example.identityservice.infrastructure.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static com.example.identityservice.api.ApiException.require;

@Service
@Transactional
public class IdentityService {
    private final IdentityRepository repo;
    private final DomainEvents events;
    private final ObjectMapper json;
    public IdentityService(IdentityRepository repo, DomainEvents events, ObjectMapper json) { this.repo=repo; this.events=events; this.json=json; }
    public User caller(Jwt jwt) {
        var u=repo.subject(jwt.getSubject()).orElseThrow(() -> new ApiException(403,"USER_MAPPING_REQUIRED","The institutional subject has not been provisioned."));
        require(u.active(),403,"ACCOUNT_INACTIVE","The application account is not active.");
        String signedId=jwt.getClaimAsString("user_id");
        require(signedId==null || u.id().toString().equals(signedId),403,"USER_MAPPING_MISMATCH","The signed application ID does not match the subject.");
        return u;
    }
    public void admin(User caller) { require(repo.roles(caller.id()).contains("ADMIN"),403,"FORBIDDEN","Administrator role is required."); }
    public boolean administrator(User caller) { return repo.roles(caller.id()).contains("ADMIN"); }
    public void lookup(User caller) { require(administrator(caller)||repo.roles(caller.id()).contains("COORDINATOR"),403,"FORBIDDEN","User lookup is not permitted."); }
    public UserView me(Jwt jwt) {
        var u=caller(jwt);
        repo.db().sql("update app_user set last_authenticated_at=:t where id=:id").param("t",java.sql.Timestamp.from(Instant.now())).param("id",u.id()).update();
        return repo.view(u);
    }
    public UserView get(User actor, UUID id) {
        if(!actor.id().equals(id)) admin(actor);
        return repo.view(repo.user(id));
    }
    public Object lookupUser(User actor, UUID id) {
        if(administrator(actor)||actor.id().equals(id)) return repo.view(repo.user(id));
        lookup(actor); var u=repo.user(id); return new LookupUser(u.id(),u.institutionalId(),u.displayName());
    }
    public UserView provision(User actor, CreateUser in, String key) {
        repo.lockWrites(); activeActor(actor); admin(repo.user(actor.id()));
        String hash=sha(json.writeValueAsString(in));
        if(key!=null) {
            require(!key.isBlank() && key.length()<=200,400,"INVALID_IDEMPOTENCY_KEY","Idempotency-Key must contain 1 to 200 characters.");
            var saved=repo.db().sql("select request_hash,response_body from idempotency_record where caller_id=:a and operation='CREATE_USER' and idempotency_key=:k and expires_at>:t")
                .param("a",actor.id().toString()).param("k",key).param("t",java.sql.Timestamp.from(Instant.now())).query((r,n)->Map.of("hash",r.getString(1),"body",r.getString(2))).optional();
            if(saved.isPresent()) {
                require(saved.get().get("hash").equals(hash),409,"IDEMPOTENCY_CONFLICT","The idempotency key was used with a different request.");
                return json.readValue(saved.get().get("body"),UserView.class);
            }
            repo.db().sql("delete from idempotency_record where caller_id=:a and operation='CREATE_USER' and idempotency_key=:k")
                .param("a",actor.id().toString()).param("k",key).update();
        }
        var roles=in.roles()==null?List.<String>of():in.roles(); repo.validateRoles(roles);
        var status=in.status()==null?Status.ACTIVE:in.status();
        require(status!=Status.DEACTIVATED,422,"INVALID_INITIAL_STATUS","New accounts must be ACTIVE or PENDING.");
        var now=Instant.now();
        var u=new User(UUID.randomUUID(),in.identitySubject().trim(),blank(in.institutionalId()),in.email().trim(),in.displayName().trim(),
            blank(in.givenName()),blank(in.familyName()),status,blank(in.locale()),null,now,actor.id(),now,actor.id(),1);
        repo.insert(u); repo.assign(u.id(),roles,actor.id(),"Initial provisioning");
        repo.history(u.id(),null,status,"Initial provisioning",actor.id());
        events.user("UserCreated",u,"USER",actor.id().toString());
        if(!roles.isEmpty()) events.user("UserRolesChanged",u,"USER",actor.id().toString());
        var view=repo.view(u);
        if(key!=null) repo.db().sql("insert into idempotency_record values(:a,'CREATE_USER',:k,:h,:b,:id,:t,:e)")
            .param("a",actor.id().toString()).param("k",key).param("h",hash).param("b",json.writeValueAsString(view))
            .param("id",u.id()).param("t",java.sql.Timestamp.from(now)).param("e",java.sql.Timestamp.from(now.plus(Duration.ofHours(24)))).update();
        return view;
    }
    public UserView patch(User actor, UUID id, PatchUser in, String match, boolean self) {
        repo.lockWrites(); activeActor(actor); if(!self) admin(actor);
        require(!self || actor.id().equals(id),403,"FORBIDDEN","Only the current profile can be changed.");
        require(!self || (in.email()==null && in.institutionalId()==null),422,"PROFILE_FIELD_RESTRICTED","Only administrators may change email or institutional ID.");
        var u=repo.user(id); version(u,match);
        require(in.displayName()==null || !in.displayName().isBlank(),422,"INVALID_DISPLAY_NAME","Display name cannot be blank.");
        repo.db().sql("""
            update app_user set display_name=:d,given_name=:g,family_name=:f,email=:e,institutional_id=:i,locale=:l,
                updated_at=:t,updated_by=:a,version=version+1 where id=:id and version=:v
            """).param("d",in.displayName()==null?u.displayName():in.displayName().trim())
            .param("g",in.givenName()==null?u.givenName():blank(in.givenName())).param("f",in.familyName()==null?u.familyName():blank(in.familyName()))
            .param("e",in.email()==null?u.email():in.email().trim().toLowerCase(Locale.ROOT))
            .param("i",in.institutionalId()==null?u.institutionalId():blank(in.institutionalId()))
            .param("l",in.locale()==null?u.locale():blank(in.locale())).param("t",java.sql.Timestamp.from(Instant.now())).param("a",actor.id())
            .param("id",id).param("v",u.version()).update();
        var next=repo.user(id); events.user("UserProfileChanged",next,"USER",actor.id().toString()); return repo.view(next);
    }
    public UserView state(User actor, UUID id, boolean active, String reason, String match) {
        repo.lockWrites(); activeActor(actor); admin(actor); var u=repo.user(id); version(u,match);
        var target=active?Status.ACTIVE:Status.DEACTIVATED;
        if(u.status()==target) return repo.view(u);
        if(!active) protectLastAdmin(u,Set.of(),false);
        repo.db().sql("update app_user set status=:s,version=version+1,updated_at=:t,updated_by=:a where id=:id")
            .param("s",target.name()).param("t",java.sql.Timestamp.from(Instant.now())).param("a",actor.id()).param("id",id).update();
        repo.history(id,u.status(),target,reason,actor.id()); var next=repo.user(id);
        events.user(active?"UserReactivated":"UserDeactivated",next,"USER",actor.id().toString()); return repo.view(next);
    }
    public UserView roles(User actor, UUID id, List<String> requested, String reason, String match, String add, String remove) {
        repo.lockWrites(); activeActor(actor); admin(actor); var u=repo.user(id); version(u,match);
        var current=repo.roles(id); var next=new TreeSet<>(current);
        if(requested!=null) { repo.validateRoles(requested); next=new TreeSet<>(requested); }
        if(add!=null) { repo.validateRoles(List.of(add)); next.add(add); }
        if(remove!=null) { repo.validateRoles(List.of(remove)); next.remove(remove); }
        protectLastAdmin(u,next,u.active());
        if(next.equals(new TreeSet<>(current))) return repo.view(u);
        // Preserve attribution of unchanged assignments.
        for(var role:current) if(!next.contains(role)) repo.db().sql("delete from user_role where user_id=:u and role_id=(select id from app_role where code=:c)").param("u",id).param("c",role).update();
        for(var role:next) if(!current.contains(role)) repo.db().sql("insert into user_role(user_id,role_id,assigned_at,assigned_by,reason) select :u,id,:t,:a,:r from app_role where code=:c")
            .param("u",id).param("t",java.sql.Timestamp.from(Instant.now())).param("a",actor.id()).param("r",reason).param("c",role).update();
        repo.db().sql("update app_user set version=version+1,updated_at=:t,updated_by=:a where id=:id").param("t",java.sql.Timestamp.from(Instant.now())).param("a",actor.id()).param("id",id).update();
        var changed=repo.user(id); events.user("UserRolesChanged",changed,"USER",actor.id().toString()); return repo.view(changed);
    }
    private void activeActor(User actor) { require(repo.user(actor.id()).active(),403,"ACCOUNT_INACTIVE","The acting account is inactive."); }
    private void protectLastAdmin(User u, Set<String> roles, boolean active) {
        require(!u.active() || !repo.roles(u.id()).contains("ADMIN") || (active && roles.contains("ADMIN")) || repo.activeAdmins()>1,
            409,"LAST_ADMIN_REQUIRED","At least one active administrator must remain.");
    }
    public static void version(User u, String header) {
        require(header!=null,428,"PRECONDITION_REQUIRED","If-Match is required.");
        require(header.matches("\"[0-9]+\""),400,"INVALID_ETAG","If-Match must contain one quoted numeric version.");
        require(header.equals("\""+u.version()+"\""),412,"VERSION_CONFLICT","The user was changed. Fetch the current version.");
    }
    public Page<?> search(User actor, String q, List<String> statuses, String role, int limit, String cursor) {
        lookup(actor); require(limit>=1 && limit<=100,400,"INVALID_LIMIT","Limit must be between 1 and 100.");
        if(statuses!=null) for(var s:statuses) try { Status.valueOf(s); } catch(Exception e) { throw new ApiException(400,"INVALID_STATUS","Unknown status."); }
        String scope=sha(Objects.toString(q,"")+"|"+statuses+"|"+role+"|"+actor.id()); UUID after=null;
        if(cursor!=null) try {
            var parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split(":",2);
            require(parts.length==2 && parts[0].equals(scope),400,"INVALID_CURSOR","Cursor does not match this query."); after=UUID.fromString(parts[1]);
        } catch(IllegalArgumentException ex) { throw new ApiException(400,"INVALID_CURSOR","Cursor is malformed."); }
        var found=repo.search(q,statuses,role,after,limit+1); boolean more=found.size()>limit;
        var rows=found.subList(0,Math.min(limit,found.size()));
        String next=more?Base64.getUrlEncoder().withoutPadding().encodeToString((scope+":"+rows.getLast().id()).getBytes(StandardCharsets.UTF_8)):null;
        List<?> items=administrator(actor)?rows.stream().map(repo::view).toList():rows.stream().map(u->new LookupUser(u.id(),u.institutionalId(),u.displayName())).toList();
        return new Page<>(items,new PageInfo(limit,next,more));
    }
    static String blank(String s) { return s==null||s.isBlank()?null:s.trim(); }
    public static String sha(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
