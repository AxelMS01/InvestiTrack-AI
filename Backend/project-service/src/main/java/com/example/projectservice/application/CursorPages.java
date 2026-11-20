package com.example.projectservice.application;

import com.example.projectservice.api.ApiModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.example.projectservice.api.ApiException.require;

/** Query-bound UUID keyset cursors. Authorization is evaluated again on every page. */
public final class CursorPages {
    private CursorPages() { }
    public static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public static UUID after(String cursor,String binding,int limit) {
        require(limit>=1&&limit<=100,400,"INVALID_LIMIT","Limit must be between 1 and 100.");
        if(cursor==null)return new UUID(0,0);
        try {
            require(cursor.length()<=300,400,"INVALID_CURSOR","The cursor is invalid.");
            var parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split(":",2);
            require(parts.length==2&&parts[0].equals(hash(binding)),400,"INVALID_CURSOR","The cursor belongs to another query.");
            return UUID.fromString(parts[1]);
        }catch(RuntimeException e){throw new com.example.projectservice.api.ApiException(400,"INVALID_CURSOR","The cursor is invalid or belongs to another query.");}
    }
    public static Page<Map<String,Object>> page(List<Map<String,Object>> rows,String binding,int limit) {
        boolean more=rows.size()>limit;var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        String cursor=more?Base64.getUrlEncoder().withoutPadding().encodeToString((hash(binding)+":"+items.getLast().get("id")).getBytes(StandardCharsets.UTF_8)):null;
        return new Page<>(items,new PageInfo(limit,cursor,more));
    }
}
