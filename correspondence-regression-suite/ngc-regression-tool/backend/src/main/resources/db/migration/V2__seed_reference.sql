-- Initial reference data. Names are a starting list to confirm; CRUD UI comes later.
INSERT INTO tag (category, code, label) VALUES
  ('CLIENT',   'GILI',          'GILI'),
  ('EVENT',    'policy-page',   'Policy Page'),
  ('EVENT',    'new-business',  'New Business'),
  ('EVENT',    'anniversary',   'Anniversary'),
  ('MAPPER',   'policy',        'Policy mapper'),
  ('MAPPER',   'anniversary',   'Anniversary mapper'),
  ('MAPPER',   'new-business',  'New Business mapper'),
  ('PRIORITY', 'MVP',           'MVP'),
  ('PRIORITY', 'release-gate',  'Release gate'),
  ('PRIORITY', 'extended',      'Extended'),
  ('PATH',     'print',         'Print'),
  ('PATH',     'email',         'Email');

INSERT INTO environment (code, name) VALUES ('qa', 'QA'), ('uat', 'UAT');

INSERT INTO kafka_topic (environment_id, kind, topic_name)
SELECT id, 'SOR', 'zinnia.correspondence.sor' FROM environment WHERE code = 'qa';
INSERT INTO kafka_topic (environment_id, kind, topic_name)
SELECT id, 'BPM', 'zinnia.correspondence.qa' FROM environment WHERE code = 'qa';
INSERT INTO kafka_topic (environment_id, kind, topic_name)
SELECT id, 'SOR', 'zinnia.correspondence.sor' FROM environment WHERE code = 'uat';
INSERT INTO kafka_topic (environment_id, kind, topic_name)
SELECT id, 'BPM', 'zinnia.correspondence.uat' FROM environment WHERE code = 'uat';

INSERT INTO test_module (code, name, description)
VALUES ('policy-page', 'Policy Page', 'GILI policy page correspondence (policy.activation.completed)');
