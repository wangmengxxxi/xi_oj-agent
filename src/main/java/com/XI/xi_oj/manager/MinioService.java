package com.XI.xi_oj.manager;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class MinioService {

    private final MinioClient minioClient;

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.bucket}")
    private String bucket;

    @PostConstruct
    public void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[MinIO] bucket '{}' created", bucket);
            } else {
                log.info("[MinIO] bucket '{}' already exists", bucket);
            }
            setBucketPublicRead();
        } catch (Exception e) {
            log.error("[MinIO] failed to ensure bucket '{}', image upload will fail", bucket, e);
        }
    }

    private void setBucketPublicRead() {
        String policy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": {"AWS": ["*"]},
                      "Action": ["s3:GetObject"],
                      "Resource": ["arn:aws:s3:::%s/*"]
                    }
                  ]
                }
                """.formatted(bucket);
        try {
            minioClient.setBucketPolicy(
                    SetBucketPolicyArgs.builder()
                            .bucket(bucket)
                            .config(policy)
                            .build());
            log.info("[MinIO] bucket '{}' set to public-read", bucket);
        } catch (Exception e) {
            log.warn("[MinIO] failed to set public-read policy on '{}': {}", bucket, e.getMessage());
        }
    }

    /**
     * 上传图片到 MinIO，返回访问 URL
     */
    public String uploadImage(byte[] data, String objectName, String contentType) {
        try (ByteArrayInputStream stream = new ByteArrayInputStream(data)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(stream, data.length, -1)
                    .contentType(contentType)
                    .build());
            String url = endpoint + "/" + bucket + "/" + objectName;
            log.debug("[MinIO] uploaded: {}", url);
            return url;
        } catch (Exception e) {
            log.error("[MinIO] upload failed: {}", objectName, e);
            throw new RuntimeException("MinIO upload failed", e);
        }
    }

    /**
     * 生成唯一的对象名称
     */
    public static String generateObjectName(String prefix, String extension) {
        return prefix + "/" + UUID.randomUUID() + extension;
    }
}
