package com.zinnia.regression.connector.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import com.zinnia.regression.connector.mongo.DatabaseReport.Access;
import com.zinnia.regression.connector.mongo.DatabaseReport.MissingIndex;
import com.zinnia.regression.connector.mongo.MongoDiagnostics.Want;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class MongoDiagnosticsTest {

    private static final List<Want> WANTED = List.of(new Want(List.of("correlationId"), "lookup"),
            new Want(List.of("createdAt"), "window"));

    @Test
    void anIndexCoversAWantedKeyOnlyWhenItStartsWithIt() {
        List<MissingIndex> missing = MongoDiagnostics.missing(List.of(List.of("_id"), List.of("correlationId", "eventType"),
                List.of("status", "createdAt")), WANTED);

        assertThat(missing).extracting(MissingIndex::keys).containsExactly(List.of("createdAt"));
        assertThat(missing.get(0).reason()).isEqualTo("window");
    }

    @Test
    void nothingIsMissingWhenAllWantedKeysAreIndexed() {
        assertThat(MongoDiagnostics.missing(List.of(List.of("createdAt", "carrier"), List.of("correlationId")), WANTED)).isEmpty();
    }

    @Test
    void everythingIsMissingFromACollectionWithOnlyTheIdIndex() {
        assertThat(MongoDiagnostics.missing(List.of(List.of("_id")), WANTED)).hasSize(2);
    }

    @Test
    void keysAreReadInIndexOrder() {
        Document index = new Document("name", "x").append("key", new Document("carrier", 1).append("eventType", 1).append("createdAt", -1));

        assertThat(MongoDiagnostics.keys(index)).containsExactly("carrier", "eventType", "createdAt");
        assertThat(MongoDiagnostics.keys(new Document("name", "broken"))).isEmpty();
    }

    private static Document status(List<Document> privileges, boolean authenticated) {
        Document auth = new Document("authenticatedUsers", authenticated ? List.of(new Document("user", "u").append("db", "admin")) : List.of())
                .append("authenticatedUserPrivileges", privileges);
        return new Document("authInfo", auth);
    }

    private static Document privilege(Document resource, String... actions) {
        return new Document("resource", resource).append("actions", List.of(actions));
    }

    @Test
    void aReadOnlyUserIsReportedReadOnly() {
        Access a = MongoDiagnostics.writeAccess(status(List.of(
                privilege(new Document("db", "ngc").append("collection", ""), "find", "listIndexes", "collStats")), true), "ngc");

        assertThat(a.checked()).isTrue();
        assertThat(a.readOnly()).isTrue();
        assertThat(a.writeActions()).isEmpty();
    }

    @Test
    void writePrivilegesOnTheNgcDatabaseOrOnAnyDatabaseAreReported() {
        Access onDb = MongoDiagnostics.writeAccess(status(List.of(
                privilege(new Document("db", "ngc").append("collection", ""), "find", "update", "insert")), true), "ngc");
        Access anyDb = MongoDiagnostics.writeAccess(status(List.of(
                privilege(new Document("db", "").append("collection", ""), "remove", "find")), true), "ngc");
        Access cluster = MongoDiagnostics.writeAccess(status(List.of(
                privilege(new Document("cluster", true), "dropDatabase")), true), "ngc");

        assertThat(onDb.readOnly()).isFalse();
        assertThat(onDb.writeActions()).containsExactly("insert", "update");
        assertThat(anyDb.writeActions()).containsExactly("remove");
        assertThat(cluster.writeActions()).containsExactly("dropDatabase");
    }

    @Test
    void writePrivilegesOnAnotherDatabaseDoNotCount() {
        Access a = MongoDiagnostics.writeAccess(status(List.of(
                privilege(new Document("db", "other").append("collection", ""), "insert", "update")), true), "ngc");

        assertThat(a.readOnly()).isTrue();
    }

    @Test
    void anUnauthenticatedConnectionIsNotClaimedToBeReadOnly() {
        Access a = MongoDiagnostics.writeAccess(status(List.of(), false), "ngc");

        assertThat(a.checked()).isFalse();
        assertThat(a.readOnly()).isFalse();
        assertThat(a.note()).contains("not authenticated");
        assertThat(MongoDiagnostics.writeAccess(new Document(), "ngc").checked()).isFalse();
    }
}
