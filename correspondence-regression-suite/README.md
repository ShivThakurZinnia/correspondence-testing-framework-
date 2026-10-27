# NGC regression service catalog

This is the versioned contract for a Java regression framework to be implemented by the NGC team. Scenarios select capabilities and expectations. Service manifests describe those capabilities. Environment profiles bind them to connector implementations. The runner discovers work from the catalog; it does not contain a switch statement for Tango, CDS, or each client.

The package includes JSON Schema definitions, three example scenarios, reusable expectations, an offline authoring validator, negative tests, and a compilable Java connector SPI. It does **not** include deployed connectors, a Kafka injector, a regression execution engine, or verified UAT data. All environment names, connector implementation versions, configuration references, and source event details in these examples are placeholders.

## Catalog composition

| File group | Purpose | Changed when |
| --- | --- | --- |
| `catalog.json` | Release version and explicit inventory of every resource | Releasing a catalog revision |
| `services/` | Capability IDs, exact versions, prerequisites and input/evidence/expectation schemas | Adding or evolving an integration contract |
| `clients/` | Client identity and allowed downstream services | Adding a client |
| `expectations/` | Reviewed, reusable business expectations bound to one capability version | Adding business coverage |
| `scenarios/` | Event fixture, selected checks, evidence bindings, expectations and deadlines | Adding a business scenario |
| `environments/` | Connector implementation/version, physical channel mapping and configuration reference | Changing a deployment binding |
| `suites/` | Selected scenarios, mandatory client coverage and real/simulated capability requirements | Changing a release gate |
| `schemas/` | Strict machine-readable authoring contracts | Evolving the catalog format |

JSON is the canonical v1 serialization. The Java implementation can use Jackson. YAML authoring can be added through a parser that preserves exactly the same data model and rejects duplicate keys; it must not introduce separate merge or inheritance semantics.

## Request a Tango PDF check

The complete example is `scenarios/policy-page-tango-pdf.json`. Its final check selects a capability and declares the expected result:

```json
{
  "id": "pdfGenerated",
  "capability": {"id": "tango.pdf.generated", "version": "1.0.0"},
  "inputs": {},
  "bindings": [
    {"target": "/correlationId", "source": {"kind": "run", "pointer": "/correlationId"}},
    {"target": "/requestId", "source": {"kind": "evidence", "checkId": "cdsPublished", "pointer": "/requestId"}},
    {"target": "/targetEventUniqueId", "source": {"kind": "evidence", "checkId": "cdsPublished", "pointer": "/targetEventUniqueId"}}
  ],
  "expect": {
    "generated": true,
    "readable": true,
    "minPages": 1,
    "textContains": ["TEST-POL-1001"]
  },
  "evidencePolicy": "REAL_REQUIRED",
  "timeoutSeconds": 600
}
```

The `tango` manifest maps that capability to `tangoConnector`. The environment binds `tangoConnector` to a pinned implementation version and a deployment configuration reference. The framework invokes the corresponding Java handler after resolving its inputs. The handler retrieves correlated evidence, evaluates the declared expectations, and returns structured mismatches.

The capability's expectation schema enforces meaningful PDF verification: readable bytes, at least one page, and nonempty expected text. A `pdf.generation.completed` callback alone cannot pass this check. The connector must resolve an authoritative current-job artifact and validate its bytes. Final PDF location and the real CDS/Tango lookup contract remain implementation prerequisites.

## Decide how far a scenario runs

| Example scenario | Selected capabilities | Meaning |
| --- | --- | --- |
| `policy-page-ngc-output` | `ngc.letter.prepared` | Observe and validate NGC XML |
| `policy-page-tango-pdf` | NGC preparation, CDS publication, Tango PDF | Verify actual PDF generation by real Tango |
| `policy-page-simulated-lifecycle` | NGC preparation, CDS publication, Tango lifecycle | Verify NGC handling of a simulated generation callback |

There is no extra `runTango: true` flag to contradict the selected checks. Removing Tango checks removes Tango observation from the resolved plan. Suite coverage requirements still reject accidentally removing mandatory PDF coverage.

Observation selection does not stop business execution. An NGC-only test still requires isolated downstream routing or a test-delivery fence. Declaring `deliveryFence` in a file is not proof of isolation: runtime preflight must verify the actual deployment.

