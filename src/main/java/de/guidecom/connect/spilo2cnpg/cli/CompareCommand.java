package de.guidecom.connect.spilo2cnpg.cli;

import de.guidecom.connect.spilo2cnpg.support.ManifestComparator;
import de.guidecom.connect.spilo2cnpg.support.ManifestComparator.ComparisonResult;
import de.guidecom.connect.spilo2cnpg.support.ManifestComparator.Difference;
import de.guidecom.connect.spilo2cnpg.support.ManifestComparator.Presence;
import de.guidecom.connect.spilo2cnpg.support.ManifestComparator.ResourceDiff;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * CLI command: {@code spilo2cnpg compare <left> <right>}
 *
 * <p>Structurally compares two (multi-document) Kubernetes manifest files resource by resource,
 * ignoring key ordering, comments and cosmetic scalar quoting. Useful for verifying that a
 * generated CNPG manifest set matches a reference (e.g. the deployed pilot).
 *
 * <p>Exit code: {@code 0} if the two sides are structurally identical, {@code 1} otherwise.
 */
@Command(
    name = "compare",
    mixinStandardHelpOptions = true,
    description = "Structurally compare two manifest sets (e.g. generated vs deployed reference)"
)
public class CompareCommand implements Callable<Integer> {

  @Parameters(index = "0", description = "Left manifest file (e.g. generated)")
  private File left;
  @Parameters(index = "1", description = "Right manifest file (e.g. deployed reference)")
  private File right;
  @Option(names = {"--left-label"}, description = "Label for the left side (default: ${DEFAULT-VALUE})")
  private String leftLabel = "left";
  @Option(names = {"--right-label"}, description = "Label for the right side (default: ${DEFAULT-VALUE})")
  private String rightLabel = "right";
  @Option(names = {"--show-identical"}, description = "Also list resources that are identical")
  private boolean showIdentical;

  private final ManifestComparator comparator = new ManifestComparator();

  @Override
  public Integer call() {
    List<Map<String, Object>> leftDocs;
    List<Map<String, Object>> rightDocs;
    try {
      leftDocs = comparator.parseFile(left);
    } catch (IOException e) {
      System.err.println("ERROR: could not read '" + left + "': " + e.getMessage());
      return 2;
    }
    try {
      rightDocs = comparator.parseFile(right);
    } catch (IOException e) {
      System.err.println("ERROR: could not read '" + right + "': " + e.getMessage());
      return 2;
    }

    ComparisonResult result = comparator.compare(leftDocs, rightDocs);
    printReport(result);
    return result.identical() ? 0 : 1;
  }

  private void printReport(ComparisonResult result) {
    String line = "=".repeat(72);
    System.out.println(line);
    System.out.printf("  Manifest comparison: %s (left) vs %s (right)%n", leftLabel, rightLabel);
    System.out.println(line);

    int identical = 0;
    int differing = 0;
    int onlyLeft = 0;
    int onlyRight = 0;

    for (ResourceDiff rd : result.resources()) {
      if (rd.presence() == Presence.ONLY_LEFT) {
        onlyLeft++;
        System.out.printf("  [ONLY %s] %s%n", leftLabel, rd.key());
      } else if (rd.presence() == Presence.ONLY_RIGHT) {
        onlyRight++;
        System.out.printf("  [ONLY %s] %s%n", rightLabel, rd.key());
      } else if (rd.differences().isEmpty()) {
        identical++;
        if (showIdentical) {
          System.out.printf("  [IDENTICAL] %s%n", rd.key());
        }
      } else {
        differing++;
        System.out.printf("  [DIFF %d] %s%n", rd.differences().size(), rd.key());
        for (Difference d : rd.differences()) {
          switch (d.type()) {
            case CHANGED -> System.out.printf("        ~ %s: %s=%s | %s=%s%n",
                d.path(), leftLabel, d.left(), rightLabel, d.right());
            case ONLY_LEFT -> System.out.printf("        + only in %s %s = %s%n",
                leftLabel, d.path(), d.left());
            case ONLY_RIGHT -> System.out.printf("        - only in %s %s = %s%n",
                rightLabel, d.path(), d.right());
          }
        }
      }
    }

    System.out.println(line);
    System.out.printf("  Summary: %d resource(s) | %d identical | %d differing | %d only-%s | %d only-%s%n",
        result.resources().size(), identical, differing, onlyLeft, leftLabel, onlyRight, rightLabel);
    System.out.printf("  Result: %s%n", result.identical() ? "IDENTICAL" : "DIFFERENCES FOUND");
    System.out.println(line);
  }
}
