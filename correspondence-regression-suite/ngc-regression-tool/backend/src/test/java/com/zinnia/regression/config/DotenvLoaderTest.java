package com.zinnia.regression.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.ConnectionString;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotenvLoaderTest {

    static final String ATLAS_URI = "mongodb+srv://test-user:p%40ss%23word@cluster0.example.mongodb.net/"
            + "?retryWrites=true&w=majority&appName=correspondence-dev";

    @TempDir Path dir;

    @Test
    void readsAnAtlasSrvUriWithQueryOptionsAndEncodedPasswordIntact() throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, "# comment\n\nMONGODB_URI=" + ATLAS_URI + "\nMONGODB_DATABASE=nextgen_correspondence_dev\n");

        var props = DotenvLoader.load(env);

        assertThat(props.get("MONGODB_URI")).isEqualTo(ATLAS_URI);
        assertThat(props.get("MONGODB_DATABASE")).isEqualTo("nextgen_correspondence_dev");
    }

    @Test
    void theUriParsesAsAMongoConnectionStringWithoutADatabaseName() {
        ConnectionString cs = new ConnectionString(ATLAS_URI);

        assertThat(cs.isSrvProtocol()).isTrue();
        assertThat(cs.getUsername()).isEqualTo("test-user");
        assertThat(new String(cs.getPassword())).isEqualTo("p@ss#word");
        assertThat(cs.getDatabase()).as("no database in the URI, so MONGODB_DATABASE is required").isNull();
        assertThat(cs.getApplicationName()).isEqualTo("correspondence-dev");
        assertThat(cs.getWriteConcern().asDocument().getString("w").getValue()).isEqualTo("majority");
    }

    @Test
    void handlesQuotesBlankValuesAndUnsetKeys() throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, "A=\"quoted value\"\nB=\nKAFKA_BOOTSTRAP_SERVER=localhost:9092\n#KAFKA_KEY=ignored\nnot a pair\n  C = spaced  \n");

        var props = DotenvLoader.load(env);

        assertThat(props).containsEntry("A", "quoted value").containsEntry("B", "")
                .containsEntry("KAFKA_BOOTSTRAP_SERVER", "localhost:9092").containsEntry("C", "spaced")
                .doesNotContainKeys("KAFKA_KEY", "not a pair");
    }

    @Test
    void aMissingFileIsFine() {
        assertThat(DotenvLoader.load(dir.resolve("nope"))).isEmpty();
    }
}
