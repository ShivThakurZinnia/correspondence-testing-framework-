"""Offline authoring checks. Production execution and connector loading belong in Java."""

import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import sys

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError, ValidationError


class CatalogError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise CatalogError(message)


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def validate(schema, value, where):
    try:
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema).validate(value)
    except (ValidationError, SchemaError) as error:
        raise CatalogError(f"{where}: {error.message}") from error


def pointer_parts(pointer):
    require(pointer.startswith("/"), f"Invalid JSON Pointer: {pointer}")
    require(not re.search(r"~(?![01])", pointer), f"Invalid JSON Pointer escape: {pointer}")
    return [part.replace("~1", "/").replace("~0", "~") for part in pointer[1:].split("/")]


def at_pointer(value, pointer):
    for part in pointer_parts(pointer):
        if isinstance(value, dict):
            require(part in value, f"Missing binding source: {pointer}")
            value = value[part]
        elif isinstance(value, list):
            require(bool(re.fullmatch(r"0|[1-9][0-9]*", part)), f"Invalid array index: {pointer}")
            require(int(part) < len(value), f"Missing array element: {pointer}")
            value = value[int(part)]
        else:
            raise CatalogError(f"Cannot traverse binding source: {pointer}")
    return value


def schema_at_pointer(schema, pointer):
    for part in pointer_parts(pointer):
        require(part in schema.get("properties", {}), f"Undeclared evidence property: {pointer}")
        schema = schema["properties"][part]
    require("type" in schema, f"Binding source needs an explicit type: {pointer}")
    return schema


def no_external_schema_refs(value):
    if isinstance(value, dict):
        require("$ref" not in value and "$dynamicRef" not in value,
                "Capability schemas must be self-contained and inline in catalog v1")
        for child in value.values():
            no_external_schema_refs(child)
    elif isinstance(value, list):
        for child in value:
            no_external_schema_refs(child)


def ordered_checks(graph):
    ordered, visiting, visited = [], set(), set()

    def visit(node):
        require(node not in visiting, f"Check dependency cycle at {node}")
        if node in visited:
            return
        visiting.add(node)
        for dependency in sorted(graph[node]):
            visit(dependency)
        visiting.remove(node)
        visited.add(node)
        ordered.append(node)

    for node in graph:
        visit(node)
    return ordered


