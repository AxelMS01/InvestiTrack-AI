package com.example.documentservice.domain;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Pure validation of the bytes that are committed, never of a mutable upload key. */
public final class FileRules {
    private FileRules() { }
    public static final Map<String,String> TYPES=Map.of(
        "pdf","application/pdf","docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","pptx","application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "png","image/png","jpg","image/jpeg","jpeg","image/jpeg","gif","image/gif");
    public static String declared(String name,String type,long size,long maximum,String hash) {
        if(name==null||name.isBlank()||name.length()>500||name.contains("/")||name.contains("\\")||name.chars().anyMatch(c->c<32||c==127))throw new IllegalArgumentException("Invalid filename");
        String extension=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        if(!Objects.equals(TYPES.get(extension),type))throw new IllegalArgumentException("Unsupported filename or content type");
        if(size<=0||size>maximum)throw new IllegalArgumentException("Invalid file size");
        if(hash==null||!hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid SHA-256");
        return extension;
    }
    public static String sha256(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static boolean matches(byte[] bytes,String type) {
        if(type.equals("application/pdf"))return starts(bytes,"%PDF-".getBytes(StandardCharsets.US_ASCII));
        if(type.equals("image/png"))return starts(bytes,new byte[]{(byte)137,80,78,71,13,10,26,10});
        if(type.equals("image/jpeg"))return starts(bytes,new byte[]{(byte)255,(byte)216,(byte)255});
        if(type.equals("image/gif"))return starts(bytes,"GIF87a".getBytes(StandardCharsets.US_ASCII))||starts(bytes,"GIF89a".getBytes(StandardCharsets.US_ASCII));
        String prefix=type.contains("wordprocessingml")?"word/":type.contains("spreadsheetml")?"xl/":type.contains("presentationml")?"ppt/":null;
        if(prefix==null||!starts(bytes,new byte[]{80,75,3,4}))return false;
        boolean content=false,main=false;long expanded=0;int entries=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;byte[] buffer=new byte[8192];
            while((entry=zip.getNextEntry())!=null) {
                if(++entries>2000||entry.getName().contains("..")||entry.getName().startsWith("/")||entry.getName().toLowerCase(Locale.ROOT).endsWith("vbaproject.bin"))return false;
                content|=entry.getName().equals("[Content_Types].xml");
                main|=entry.getName().equals(prefix+(prefix.equals("word/")?"document.xml":prefix.equals("xl/")?"workbook.xml":"presentation.xml"));
                int count;while((count=zip.read(buffer))!=-1){expanded+=count;if(expanded>100*1024*1024)return false;}
            }
            return content&&main;
        }catch(IOException e){return false;}
    }
    private static boolean starts(byte[] bytes,byte[] prefix) {return bytes.length>=prefix.length&&Arrays.equals(bytes,0,prefix.length,prefix,0,prefix.length);}
}
