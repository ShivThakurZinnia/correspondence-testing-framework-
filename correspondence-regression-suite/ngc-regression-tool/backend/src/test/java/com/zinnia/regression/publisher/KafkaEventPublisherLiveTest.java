package com.zinnia.regression.publisher;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Publishes to a throwaway topic (never an NGC topic) on whatever KAFKA_BOOTSTRAP_SERVER points at and reads it back.
 * Runs only when that variable is set; intended for a local broker.
 */
@EnabledIfEnvironmentVariable(named = "KAFKA_BOOTSTRAP_SERVER", matches = ".+")
class KafkaEventPublisherLiveTest {

    @Test
    void publishedMessageIsAckedAndReadableWithKeyAndValueIntact() throws Exception {
        String bootstrap = System.getenv("KAFKA_BOOTSTRAP_SERVER");
        String topic = "regression-suite-selftest-" + UUID.randomUUID();
        Properties admin = new Properties();
        admin.put("bootstrap.servers", bootstrap);
        try (AdminClient client = AdminClient.create(admin)) {
            client.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
            try {
                var publisher = new KafkaEventPublisher(bootstrap, System.getenv().getOrDefault("KAFKA_KEY", ""),
                        System.getenv().getOrDefault("KAFKA_SECRET", ""));
                String key = UUID.randomUUID().toString();
                String value = "{\"correlationid\":\"" + key + "\",\"type\":\"selftest\"}";

                EventPublisher.PublishResult result = publisher.publish(topic, key, value);

                assertThat(result.offset()).isZero();
                Properties c = new Properties();
                c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
                c.put(ConsumerConfig.GROUP_ID_CONFIG, "regression-selftest-" + key);
                c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
                c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(c)) {
                    consumer.subscribe(List.of(topic));
                    var records = consumer.poll(Duration.ofSeconds(15));
                    assertThat(records.count()).isEqualTo(1);
                    var r = records.iterator().next();
                    assertThat(r.key()).isEqualTo(key);
                    assertThat(r.value()).isEqualTo(value);
                    System.out.println("LIVE kafka publish ok: topic=" + topic + " partition=" + r.partition() + " offset=" + r.offset());
                }
                publisher.close();
            } finally {
                client.deleteTopics(List.of(topic)).all().get();
            }
        }
    }
}
