-- Dev is the first environment wired to a real NGC. Topic names match NGC's dev configuration.
INSERT INTO environment (code, name) VALUES ('dev', 'Dev');
INSERT INTO kafka_topic (environment_id, kind, topic_name)
SELECT id, 'SOR', 'zinnia.correspondence.sor' FROM environment WHERE code = 'dev';
INSERT INTO kafka_topic (environment_id, kind, topic_name)
SELECT id, 'BPM', 'zinnia.correspondence.dev' FROM environment WHERE code = 'dev';
