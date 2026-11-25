package com.example.documentservice.infrastructure;

import io.minio.*;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.example.documentservice.api.ApiException.*;

@Component
public class ObjectStorage {
    private final MinioClient client,signer;
    private final String bucket;
    public ObjectStorage(@Value("${document.storage.endpoint}") String endpoint,@Value("${document.storage.public-endpoint}") String publicEndpoint,
        @Value("${document.storage.access-key}") String access,@Value("${document.storage.secret-key}") String secret,
        @Value("${document.storage.bucket}") String bucket,@Value("${document.storage.region}") String region) {
        this.bucket=bucket;
        // Placeholder credentials permit application startup; all I/O fails closed until configured.
        client=MinioClient.builder().endpoint(endpoint).credentials(access.isBlank()?"unconfigured":access,secret.isBlank()?"unconfigured":secret).region(region).build();
        client.setTimeout(TimeUnit.SECONDS.toMillis(3),TimeUnit.SECONDS.toMillis(30),TimeUnit.SECONDS.toMillis(30));
        signer=MinioClient.builder().endpoint(publicEndpoint).credentials(access.isBlank()?"unconfigured":access,secret.isBlank()?"unconfigured":secret).region(region).build();
    }
    public String uploadUrl(String key){return signed(key,Method.PUT,900,Map.of());}
    public String downloadUrl(String key,String name){
        String disposition="attachment; filename*=UTF-8''"+java.net.URLEncoder.encode(name,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");
        return signed(key,Method.GET,60,Map.of("response-content-disposition",disposition,"response-cache-control","private, no-store"));
    }
    private String signed(String key,Method method,int seconds,Map<String,String> query){
        try{return signer.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().bucket(bucket).object(key).method(method).expiry(seconds).extraQueryParams(query).build());}
        catch(Exception e){throw unavailable();}
    }
    public byte[] read(String key,long maximum) {
        require(maximum>=0&&maximum<Integer.MAX_VALUE,400,"INVALID_SIZE","Invalid object limit.");
        try(var stream=client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            byte[] bytes=stream.readNBytes((int)maximum+1);
            require(bytes.length<=maximum,413,"OBJECT_TOO_LARGE","The object exceeds its declared or permitted size.");return bytes;
        }catch(com.example.documentservice.api.ApiException e){throw e;}catch(Exception e){throw unavailable();}
    }
    public void write(String key,byte[] bytes,String contentType) {
        try {client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType(contentType).stream(new ByteArrayInputStream(bytes),bytes.length,-1).build());}
        catch(Exception e){throw unavailable();}
    }
}
