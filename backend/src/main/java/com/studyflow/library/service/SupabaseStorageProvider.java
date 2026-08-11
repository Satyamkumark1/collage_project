package com.studyflow.library.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Supabase Storage's REST API (plain HTTP, service_role key) — same "RestClient over a new SDK"
 * posture as {@link com.studyflow.identity.service.RedisLoginLockStore}'s Upstash integration:
 * one bucket, three verbs, doesn't justify the supabase-java SDK. Persistent alternative to
 * {@link LocalDiskStorageProvider} for free hosts with ephemeral disk. Cloudinary stays
 * permanently out per the existing decision — see docs/DECISIONS.md.
 */
@Component
@ConditionalOnProperty(name = "studyflow.storage.provider", havingValue = "supabase")
public class SupabaseStorageProvider implements StorageProvider {

    private final RestClient restClient;
    private final String bucket;

    public SupabaseStorageProvider(@Value("${studyflow.storage.supabase.url}") String url,
            @Value("${studyflow.storage.supabase.service-key}") String serviceKey,
            @Value("${studyflow.storage.supabase.bucket}") String bucket) {
        this.bucket = bucket;
        this.restClient = RestClient.builder().baseUrl(url + "/storage/v1/object")
                .defaultHeader("Authorization", "Bearer " + serviceKey).build();
    }

    @Override
    public String store(byte[] content, String key) {
        restClient.post().uri("/{bucket}/{key}", bucket, key).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("x-upsert", "true").body(content).retrieve().toBodilessEntity();
        return key;
    }

    @Override
    public InputStream retrieve(String storageKey) {
        byte[] body = restClient.get().uri("/{bucket}/{key}", bucket, storageKey).retrieve().body(byte[].class);
        return new ByteArrayInputStream(body);
    }

    @Override
    public void delete(String storageKey) {
        restClient.delete().uri("/{bucket}/{key}", bucket, storageKey).retrieve().toBodilessEntity();
    }

    @Override
    public String providerName() {
        return "SUPABASE_STORAGE";
    }
}
