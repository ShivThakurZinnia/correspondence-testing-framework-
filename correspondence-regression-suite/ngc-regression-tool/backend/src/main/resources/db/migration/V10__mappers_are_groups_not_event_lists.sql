-- Testers decide which mapper group a test case belongs to; the database no longer carries NGC event names.
-- (V8/V9 stay in history because they may already be applied; this removes what they created.)
DROP TABLE mapper_event;

UPDATE tag SET description = 'NGC mapper: PolicyPageXmlMapper'              WHERE category = 'MAPPER' AND code = 'policy-page';
UPDATE tag SET description = 'NGC mapper: AnniversaryXmlMapper'             WHERE category = 'MAPPER' AND code = 'anniversary';
UPDATE tag SET description = 'NGC mapper: NewBusinessLettersXmlMapper'      WHERE category = 'MAPPER' AND code = 'new-business';
UPDATE tag SET description = 'NGC mapper: GenericLettersXmlMapper'          WHERE category = 'MAPPER' AND code = 'generic-letter';
UPDATE tag SET description = 'NGC mapper: AutomatedLettersXmlMapper'        WHERE category = 'MAPPER' AND code = 'automated-letter';
UPDATE tag SET description = 'NGC mapper: NigoLettersXmlMapper'             WHERE category = 'MAPPER' AND code = 'nigo-letter';
UPDATE tag SET description = 'NGC mapper: AgentLettersXmlMapper'            WHERE category = 'MAPPER' AND code = 'agent-letter';
UPDATE tag SET description = 'NGC mapper: SuitabilityDeclineLettersXmlMapper' WHERE category = 'MAPPER' AND code = 'suitability-letter';
UPDATE tag SET description = 'NGC mapper: ScheduledPagesXmlMapper'          WHERE category = 'MAPPER' AND code = 'scheduled-pages';
UPDATE tag SET description = 'NGC mapper: FinancialConfirmXmlMapper'        WHERE category = 'MAPPER' AND code = 'financial-confirm';
UPDATE tag SET description = 'NGC mapper: AdhocLettersXmlMapper'            WHERE category = 'MAPPER' AND code = 'adhoc-letter';

UPDATE test_module SET description = 'Policy page (POLPG) letters sent after a policy is activated or issued. NGC mapper: PolicyPageXmlMapper.'
  WHERE code = 'policy-page' AND description LIKE 'Policy page (POLPG) letters%';
UPDATE test_module SET description = 'Annual anniversary statements (ANNSTM). NGC mapper: AnniversaryXmlMapper.'
  WHERE code = 'anniversary' AND description LIKE 'Annual anniversary statements%';
UPDATE test_module SET description = 'New business letters from BPM events. NGC mapper: NewBusinessLettersXmlMapper.'
  WHERE code = 'new-business' AND description LIKE 'New business letters from BPM events%';