class Catalog:
    RUN_SCHEMA = {
        "type": "object",
        "properties": {key: {"type": "string"} for key in
                       ("runId", "eventId", "correlationId", "startedAt")},
    }

    def __init__(self, root):
        self.root = Path(root).resolve()
        self.hashes = {}
        self.manifest = self.load("catalog.json", "catalog")
        self.groups = {}
        self.capabilities = {}
        for group in ("services", "clients", "expectations", "scenarios", "environments", "suites"):
            documents = {}
            for path in self.manifest[group]:
                document = self.load(path, group[:-1] if group != "services" else "service")
                require(document["id"] not in documents, f"Duplicate {group} ID: {document['id']}")
                documents[document["id"]] = document
            self.groups[group] = documents
        for service in self.groups["services"].values():
            for capability in service["capabilities"]:
                require(capability["id"].startswith(service["id"] + "."),
                        f"Capability namespace does not match service: {capability['id']}")
                key = (capability["id"], capability["version"])
                require(key not in self.capabilities, f"Duplicate capability: {key}")
                for schema_name in ("inputSchema", "evidenceSchema", "expectationSchema"):
                    schema = capability[schema_name]
                    no_external_schema_refs(schema)
                    Draft202012Validator.check_schema(schema)
                    require(set(schema["required"]) <= set(schema["properties"]),
                            f"Required property missing from {key} {schema_name}")
                self.capabilities[key] = (service, capability)
        for service, capability in self.capabilities.values():
            for prerequisite in capability["prerequisites"]:
                self.capability(prerequisite)
        ordered_checks({key: {(ref["id"], ref["version"]) for ref in capability["prerequisites"]}
                        for key, (_, capability) in self.capabilities.items()})
        for expectation in self.groups["expectations"].values():
            _, capability = self.capability(expectation["capability"])
            validate(capability["expectationSchema"], expectation["expect"], expectation["id"])
        for client in self.groups["clients"].values():
            require(set(client["allowedServices"]) <= self.groups["services"].keys(),
                    f"Unknown allowed service in client {client['id']}")
        for environment in self.groups["environments"].values():
            for service_id, binding in environment["connectors"].items():
                service = self.lookup("services", service_id)
                require(binding["connectorId"] == service["connectorId"], f"Wrong connector for {service_id}")
                require(binding["serviceContractVersion"] == service["contractVersion"],
                        f"Service contract version mismatch for {service_id}")
        for scenario in self.groups["scenarios"].values():
            self.compile_scenario(scenario)

    def load(self, relative, schema_kind=None):
        require(not Path(relative).is_absolute(), f"Absolute catalog path forbidden: {relative}")
        path = (self.root / relative).resolve()
        require(self.root in path.parents, f"Catalog path escapes root: {relative}")
        try:
            raw = path.read_bytes()
            value = json.loads(raw, object_pairs_hook=unique_pairs,
                               parse_constant=lambda text: (_ for _ in ()).throw(CatalogError(f"Non-finite JSON number: {text}")))
        except (OSError, json.JSONDecodeError) as error:
            raise CatalogError(f"{relative}: {error}") from error
        self.hashes[relative] = hashlib.sha256(raw).hexdigest()
        if schema_kind:
            schema = self.load(f"schemas/{schema_kind}-v1.schema.json")
            validate(schema, value, relative)
        return value

    def lookup(self, group, identifier):
        require(identifier in self.groups[group], f"Unknown {group} reference: {identifier}")
        return self.groups[group][identifier]

    def capability(self, reference):
        key = (reference["id"], reference["version"])
        require(key in self.capabilities, f"Unknown capability/version: {key}")
        return self.capabilities[key]

    def compile_scenario(self, scenario, environment=None):
        validate(self.load("schemas/scenario-v1.schema.json"), scenario, scenario.get("id", "scenario"))
        client = self.lookup("clients", scenario["clientRef"])
        fixture = self.load(scenario["stimulus"]["fixtureRef"])
        require(isinstance(fixture, dict), "Stimulus fixture must be a JSON object")
        stimulus_targets = set()
        for binding in scenario["stimulus"]["bindings"]:
            target = binding["target"][1:]
            require(target not in stimulus_targets, f"Duplicate stimulus binding: {target}")
            stimulus_targets.add(target)
            require(target in fixture, f"Stimulus binding must replace an existing field: {target}")
            source = binding["source"]
            require(source["kind"] != "evidence", "Stimulus cannot depend on post-injection evidence")
            at_pointer(scenario["parameters"] if source["kind"] == "parameters" else self.RUN_SCHEMA["properties"], source["pointer"])
        checks = {}
        for check in scenario["checks"]:
            require(check["id"] not in checks, f"Duplicate check ID: {check['id']}")
            checks[check["id"]] = check
        graph = {identifier: set() for identifier in checks}
        expectations = {}
        for check in checks.values():
            service, capability = self.capability(check["capability"])
            require(service["id"] in client["allowedServices"], f"Client cannot use service {service['id']}")
            require(check["timeoutSeconds"] <= scenario["overallTimeoutSeconds"], "Check timeout exceeds scenario deadline")
            if "expectationRef" in check:
                bundle = self.lookup("expectations", check["expectationRef"])
                require(bundle["capability"] == check["capability"], f"Expectation capability mismatch for {check['id']}")
                expectations[check["id"]] = bundle["expect"]
            else:
                expectations[check["id"]] = check["expect"]
            validate(capability["expectationSchema"], expectations[check["id"]], f"{check['id']} expectation")
            schema = copy.deepcopy(capability["inputSchema"])
            bound = set()
            for binding in check["bindings"]:
                target = binding["target"][1:]
                require(target not in bound and target not in check["inputs"], f"Duplicate input/binding: {target}")
                require(target in schema["properties"], f"Unknown input binding: {target}")
                bound.add(target)
                source = binding["source"]
                if source["kind"] == "parameters":
                    validate(schema["properties"][target], at_pointer(scenario["parameters"], source["pointer"]), target)
                    continue
                if source["kind"] == "run":
                    source_schema = schema_at_pointer(self.RUN_SCHEMA, source["pointer"])
                else:
                    upstream = source["checkId"]
                    require(upstream in checks, f"Unknown source check: {upstream}")
                    graph[check["id"]].add(upstream)
                    _, upstream_capability = self.capability(checks[upstream]["capability"])
                    source_schema = schema_at_pointer(upstream_capability["evidenceSchema"], source["pointer"])
                target_type = schema["properties"][target].get("type")
                require(source_schema["type"] == target_type, f"Binding type mismatch for {check['id']}/{target}")
            schema["required"] = [name for name in schema["required"] if name not in bound]
            validate(schema, check["inputs"], f"{check['id']} inputs")
            if environment:
                binding = environment["connectors"].get(service["id"])
                require(binding is not None, f"Missing environment connector: {service['id']}")
                mode = binding["evidenceMode"]
                require(mode in capability["supportedEvidence"], f"Unsupported {mode} evidence for {capability['id']}")
                require(check["evidencePolicy"] != "REAL_REQUIRED" or mode == "REAL",
                        f"Real evidence required by {check['id']}")
        order = ordered_checks(graph)
        ancestors = {}
        for identifier in order:
            ancestors[identifier] = set(graph[identifier])
            for dependency in graph[identifier]:
                ancestors[identifier].update(ancestors[dependency])
            _, capability = self.capability(checks[identifier]["capability"])
            for prerequisite in capability["prerequisites"]:
                require(any(checks[ancestor]["capability"] == prerequisite for ancestor in ancestors[identifier]),
                        f"Missing prerequisite for {identifier}: {prerequisite['id']}")
        if environment:
            require(scenario["stimulus"]["channel"] in environment["channels"], "Unbound stimulus channel")
        used_services = sorted({self.capability(check["capability"])[0]["id"] for check in checks.values()})
        return {"scenarioId": scenario["id"], "clientId": client["id"], "revision": scenario["revision"],
                "executionOrder": order, "activeServices": used_services,
                "checks": [{"id": identifier, "capability": checks[identifier]["capability"],
                            "dependsOn": sorted(graph[identifier]), "expect": expectations[identifier],
                            "evidenceMode": environment["connectors"][self.capability(checks[identifier]["capability"])[0]["id"]]["evidenceMode"] if environment else None}
                           for identifier in order]}

    def compile_suite(self, identifier):
        suite = self.lookup("suites", identifier)
        environment = self.lookup("environments", suite["environmentRef"])
        scenarios = [self.lookup("scenarios", ref) for ref in suite["scenarioRefs"]]
        plans = [self.compile_scenario(scenario, environment) for scenario in scenarios]
        require(set(suite["requiredClients"]) <= {plan["clientId"] for plan in plans}, "Missing required client coverage")
        for client in suite["requiredClients"]:
            for requirement in suite["requiredCapabilities"]:
                self.capability(requirement)
                require(any(check["capability"]["id"] == requirement["id"] and
                            check["capability"]["version"] == requirement["version"] and
                            check["evidenceMode"] == requirement["evidenceMode"]
                            for plan in plans if plan["clientId"] == client for check in plan["checks"]),
                        f"Missing required capability coverage for {client}: {requirement['id']}")
        return {"suiteId": identifier, "environmentId": environment["id"], "plans": plans}

    def compile_all(self):
        suites = [self.compile_suite(identifier) for identifier in self.groups["suites"]]
        return {"schemaVersion": self.manifest["schemaVersion"], "catalogVersion": self.manifest["catalogVersion"],
                "validation": "AUTHORING_ONLY", "contentHashes": dict(sorted(self.hashes.items())), "suites": suites}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--suite", help="Print a resolved authoring plan for one suite")
    args = parser.parse_args()
    try:
        catalog = Catalog(args.root)
        result = catalog.compile_all()
        if args.suite:
            result["suites"] = [catalog.compile_suite(args.suite)]
        print(json.dumps(result, indent=2))
    except (CatalogError, SchemaError) as error:
        print(f"Catalog invalid: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