The controlled lifecycle suite uses real NGC and real Kafka publication to an isolated downstream boundary. Only Tango lifecycle behavior is simulated. Broker evidence therefore remains `REAL` for CDS publication, while Tango lifecycle evidence is `SIMULATED`.

## Reuse expectations

A check declares exactly one of `expect` or `expectationRef`. All three examples reuse the `policy-page-owner` expectation bundle for NGC XML validation. The bundle declares its capability version, so it cannot be accidentally applied to an unrelated handler.

No implicit defaults or deep merges alter business assertions. To change a shared expectation for one scenario, create another reviewed bundle or explicitly use an inline expectation. Updating a released bundle requires a new catalog release. Each target is expressed as an explicit check chain in v1; additional recipient chains get distinct check IDs and correlated bindings.

Dynamic bindings replace top-level input properties using typed JSON values. Sources are limited to run values, scenario parameters, and declared evidence properties. No template scripts, expressions, Java class names, or arbitrary SQL/Mongo queries execute from scenario files. Dynamic expectations are deliberately excluded from v1: expected values remain reviewed data. A future typed parameter-binding extension must not derive expected business results from actual output.

## Validate and inspect a plan

Authoring tooling uses Python only to verify these language-neutral contracts; the regression application and connectors remain Java. No NGC systems are contacted.

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.txt
.venv/bin/python tools/validate_catalog.py
.venv/bin/python tools/validate_catalog.py --suite uat-pdf
.venv/bin/python -m unittest discover -s tests -v
javac -d build/contracts contracts/java/com/zinnia/ngc/regression/spi/Connector.java
```

Validation checks schemas, references, exact versions, expectation shapes, declared binding properties/types, dependency cycles, prerequisites, environment compatibility and required client/capability coverage. The output contains inferred check order, active services, evidence modes, resolved expectations and SHA-256 hashes for catalog resources.

The printed plan is an **authoring preview**, not an executable runtime snapshot. Production `PlanCompiler` must retain all input bindings, deadlines, fixture content, connector configuration identities and run-specific resolved values in its immutable execution plan. Runtime preflight additionally checks installed handlers, endpoint access, deployment fences, secret resolution and artifact lookup support. Example placeholders must block actual execution.

## Extend it

**New scenario:** add a fixture or reuse one, declare checks using existing capabilities, reuse or add expectations, and add the scenario path and suite selection. No runner or connector change is needed.

**New client:** add a profile, reviewed client fixtures and expectations, and its scenarios. Add the client to required suite coverage. Client identity is independent of carrier, product and plan mappings inside the real event fixture.

**New capability:** add an exact-version contract to a service manifest, implement a Java handler, register it in that connector, and supply contract tests. The schema and handler jointly define behavior. A JSON Schema can validate the shape of `minPages`; Java implements the page-count assertion.

**New system:** add a service manifest and Java connector, bind its exact versions in the environment, and select its capabilities from scenarios. Capability IDs are namespaced by service. The registry and planner do not require source edits for each new system.

## Versioning

| Version | Meaning |
| --- | --- |
| `schemaVersion: 1.0` | Catalog file shape; unknown fields/versions fail validation |
| `catalogVersion: 0.1.0` | Immutable release of the complete resource inventory |
| `service.contractVersion: 1.0.0` | Service contract snapshot required by its environment binding |
| `capability.version: 1.0.0` | Exact input, evidence, expectation and behavioral contract |
| `implementationVersion: 0.1.0` | Pinned connector binary supplied by the implementation team |
| Scenario/expectation `revision` | Review history within the catalog release |

Use exact pins, never `latest`, wildcards or automatic version negotiation. Breaking schema, meaning, provenance or assertion changes require a new capability major version. Keep old capability versions registered while scenarios still reference them. Additive service capabilities require a new compatible service contract/catalog release. Every scenario or fixture change requires a new catalog release; retain Git commit and resource hashes with each run.

The authoring validator checks versions and reports hashes. Immutable release enforcement and binary checksum verification belong in CI and deployment. Never replace a previously released artifact under the same version. One catalog snapshot binds one service contract version per service; use another catalog release to exercise another contract snapshot.

## Developer handover

See `docs/runtime-contract.md` for Java execution responsibilities, assertion semantics and readiness rules. `contracts/java/` supplies the proposed compilable SPI. Real connector implementations, current-job lookup, NGC-approved fixtures, client mappings and deployment configuration must be implemented and reviewed before UAT execution.
