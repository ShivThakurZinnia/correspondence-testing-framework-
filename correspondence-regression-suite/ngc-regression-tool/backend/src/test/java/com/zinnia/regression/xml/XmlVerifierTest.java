package com.zinnia.regression.xml;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zinnia.regression.assertion.AssertionOutcome;
import com.zinnia.regression.collector.NgcView;
import com.zinnia.regression.collector.NgcView.CdsPayload;
import com.zinnia.regression.collector.NgcView.IncomingEventView;
import com.zinnia.regression.collector.NgcView.TargetView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class XmlVerifierTest {

    static final String XML = "<L><POLICIES><POLICY><POL_NUM>A1</POL_NUM><CREATED>2026-01-01</CREATED></POLICY></POLICIES></L>";
    static final String XSD = """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:include schemaLocation="types.xsd"/>
              <xs:element name="L"><xs:complexType><xs:sequence><xs:element name="POLICIES"><xs:complexType><xs:sequence>
                <xs:element name="POLICY"><xs:complexType><xs:sequence>
                  <xs:element name="POL_NUM" type="xs:string"/><xs:element name="CREATED" type="DateText"/>
                </xs:sequence></xs:complexType></xs:element></xs:sequence></xs:complexType></xs:element></xs:sequence></xs:complexType></xs:element>
            </xs:schema>""";
    static final String TYPES = """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:simpleType name="DateText"><xs:restriction base="xs:date"/></xs:simpleType></xs:schema>""";

    final ObjectMapper mapper = new ObjectMapper();
    final XmlVerifier verifier = new XmlVerifier(new XmlComparator());

    static class FakeStore implements XmlStore {
        final Map<String, String> objects = new HashMap<>();
        final List<String> reads = new ArrayList<>();
        boolean broken;

        @Override
        public String defaultBucket() {
            return "b";
        }

        @Override
        public Optional<String> read(String bucket, String key) {
            reads.add(bucket + "/" + key);
            if (broken) {
                throw new IllegalStateException("Cannot read: SSO session expired");
            }
            return Optional.ofNullable(objects.get(bucket + "/" + key));
        }
    }

    FakeStore store() {
        FakeStore s = new FakeStore();
        s.objects.put("b/base/f.xml", XML);
        s.objects.put("b/schema/v1/Correspondence.xsd", XSD);
        s.objects.put("b/schema/v1/types.xsd", TYPES);
        return s;
    }

    NgcView view(String outputPath, TargetView... targets) {
        var incoming = new IncomingEventView("u", "SENT_TO_CDS", null, "base", List.of(targets));
        var fields = new HashMap<String, String>();
        fields.put("XSDFilePath", "s3://b/schema/v1/Correspondence.xsd");
        if (outputPath != null) {
            fields.put("OutputFilePath", outputPath);
        }
        return new NgcView("c", List.of(incoming), 1, 0, 0, List.of(), List.of(new CdsPayload("OWNER", fields)));
    }

    TargetView owner(String xmlFileName) {
        return new TargetView("OWNER", "SENT_TO_CDS", "PRINT", null, null, "POLPG", xmlFileName, null, null);
    }

    JsonNode expect(String json) throws Exception {
        return mapper.readTree(json);
    }

    boolean passed(XmlVerifier.Result r, String path) {
        return r.outcomes().stream().filter(o -> o.path().equals(path)).findFirst().map(AssertionOutcome::passed)
                .orElseThrow(() -> new AssertionError("no outcome for " + path + " in " + r.outcomes()));
    }

    @Test
    void existsWellFormedXsdAndGoldenAllPassUsingTheCdsOutputPath() throws Exception {
        var r = verifier.verify(expect("{\"exists\":true,\"xsdValid\":true,\"goldenCompare\":true}"),
                view("s3://b/base/f.xml", owner("f.xml")), XML, store());

        assertThat(r.outcomes()).allMatch(AssertionOutcome::passed).extracting(AssertionOutcome::path)
                .containsExactly("xml.exists", "xml.wellFormed", "xml.xsdValid", "xml.golden");
        assertThat(r.xml()).isEqualTo(XML);
        assertThat(r.role()).isEqualTo("OWNER");
    }

    @Test
    void fallsBackToBaseLocationPlusFileNameWhenCdsPathIsMissingOrWrong() throws Exception {
        FakeStore s = store();

        var r = verifier.verify(expect("{\"exists\":true}"), view("s3://b/wrong/f.xml", owner("f.xml")), null, s);

        assertThat(passed(r, "xml.exists")).isTrue();
        assertThat(s.reads).startsWith("b/wrong/f.xml", "b/base/f.xml");
    }

    @Test
    void missingXmlFailsExistsAndMarksDependentChecksNotEvaluated() throws Exception {
        var r = verifier.verify(expect("{\"exists\":true,\"xsdValid\":true,\"goldenCompare\":true}"),
                view("s3://b/nope.xml", owner("nope.xml")), XML, store());

        assertThat(r.outcomes()).extracting(AssertionOutcome::path, AssertionOutcome::passed).containsExactly(
                org.assertj.core.groups.Tuple.tuple("xml.exists", false),
                org.assertj.core.groups.Tuple.tuple("xml.xsdValid", false),
                org.assertj.core.groups.Tuple.tuple("xml.golden", false));
        assertThat(r.outcomes().get(0).message()).contains("s3://b/nope.xml", "s3://b/base/nope.xml");
        assertThat(r.xml()).isNull();
    }

    @Test
    void invalidAgainstXsdReportsTheValidationError() throws Exception {
        FakeStore s = store();
        s.objects.put("b/base/f.xml", XML.replace("2026-01-01", "not-a-date"));

        var r = verifier.verify(expect("{\"xsdValid\":true}"), view("s3://b/base/f.xml", owner("f.xml")), null, s);

        assertThat(passed(r, "xml.xsdValid")).isFalse();
        assertThat(r.outcomes().stream().filter(o -> o.path().equals("xml.xsdValid")).findFirst().get().message())
                .contains("not-a-date");
    }

    @Test
    void goldenDifferenceHonoursIgnoreList() throws Exception {
        FakeStore s = store();
        s.objects.put("b/base/f.xml", XML.replace("2026-01-01", "2030-02-02"));

        assertThat(passed(verifier.verify(expect("{\"goldenCompare\":true}"), view("s3://b/base/f.xml", owner("f.xml")), XML, s),
                "xml.golden")).isFalse();
        assertThat(passed(verifier.verify(expect("{\"goldenCompare\":true,\"ignore\":[\"CREATED\"]}"),
                view("s3://b/base/f.xml", owner("f.xml")), XML, s), "xml.golden")).isTrue();
    }

    @Test
    void goldenCompareWithoutAGoldenFileFailsInsteadOfPassing() throws Exception {
        var r = verifier.verify(expect("{\"goldenCompare\":true}"), view("s3://b/base/f.xml", owner("f.xml")), null, store());

        assertThat(passed(r, "xml.golden")).isFalse();
    }

    @Test
    void malformedXmlFailsWellFormedAndSkipsSchemaAndGoldenChecks() throws Exception {
        FakeStore s = store();
        s.objects.put("b/base/f.xml", "<L><unclosed>");

        var r = verifier.verify(expect("{\"xsdValid\":true,\"goldenCompare\":true}"),
                view("s3://b/base/f.xml", owner("f.xml")), XML, s);

        assertThat(r.outcomes()).extracting(AssertionOutcome::path).containsExactly("xml.exists", "xml.wellFormed");
        assertThat(passed(r, "xml.wellFormed")).isFalse();
    }

    @Test
    void severalTargetsRequireARoleAndTheRoleSelectsTheXml() throws Exception {
        var joint = new TargetView("JOINTOWNER", "SENT_TO_CDS", "PRINT", null, null, "POLPG", "j.xml", null, null);
        FakeStore s = store();
        s.objects.put("b/base/j.xml", XML);
        var two = new NgcView("c", List.of(new IncomingEventView("u", "SENT_TO_CDS", null, "base",
                List.of(owner("f.xml"), joint))), 2, 0, 0, List.of(), List.of());

        assertThat(passed(verifier.verify(expect("{\"exists\":true}"), two, null, s), "xml.role")).isFalse();
        var r = verifier.verify(expect("{\"exists\":true,\"role\":\"JOINTOWNER\"}"), two, null, s);
        assertThat(passed(r, "xml.exists")).isTrue();
        assertThat(r.role()).isEqualTo("JOINTOWNER");
    }

    @Test
    void storeFailureBecomesAFailedAssertionWithTheCause() throws Exception {
        FakeStore s = store();
        s.broken = true;

        var r = verifier.verify(expect("{\"exists\":true}"), view("s3://b/base/f.xml", owner("f.xml")), null, s);

        assertThat(r.outcomes()).singleElement().satisfies(o -> {
            assertThat(o.passed()).isFalse();
            assertThat(o.message()).contains("SSO session expired");
        });
    }

    @Test
    void noIncomingEventFailsExplicitly() throws Exception {
        var r = verifier.verify(expect("{\"exists\":true}"), new NgcView("c", List.of(), 0, 0, 0, List.of()), null, store());

        assertThat(passed(r, "xml.exists")).isFalse();
    }

    @Test
    void expectingNoXmlPassesWhenAbsent() throws Exception {
        var r = verifier.verify(expect("{\"exists\":false}"), view("s3://b/nope.xml", owner("nope.xml")), null, store());

        assertThat(passed(r, "xml.exists")).isTrue();
    }
}
