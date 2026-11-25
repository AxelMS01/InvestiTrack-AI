package com.example.auditservice.infrastructure;

import com.example.auditservice.api.ApiException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Repository
public class AuditStore {
    private final NamedParameterJdbcTemplate db;
    private final ObjectMapper json;
    private static final Set<String> TABLES=Set.of("audit_event","audit_export_job","download_grant","idempotency_record","processed_event");
    private static final Set<String> JSON_COLUMNS=Set.of("metadata","filter_definition","response_body");
    public AuditStore(NamedParameterJdbcTemplate db,ObjectMapper json) {this.db=db;this.json=json;}
    public int execute(String sql,Map<String,?> parameters) {return db.update(sql,parameters(parameters));}
    public List<Map<String,Object>> query(String sql,Map<String,?> parameters) {return db.query(sql,parameters(parameters),(r,n)->read(r));}
    public Optional<Map<String,Object>> one(String sql,Map<String,?> parameters) {var rows=query(sql,parameters);return rows.isEmpty()?Optional.empty():Optional.of(rows.getFirst());}
    public Map<String,Object> row(String table,UUID id) {return row(table,id,false);}
    public Map<String,Object> row(String table,UUID id,boolean lock) {
        table(table);return one("select * from "+table+" where id=:id"+(lock?" for update":""),map("id",id))
            .orElseThrow(()->new ApiException(404,"RESOURCE_NOT_FOUND","The resource is absent or not visible."));
    }
    public void lock(String key) {db.query("select pg_advisory_xact_lock(hashtextextended(:key,0))",Map.of("key",key),(r,n)->0);}
    public void insert(String table,Map<String,?> values) {
        table(table);var columns=values.keySet().stream().map(AuditStore::snake).toList();
        var sql="insert into "+table+" ("+String.join(",",columns)+") values ("+values.keySet().stream()
            .map(k->JSON_COLUMNS.contains(snake(k))?"cast(:"+k+" as jsonb)":":"+k).collect(Collectors.joining(","))+")";
        execute(sql,values);
    }
    public void update(String table,UUID id,Map<String,?> changes) {
        table(table);var parameters=new LinkedHashMap<String,Object>(changes);parameters.put("rowId",id);
        var setters=changes.keySet().stream().map(k->snake(k)+"="+(JSON_COLUMNS.contains(snake(k))?"cast(:"+k+" as jsonb)":":"+k)).collect(Collectors.joining(","));
        execute("update "+table+" set "+setters+" where id=:rowId",parameters);
    }
    public static Map<String,Object> map(Object... pairs) {
        var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;
    }
    private Map<String,Object> parameters(Map<String,?> values) {
        var result=new HashMap<String,Object>();values.forEach((key,input)-> {
            Object value=input;
            if(value instanceof Instant instant)value=Timestamp.from(instant);
            else if(value instanceof Enum<?> enumeration)value=enumeration.name();
            if(JSON_COLUMNS.contains(snake(key))&&value!=null&&!(value instanceof String))value=json.writeValueAsString(value);
            result.put(key,value);
        });return result;
    }
    private Map<String,Object> read(ResultSet r)throws SQLException {
        var result=new LinkedHashMap<String,Object>();var metadata=r.getMetaData();
        for(int i=1;i<=metadata.getColumnCount();i++) {
            var value=r.getObject(i);String column=metadata.getColumnLabel(i);
            if(value instanceof Timestamp timestamp)value=timestamp.toInstant();
            else if(value instanceof java.sql.Date date)value=date.toLocalDate();
            else if(JSON_COLUMNS.contains(column)&&value!=null)value=json.readValue(value.toString(),Object.class);
            result.put(camel(column),value);
        }return result;
    }
    private static String snake(String key) {if(!key.matches("[A-Za-z][A-Za-z0-9_]*"))throw new IllegalArgumentException();return key.replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);}
    private static String camel(String key) {var s=new StringBuilder();boolean upper=false;for(char c:key.toCharArray()) {if(c=='_'){upper=true;continue;}s.append(upper?Character.toUpperCase(c):c);upper=false;}return s.toString();}
    private static void table(String table) {if(!TABLES.contains(table))throw new IllegalArgumentException("Unknown table");}
    public ObjectMapper json(){return json;}
}
