package de.guidecom.connect.spilo2cnpg;

import de.guidecom.connect.spilo2cnpg.support.ManifestComparator;
import de.guidecom.connect.spilo2cnpg.support.ManifestComparator.ComparisonResult;
import de.guidecom.connect.spilo2cnpg.support.ManifestComparator.Difference;
import de.guidecom.connect.spilo2cnpg.support.ManifestComparator.Presence;
import de.guidecom.connect.spilo2cnpg.support.ManifestComparator.ResourceDiff;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestComparatorTest {

  private final ManifestComparator comparator = new ManifestComparator();

  private ComparisonResult compare(String left, String right) throws IOException {
    return comparator.compare(comparator.parse(left), comparator.parse(right));
  }

  @Test
  void identicalManifestsAreEqualIgnoringKeyOrder() throws IOException {
    String a = """
        kind: Cluster
        metadata:
          name: c1
          namespace: ns
        spec:
          instances: 1
          imageName: img
        """;
    String b = """
        metadata:
          namespace: ns
          name: c1
        spec:
          imageName: img
          instances: 1
        kind: Cluster
        """;
    ComparisonResult result = compare(a, b);
    assertTrue(result.identical());
    assertEquals(0, result.totalDifferences());
  }

  @Test
  void scalarQuotingDifferenceIsIgnored() throws IOException {
    String a = "kind: Pod\nmetadata:\n  name: p\nspec:\n  port: \"5432\"\n";
    String b = "kind: Pod\nmetadata:\n  name: p\nspec:\n  port: 5432\n";
    assertTrue(compare(a, b).identical());
  }

  @Test
  void changedScalarIsReported() throws IOException {
    String a = "kind: C\nmetadata:\n  name: x\nspec:\n  instances: 1\n";
    String b = "kind: C\nmetadata:\n  name: x\nspec:\n  instances: 2\n";
    ComparisonResult result = compare(a, b);
    assertFalse(result.identical());
    ResourceDiff rd = result.resources().get(0);
    assertEquals(Presence.BOTH, rd.presence());
    assertEquals(1, rd.differences().size());
    Difference d = rd.differences().get(0);
    assertEquals(Difference.Type.CHANGED, d.type());
    assertEquals("/spec/instances", d.path());
    assertEquals("1", d.left());
    assertEquals("2", d.right());
  }

  @Test
  void keyOnlyOnOneSideIsReported() throws IOException {
    String a = "kind: C\nmetadata:\n  name: x\nspec:\n  a: 1\n  b: 2\n";
    String b = "kind: C\nmetadata:\n  name: x\nspec:\n  a: 1\n";
    ComparisonResult result = compare(a, b);
    ResourceDiff rd = result.resources().get(0);
    assertEquals(1, rd.differences().size());
    assertEquals(Difference.Type.ONLY_LEFT, rd.differences().get(0).type());
    assertEquals("/spec/b", rd.differences().get(0).path());
  }

  @Test
  void resourceOnlyInOneSet() throws IOException {
    String a = "kind: C\nmetadata:\n  name: x\n---\nkind: C\nmetadata:\n  name: y\n";
    String b = "kind: C\nmetadata:\n  name: x\n";
    ComparisonResult result = compare(a, b);
    assertFalse(result.identical());
    assertEquals(2, result.resources().size());
    ResourceDiff yDiff = result.resources().stream()
        .filter(r -> "y".equals(r.key().name())).findFirst().orElseThrow();
    assertEquals(Presence.ONLY_LEFT, yDiff.presence());
  }

  @Test
  void parseSkipsEmptyDocuments() throws IOException {
    List<Map<String, Object>> docs = comparator.parse("---\nkind: A\nmetadata:\n  name: a\n---\n");
    assertEquals(1, docs.size());
  }

  @Test
  void listLengthDifferenceIsReported() throws IOException {
    String a = "kind: C\nmetadata:\n  name: x\nspec:\n  items:\n    - one\n    - two\n";
    String b = "kind: C\nmetadata:\n  name: x\nspec:\n  items:\n    - one\n";
    ComparisonResult result = compare(a, b);
    ResourceDiff rd = result.resources().get(0);
    assertEquals(1, rd.differences().size());
    assertEquals(Difference.Type.ONLY_LEFT, rd.differences().get(0).type());
    assertEquals("/spec/items[1]", rd.differences().get(0).path());
  }
}
