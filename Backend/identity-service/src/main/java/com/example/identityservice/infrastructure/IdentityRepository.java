package com.example.identityservice.infrastructure;

import com.example.identityservice.domain.User;
import com.example.identityservice.domain.User.Status;
import com.example.identityservice.api.ApiException;
import com.example.identityservice.api.ApiModels.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@Repository
public class IdentityRepository {
    private final JdbcClient db;
    public IdentityRepository(JdbcClient db) { this.db = db; }
    public JdbcClient db() { return db; }
    public void lockWrites() { db.sql("select version from identity_policy where id=1 for update").query(Long.class).single(); }
    public String policyVersion() { return "identity-" + db.sql("select version from identity_policy where id=1").query(Long.class).single(); }
    private static Instant time(ResultSet r, String key) throws SQLException {
        var t = r.getTimestamp(key); return t == null ? null : t.toInstant();
    }
    private static User map(ResultSet r, int row) throws SQLException {
        return new User(r.getObject("id", UUID.class), r.getString("identity_subject"), r.getString("institutional_id"),
            r.getString("email"), r.getString("display_name"), r.getString("given_name"), r.getString("family_name"),
            Status.valueOf(r.getString("status")), r.getString("locale"), time(r,"last_authenticated_at"),
            time(r,"created_at"), r.getObject("created_by",UUID.class), time(r,"updated_at"),
            r.getObject("updated_by",UUID.class), r.getLong("version"));
    }
    public Optional<User> find(UUID id) { return db.sql("select * from app_user where id=:id").param("id",id).query(IdentityRepository::map).optional(); }
    public User user(UUID id) { return find(id).orElseThrow(() -> new ApiException(404,"USER_NOT_FOUND","User does not exist.")); }
    public Optional<User> subject(String subject) { return db.sql("select * from app_user where identity_subject=:s").param("s",subject).query(IdentityRepository::map).optional(); }
    public List<String> roles(UUID user) {
        return db.sql("select r.code from user_role ur join app_role r on r.id=ur.role_id where ur.user_id=:u and r.active=true order by r.code")
            .param("u",user).query(String.class).list();
    }
    public List<String> capabilities(UUID user) {
        return db.sql("""
            select distinct p.code from user_role ur join app_role r on r.id=ur.role_id
            join role_permission rp on rp.role_id=r.id join app_permission p on p.id=rp.permission_id
            where ur.user_id=:u and r.active=true and p.active=true order by p.code
            """).param("u",user).query(String.class).list();
    }
    public UserView view(User u) {
        return new UserView(u.id(),u.institutionalId(),u.displayName(),u.email(),u.givenName(),u.familyName(),
            u.locale(),u.status(),roles(u.id()),u.active()?capabilities(u.id()):List.of(),u.version());
    }
    public List<RoleView> catalog() {
        return db.sql("select code,name,description,assignable from app_role where active=true order by code")
            .query((r,n) -> new RoleView(r.getString("code"),r.getString("name"),r.getString("description"),r.getBoolean("assignable"),
                db.sql("select p.code from app_permission p join role_permission rp on rp.permission_id=p.id join app_role a on a.id=rp.role_id where a.code=:c and p.active=true order by p.code")
                    .param("c",r.getString("code")).query(String.class).list())).list();
    }
    public void validateRoles(Collection<String> codes) {
        if (new HashSet<>(codes).size()!=codes.size()) throw new ApiException(400,"DUPLICATE_ROLE","Roles must be unique.");
        var allowed = catalog().stream().filter(RoleView::assignable).map(RoleView::code).toList();
        if (!allowed.containsAll(codes)) throw new ApiException(422,"INVALID_ROLE","Every role must be active and assignable.");
    }
    public void assign(UUID user, Collection<String> roles, UUID actor, String reason) {
        db.sql("delete from user_role where user_id=:u").param("u",user).update();
        for (var role : roles) db.sql("insert into user_role(user_id,role_id,assigned_at,assigned_by,reason) select :u,id,:t,:a,:r from app_role where code=:c")
            .param("u",user).param("t",java.sql.Timestamp.from(Instant.now())).param("a",actor).param("r",reason).param("c",role).update();
    }
    public void insert(User u) {
        db.sql("""
            insert into app_user(id,identity_subject,institutional_id,email,display_name,given_name,family_name,status,locale,
              created_at,created_by,updated_at,updated_by,version)
            values(:id,:s,:i,:e,:d,:g,:f,:st,:l,:t,:a,:t,:a,:v)
            """).param("id",u.id()).param("s",u.identitySubject()).param("i",u.institutionalId()).param("e",u.email().toLowerCase(Locale.ROOT))
            .param("d",u.displayName()).param("g",u.givenName()).param("f",u.familyName()).param("st",u.status().name())
            .param("l",u.locale()).param("t",java.sql.Timestamp.from(u.createdAt())).param("a",u.createdBy()).param("v",u.version()).update();
    }
    public void history(UUID user, Status previous, Status next, String reason, UUID actor) {
        db.sql("insert into user_status_history(id,user_id,previous_status,new_status,reason,changed_at,changed_by) values(:id,:u,:p,:n,:r,:t,:a)")
            .param("id",UUID.randomUUID()).param("u",user).param("p",previous==null?null:previous.name()).param("n",next.name())
            .param("r",reason).param("t",java.sql.Timestamp.from(Instant.now())).param("a",actor).update();
    }
    public List<User> search(String q, List<String> statuses, String role, UUID after, int limit) {
        String sql="select u.* from app_user u where 1=1";
        var params=new HashMap<String,Object>();
        if(q!=null && !q.isBlank()) {
            sql+=" and (lower(u.display_name) like :q escape '!' or lower(u.email) like :q escape '!' or lower(u.institutional_id) like :q escape '!')";
            params.put("q","%"+q.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");
        }
        if(statuses!=null && !statuses.isEmpty()) { sql+=" and u.status in (:statuses)"; params.put("statuses",statuses); }
        if(role!=null) { sql+=" and exists(select 1 from user_role ur join app_role r on r.id=ur.role_id where ur.user_id=u.id and r.code=:role and r.active=true)"; params.put("role",role); }
        if(after!=null) { sql+=" and u.id>:after"; params.put("after",after); }
        sql+=" order by u.id limit :limit"; params.put("limit",limit);
        return db.sql(sql).params(params).query(IdentityRepository::map).list();
    }
    public long activeAdmins() {
        return db.sql("select count(*) from app_user u join user_role ur on ur.user_id=u.id join app_role r on r.id=ur.role_id where u.status='ACTIVE' and r.code='ADMIN' and r.active=true").query(Long.class).single();
    }
}
