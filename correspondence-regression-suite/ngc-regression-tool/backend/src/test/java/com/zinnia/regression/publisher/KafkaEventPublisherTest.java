package com.zinnia.regression.publisher;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class KafkaEventPublisherTest {

    @Test
    void localKafkaNeedsNoCredentialsAndStaysPlaintext() {
        for (String blank : new String[]{"", "  ", null}) {
            Properties p = KafkaEventPublisher.producerProperties("localhost:9092", blank, blank);

            assertThat(p.get("bootstrap.servers")).isEqualTo("localhost:9092");
            assertThat(p).doesNotContainKeys("security.protocol", "sasl.mechanism", "sasl.jaas.config");
            assertThat(p.get("acks")).isEqualTo("all");
            assertThat(p.get("enable.idempotence")).isEqualTo("true");
        }
    }

    @Test
    void aKeySwitchesOnSaslSslForConfluentCloud() {
        Properties p = KafkaEventPublisher.producerProperties("pkc-xyz.confluent.cloud:9092", "KEY1", "SECRET1");

        assertThat(p.get("security.protocol")).isEqualTo("SASL_SSL");
        assertThat(p.get("sasl.mechanism")).isEqualTo("PLAIN");
        assertThat(p.get("sasl.jaas.config").toString()).contains("username=\"KEY1\"", "password=\"SECRET1\"");
    }
}
