package com.zinnia.ngc.regression.spi;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Proposed Java SPI; deployed connector implementations are provided by the NGC team. */
public interface Connector {
    String id();
    String implementationVersion();
    String serviceContractVersion();
    Collection<CapabilityHandler<?, ?, ?>> handlers();

    // Open and arm shared transport observers before the runner publishes any stimulus.
    Session open(Binding binding, RunScope scope) throws ConnectorException;

    interface Session extends AutoCloseable {
        void awaitReady(Instant deadline) throws ConnectorException, InterruptedException;
        @Override
        void close() throws ConnectorException;
    }

    interface CapabilityHandler<I, E, X> {
        CapabilityRef capability();
        Class<I> inputType();
        Class<E> evidenceType();
        Class<X> expectationType();

        // Inputs are resolved and schema-validated before this call; use already-armed buffers.
        Evidence<E> observe(Session session, I inputs, Instant deadline)
                throws ConnectorException, InterruptedException;

        // Pure, repeatable evaluation. Correlation checks compare inputs with observed evidence.
        Evaluation evaluate(I inputs, E observed, X expected);
    }

    record CapabilityRef(String id, String version) {}
    record Binding(String serviceId, String connectorId, String implementationVersion,
                   String serviceContractVersion, EvidenceMode evidenceMode, URI configRef) {}
    record RunScope(UUID runId, UUID eventId, UUID correlationId, Instant startedAt,
                    Instant overallDeadline) {}
    record Evidence<E>(E data, EvidenceMode mode, String origin, Instant observedAt,
                       URI rawEvidenceRef) {}
    record Evaluation(Verdict verdict, List<Mismatch> mismatches) {}
    record Mismatch(String path, String expected, String actual, String reason) {}

    enum EvidenceMode { REAL, SIMULATED }
    enum Verdict { PASSED, FAILED }
    enum FailureKind { TIMEOUT, UNAVAILABLE, UNSUPPORTED, INVALID_EVIDENCE, INTERNAL }

    final class ConnectorException extends Exception {
        private final FailureKind kind;

        public ConnectorException(FailureKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public FailureKind kind() {
            return kind;
        }
    }
}
