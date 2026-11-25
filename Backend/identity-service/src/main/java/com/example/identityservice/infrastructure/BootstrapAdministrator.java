package com.example.identityservice.infrastructure;

import com.example.identityservice.domain.User;
import com.example.identityservice.domain.User.Status;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Explicit deployment bootstrap; never trusts role claims to provision an administrator. */
@Component
public class BootstrapAdministrator implements ApplicationRunner {
    private final IdentityRepository repo;
    private final DomainEvents events;
    private final String subject, email, name;
    public BootstrapAdministrator(IdentityRepository repo,DomainEvents events,
        @Value("${identity.bootstrap.subject:}") String subject,
        @Value("${identity.bootstrap.email:}") String email,
        @Value("${identity.bootstrap.display-name:Administrator}") String name) {
        this.repo=repo;this.events=events;this.subject=subject;this.email=email;this.name=name;
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        if(subject.isBlank()) return;
        if(email.isBlank()||!email.contains("@")) throw new IllegalArgumentException("Bootstrap requires a valid email");
        repo.lockWrites();
        // One-time initialization only. Restarting must never restore revoked privileges or reactivate an account.
        if(repo.db().sql("select count(*) from app_user").query(Long.class).single()!=0) return;
        var now=Instant.now(); var id=UUID.randomUUID();
        var user=new User(id,subject,null,email,name,null,null,Status.ACTIVE,null,null,now,id,now,id,1);
        repo.insert(user); repo.assign(id,List.of("ADMIN"),id,"Explicit deployment bootstrap");
        repo.history(id,null,Status.ACTIVE,"Explicit deployment bootstrap",id);
        events.user("UserCreated",user,"SYSTEM","identity-bootstrap");
        events.user("UserRolesChanged",user,"SYSTEM","identity-bootstrap");
    }
}
