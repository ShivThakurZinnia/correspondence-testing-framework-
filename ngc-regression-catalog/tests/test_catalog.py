import copy
import importlib.util
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("validate_catalog", ROOT / "tools/validate_catalog.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CatalogTests(unittest.TestCase):
    def setUp(self):
        self.catalog = MODULE.Catalog(ROOT)
        self.scenario = copy.deepcopy(self.catalog.groups["scenarios"]["policy-page-tango-pdf"])

    def test_all_sample_suites_compile(self):
        report = self.catalog.compile_all()
        self.assertEqual(3, len(report["suites"]))
        self.assertEqual("AUTHORING_ONLY", report["validation"])
        self.assertIn("services/tango.json", report["contentHashes"])

    def test_ngc_only_does_not_activate_tango(self):
        plan = self.catalog.compile_suite("ngc-output")["plans"][0]
        self.assertEqual(["ngc"], plan["activeServices"])
        self.assertEqual(["letterPrepared"], plan["executionOrder"])

    def test_execution_order_is_inferred_from_bindings(self):
        self.scenario["checks"].reverse()
        plan = self.catalog.compile_scenario(self.scenario)
        self.assertEqual(["letterPrepared", "cdsPublished", "pdfGenerated"], plan["executionOrder"])

    def test_pdf_cannot_run_against_simulator(self):
        environment = self.catalog.groups["environments"]["controlled-example"]
        with self.assertRaisesRegex(MODULE.CatalogError, "Unsupported SIMULATED"):
            self.catalog.compile_scenario(self.scenario, environment)

    def test_real_requirement_cannot_use_simulated_lifecycle(self):
        scenario = copy.deepcopy(self.catalog.groups["scenarios"]["policy-page-simulated-lifecycle"])
        scenario["checks"][-1]["evidencePolicy"] = "REAL_REQUIRED"
        with self.assertRaisesRegex(MODULE.CatalogError, "Real evidence required"):
            self.catalog.compile_scenario(scenario, self.catalog.groups["environments"]["controlled-example"])

    def test_unknown_version_rejected(self):
        self.scenario["checks"][-1]["capability"]["version"] = "2.0.0"
        with self.assertRaisesRegex(MODULE.CatalogError, "Unknown capability/version"):
            self.catalog.compile_scenario(self.scenario)

    def test_unknown_expectation_field_rejected(self):
        self.scenario["checks"][-1]["expect"]["minPagez"] = 1
        with self.assertRaisesRegex(MODULE.CatalogError, "Additional properties"):
            self.catalog.compile_scenario(self.scenario)

    def test_false_readability_expectation_rejected(self):
        self.scenario["checks"][-1]["expect"]["readable"] = False
        with self.assertRaises(MODULE.CatalogError):
            self.catalog.compile_scenario(self.scenario)

    def test_no_expected_pdf_content_rejected(self):
        self.scenario["checks"][-1]["expect"]["textContains"] = []
        with self.assertRaises(MODULE.CatalogError):
            self.catalog.compile_scenario(self.scenario)

    def test_missing_prerequisite_rejected(self):
        for binding in self.scenario["checks"][-1]["bindings"]:
            binding["source"] = {"kind": "run", "pointer": "/eventId"}
        with self.assertRaisesRegex(MODULE.CatalogError, "Missing prerequisite"):
            self.catalog.compile_scenario(self.scenario)

    def test_unknown_evidence_property_rejected(self):
        self.scenario["checks"][-1]["bindings"][-1]["source"]["pointer"] = "/doesNotExist"
        with self.assertRaisesRegex(MODULE.CatalogError, "Undeclared evidence property"):
            self.catalog.compile_scenario(self.scenario)

    def test_incompatible_binding_type_rejected(self):
        self.scenario["checks"][-1]["bindings"][-1]["source"]["pointer"] = "/partition"
        with self.assertRaisesRegex(MODULE.CatalogError, "Binding type mismatch"):
            self.catalog.compile_scenario(self.scenario)

    def test_cyclic_bindings_rejected(self):
        self.scenario["checks"][0]["bindings"][0]["source"] = {
            "kind": "evidence", "checkId": "pdfGenerated", "pointer": "/jobId"}
        with self.assertRaisesRegex(MODULE.CatalogError, "dependency cycle"):
            self.catalog.compile_scenario(self.scenario)

    def test_duplicate_binding_rejected(self):
        self.scenario["checks"][0]["bindings"].append(self.scenario["checks"][0]["bindings"][0])
        with self.assertRaisesRegex(MODULE.CatalogError, "Duplicate input/binding"):
            self.catalog.compile_scenario(self.scenario)

    def test_missing_connector_rejected(self):
        environment = copy.deepcopy(self.catalog.groups["environments"]["uat-example"])
        del environment["connectors"]["tango"]
        with self.assertRaisesRegex(MODULE.CatalogError, "Missing environment connector"):
            self.catalog.compile_scenario(self.scenario, environment)

    def test_suite_cannot_drop_required_pdf_coverage(self):
        self.catalog.groups["suites"]["uat-pdf"]["scenarioRefs"] = ["policy-page-ngc-output"]
        with self.assertRaisesRegex(MODULE.CatalogError, "Missing required capability coverage"):
            self.catalog.compile_suite("uat-pdf")

    def test_suite_cannot_drop_required_client(self):
        self.catalog.groups["suites"]["uat-pdf"]["requiredClients"].append("another-client")
        with self.assertRaisesRegex(MODULE.CatalogError, "Missing required client coverage"):
            self.catalog.compile_suite("uat-pdf")

    def test_catalog_paths_cannot_escape_root(self):
        with self.assertRaisesRegex(MODULE.CatalogError, "escapes root"):
            self.catalog.load("../outside.json")

    def test_duplicate_json_keys_rejected(self):
        with self.assertRaisesRegex(MODULE.CatalogError, "Duplicate JSON key"):
            json.loads('{"id": "one", "id": "two"}', object_pairs_hook=MODULE.unique_pairs)

    def test_schema_version_and_unknown_fields_rejected(self):
        schema = self.catalog.load("schemas/scenario-v1.schema.json")
        self.scenario["schemaVersion"] = "2.0"
        self.scenario["runTangoAnyway"] = True
        with self.assertRaises(MODULE.CatalogError):
            MODULE.validate(schema, self.scenario, "test")

    def test_new_service_uses_registry_without_runner_branch(self):
        service = {"id": "archive", "connectorId": "archiveConnector", "contractVersion": "1.0.0"}
        capability = copy.deepcopy(self.catalog.capabilities[("ngc.letter.prepared", "1.0.0")][1])
        capability["id"] = "archive.xml.checked"
        self.catalog.capabilities[(capability["id"], "1.0.0")] = (service, capability)
        self.catalog.groups["clients"]["sbl-example"]["allowedServices"].append("archive")
        scenario = copy.deepcopy(self.catalog.groups["scenarios"]["policy-page-ngc-output"])
        scenario["checks"][0]["capability"]["id"] = capability["id"]
        del scenario["checks"][0]["expectationRef"]
        scenario["checks"][0]["expect"] = {"schemaValid": True, "policyNumber": "TEST-POL-1001", "targetRole": "OWNER"}
        self.assertEqual(["archive"], self.catalog.compile_scenario(scenario)["activeServices"])

    def test_shared_expectation_is_resolved(self):
        plan = self.catalog.compile_scenario(self.scenario)
        self.assertEqual("TEST-POL-1001", plan["checks"][0]["expect"]["policyNumber"])

    def test_shared_expectation_cannot_be_silently_overridden(self):
        self.scenario["checks"][0]["expect"] = {"schemaValid": True}
        with self.assertRaises(MODULE.CatalogError):
            self.catalog.compile_scenario(self.scenario)


if __name__ == "__main__":
    unittest.main()
