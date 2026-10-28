package com.zinnia.regression.xml;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class S3LocationTest {

    @Test
    void parsesBucketAndKey() {
        assertThat(S3Location.parse("s3://my-bucket/a/b/c.xml")).contains(new S3Location("my-bucket", "a/b/c.xml"));
    }

    @Test
    void rejectsAnythingElse() {
        assertThat(S3Location.parse(null)).isEmpty();
        assertThat(S3Location.parse("")).isEmpty();
        assertThat(S3Location.parse("https://x/y")).isEmpty();
        assertThat(S3Location.parse("s3://bucket-only")).isEmpty();
        assertThat(S3Location.parse("s3://bucket/")).isEmpty();
        assertThat(S3Location.parse("s3:///key")).isEmpty();
    }
}
