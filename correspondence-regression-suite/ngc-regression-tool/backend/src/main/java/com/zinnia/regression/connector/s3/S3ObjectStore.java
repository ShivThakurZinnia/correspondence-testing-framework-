package com.zinnia.regression.connector.s3;

import com.zinnia.regression.connector.Connector;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/** Reads objects with the AWS default credential chain (profile, SSO or environment). Never writes. */
@Component
@ConditionalOnExpression("!'${ngc.s3.bucket:}'.isEmpty()")
public class S3ObjectStore implements ObjectStore, Connector {

    private final S3Client s3;
    private final String bucket;

    public S3ObjectStore(@Value("${ngc.s3.bucket}") String bucket, @Value("${ngc.s3.region:us-east-1}") String region) {
        this.bucket = bucket;
        this.s3 = S3Client.builder().region(Region.of(region)).build();
    }

    @Override
    public String defaultBucket() {
        return bucket;
    }

    @Override
    public Optional<byte[]> readBytes(String bucket, String key) {
        try {
            return Optional.of(s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cannot read s3://" + bucket + "/" + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String id() {
        return "s3";
    }

    @Override
    public Status check() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return new Status(State.OK, "Bucket " + bucket + " is readable");
        } catch (RuntimeException e) {
            return new Status(State.FAILED, "Cannot reach bucket " + bucket + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    @jakarta.annotation.PreDestroy
    void close() {
        s3.close();
    }
}
