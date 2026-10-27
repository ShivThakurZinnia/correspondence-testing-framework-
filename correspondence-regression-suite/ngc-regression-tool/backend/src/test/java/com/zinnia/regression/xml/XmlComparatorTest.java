package com.zinnia.regression.xml;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class XmlComparatorTest {

    final XmlComparator comparator = new XmlComparator();

    static final String GOLDEN = "<L><POLICIES><POLICY><POL_NUM>A1</POL_NUM><CREATED>2026-01-01</CREATED></POLICY></POLICIES></L>";

    @Test
    void identicalIgnoringWhitespaceAndComments() {
        String actual = "<L>\n <!-- c -->\n <POLICIES>\n  <POLICY>\n   <POL_NUM>A1</POL_NUM>\n   <CREATED>2026-01-01</CREATED>\n"
                + "  </POLICY>\n </POLICIES>\n</L>";

        assertThat(comparator.diff(GOLDEN, actual, Set.of())).isEmpty();
    }

    @Test
    void valueDifferenceIsReportedWithXPathButNotTheValues() {
        java.util.List<String> diffs = comparator.diff(GOLDEN, GOLDEN.replace("A1", "ZZ-SECRET"), Set.of());

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0)).contains("POL_NUM").doesNotContain("ZZ-SECRET").doesNotContain("A1");
    }

    @Test
    void ignoredElementNameOrPathIsSkippedButOthersStillCompared() {
        String actual = GOLDEN.replace("2026-01-01", "2030-05-05");

        assertThat(comparator.diff(GOLDEN, actual, Set.of("CREATED"))).isEmpty();
        assertThat(comparator.diff(GOLDEN, actual, Set.of("POLICIES/POLICY/CREATED"))).isEmpty();
        assertThat(comparator.diff(GOLDEN, actual, Set.of("OTHER/CREATED"))).as("path must match").isNotEmpty();
        assertThat(comparator.diff(GOLDEN, actual.replace("A1", "B2"), Set.of("CREATED"))).hasSize(1);
    }

    @Test
    void missingAndExtraElementsAreDifferences() {
        assertThat(comparator.diff(GOLDEN, "<L><POLICIES><POLICY><POL_NUM>A1</POL_NUM></POLICY></POLICIES></L>", Set.of()))
                .isNotEmpty();
        assertThat(comparator.diff(GOLDEN, GOLDEN.replace("</POLICY>", "<EXTRA/></POLICY>"), Set.of())).isNotEmpty();
    }

}
