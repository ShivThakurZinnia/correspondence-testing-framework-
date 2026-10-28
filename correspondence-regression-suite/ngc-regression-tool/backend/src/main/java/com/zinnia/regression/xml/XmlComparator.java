package com.zinnia.regression.xml;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.w3c.dom.Node;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Comparison;
import org.xmlunit.diff.Diff;

/** Compares generated XML with a golden file, ignoring whitespace, comments and any element the test names. */
@Component
public class XmlComparator {

    static final int MAX_REPORTED = 20;

    /**
     * @param ignore element names (e.g. CREATION_DATE) or path suffixes (e.g. POLICIES/POLICY/POL_ISSUE_DATE)
     * @return one line per difference with its XPath and kind; values are left out of the message
     */
    public List<String> diff(String golden, String actual, Set<String> ignore) {
        Diff diff = DiffBuilder.compare(Input.fromString(golden)).withTest(Input.fromString(actual))
                .ignoreWhitespace().ignoreComments().checkForSimilar()
                .withNodeFilter(node -> !ignored(node, ignore))
                .build();
        List<String> out = new ArrayList<>();
        diff.getDifferences().forEach(d -> {
            Comparison c = d.getComparison();
            String where = c.getControlDetails().getXPath() != null ? c.getControlDetails().getXPath()
                    : c.getTestDetails().getXPath();
            out.add(c.getType() + " at " + where);
        });
        return out;
    }

    private static boolean ignored(Node node, Set<String> ignore) {
        if (ignore.isEmpty() || node.getNodeType() != Node.ELEMENT_NODE) {
            return false;
        }
        String name = local(node);
        if (ignore.contains(name)) {
            return true;
        }
        StringBuilder path = new StringBuilder(name);
        for (Node p = node.getParentNode(); p != null && p.getNodeType() == Node.ELEMENT_NODE; p = p.getParentNode()) {
            path.insert(0, local(p) + "/");
        }
        String full = path.toString();
        return ignore.stream().anyMatch(i -> i.contains("/") && (full.equals(i) || full.endsWith("/" + i)));
    }

    private static String local(Node n) {
        return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
    }
}
