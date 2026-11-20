package com.example.proposalservice.api;

import com.example.proposalservice.api.ApiModels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.example.proposalservice.api.ApiException.require;

/** Stable sort with an ID tie breaker; cursors hold the last returned ordering tuple. */
public final class CursorPages {
    private CursorPages() {}
    public static CollectionResponse<Map<String,Object>> page(List<Map<String,Object>> values,int limit,String cursor,String sort,Set<String> allowed) {
        require(limit>=1 && limit<=100,400,"INVALID_LIMIT","limit must be between 1 and 100.");
        boolean descending=sort.startsWith("-"); String field=descending?sort.substring(1):sort;
        require(allowed.contains(field),400,"INVALID_SORT","Unsupported sort field.");
        Comparator<String> strings=Comparator.nullsFirst(Comparator.naturalOrder());
        Comparator<Map<String,Object>> order=(left,right)->compare(value(left,field),value(right,field),field);
        if(descending) order=order.reversed();
        order=order.thenComparing(m->value(m,"id"),strings);
        var sorted=values.stream().sorted(order).toList();
        if(cursor!=null) {
            require(cursor.length()<=4096,400,"INVALID_CURSOR","Cursor is too long.");
            String[] parts;
            try { parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\u0000",-1); }
            catch(IllegalArgumentException e) { throw new ApiException(400,"INVALID_CURSOR","Malformed cursor."); }
            require(parts.length==3 && parts[0].equals(sort),400,"INVALID_CURSOR","Cursor does not match the requested ordering.");
            var marker=new HashMap<String,Object>(); marker.put(field,parts[1].isEmpty()?null:parts[1]); marker.put("id",parts[2]);
            final var comparison=order; sorted=sorted.stream().filter(m->comparison.compare(m,marker)>0).toList();
        }
        boolean more=sorted.size()>limit; var items=sorted.stream().limit(limit).toList(); String next=null;
        if(more) { var last=items.getLast(); String value=value(last,field); next=Base64.getUrlEncoder().withoutPadding().encodeToString((sort+"\u0000"+(value==null?"":value)+"\u0000"+value(last,"id")).getBytes(StandardCharsets.UTF_8)); }
        return new CollectionResponse<>(items,new Page(limit,next,more));
    }
    public static CollectionResponse<Map<String,Object>> boundPage(List<Map<String,Object>> values,int limit,String cursor,String sort,Set<String> fields,String binding) {
        return wrap(page(values,limit,unwrap(cursor,binding),sort,fields),binding);
    }
    public static String unwrap(String cursor,String binding) {
        if(cursor==null)return null;
        require(cursor.length()<=8192,400,"INVALID_CURSOR","Cursor is too long.");
        try {
            var tuple=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split(":",2);
            require(tuple.length==2&&tuple[0].equals(digest(binding)),400,"INVALID_CURSOR","Cursor belongs to another query.");return tuple[1];
        }catch(IllegalArgumentException e){throw new ApiException(400,"INVALID_CURSOR","Malformed cursor.");}
    }
    public static CollectionResponse<Map<String,Object>> wrap(CollectionResponse<Map<String,Object>> page,String binding) {
        String next=page.page().nextCursor();
        if(next!=null)next=Base64.getUrlEncoder().withoutPadding().encodeToString((digest(binding)+":"+next).getBytes(StandardCharsets.UTF_8));
        return new CollectionResponse<>(page.items(),new Page(page.page().limit(),next,page.page().hasMore()));
    }
    public static CollectionResponse<Map<String,Object>> databasePage(List<Map<String,Object>> rows,int limit,String sort) {
        boolean more=rows.size()>limit;var items=rows.stream().limit(limit).toList();String cursor=null;
        if(more){var row=items.getLast();var field=sort.startsWith("-")?sort.substring(1):sort;cursor=Base64.getUrlEncoder().withoutPadding().encodeToString((sort+"\u0000"+Objects.toString(row.get(field),"")+"\u0000"+row.get("id")).getBytes(StandardCharsets.UTF_8));}
        return new CollectionResponse<>(items,new Page(limit,cursor,more));
    }
    private static String digest(String value) {
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static int compare(String left,String right,String field) {
        if(left==null || right==null) return Comparator.nullsFirst(Comparator.<String>naturalOrder()).compare(left,right);
        if(field.endsWith("At")) return java.time.Instant.parse(left).compareTo(java.time.Instant.parse(right));
        return left.compareTo(right);
    }
    private static String value(Map<String,Object> map,String field) { var v=map.get(field); return v==null?null:v.toString(); }
}
