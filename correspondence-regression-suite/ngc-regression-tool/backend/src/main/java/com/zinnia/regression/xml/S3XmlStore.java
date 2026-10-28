package com.zinnia.regression.xml;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/** Reads objects with the AWS default credential chain (profile, SSO or environment). Never writes. */
@Component
@ConditionalOnExpression("!'${ngc.s3.bucket:}'.isEmpty()")
public class S3XmlStore implements XmlStore {

    private final S3Client s3;
    private final String bucket;

    public S3XmlStore(@Value("${ngc.s3.bucket}") String bucket, @Value("${ngc.s3.region:us-east-1}") String region) {
        this.bucket = bucket;
        this.s3 = S3Client.builder().region(Region.of(region)).build();
    }

    @Override
    public String defaultBucket() {
        return bucket;
    }

    @Override
    public Optional<String> read(String bucket, String key) {
        try {
            return Optional.of(s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .asString(StandardCharsets.UTF_8));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cannot read s3://" + bucket + "/" + key + ": " + e.getMessage(), e);
        }
    }

    @jakarta.annotation.PreDestroy
    void close() {
        s3.close();
    }
}
