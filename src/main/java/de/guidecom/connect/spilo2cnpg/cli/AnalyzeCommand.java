package de.guidecom.connect.spilo2cnpg.cli;

import de.guidecom.connect.spilo2cnpg.ConversionService;
import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult;
import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult.AnalysisFinding;
import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult.MigrationReadiness;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI command: {@code spilo2cnpg analyze --file <yaml>}
 *
 * <p>Analyzes a Spilo {@code postgresql} CR for CNPG migration compatibility
 * and prints a structured report.
 */
@Command(
    name = "analyze",
    mixinStandardHelpOptions = true,
    description = "Analyze a Spilo postgresql CR for CNPG migration compatibility"
)
public class AnalyzeCommand implements Callable<Integer> {

  private final ConversionService conversionService;
  @Option(names = {"--file", "-f"}, required = true, description = "Path to the Spilo YAML file")
  private File file;

  public AnalyzeCommand(ConversionService conversionService) {
    this.conversionService = conversionService;
  }

  @Override
  public Integer call() {
    SpiloPostgresql spilo;
    try {
      spilo = conversionService.read(file);
    } catch (IOException e) {
      System.err.println("ERROR: could not read Spilo YAML '" + file + "': " + e.getMessage());
      return 1;
    }

    AnalysisResult result = conversionService.analyze(spilo);
    printReport(result);
    return conversionService.isBlocked(result) ? 1 : 0;
  }

  private void printReport(AnalysisResult result) {
    String line = "=".repeat(72);
    System.out.println(line);
    System.out.printf("  Spilo → CNPG Migration Analysis: %s/%s%n",
        result.getNamespace(), result.getClusterName());
    System.out.println(line);

    System.out.printf("  Readiness: %s%n", formatReadiness(result.getReadiness()));

    long blockers = countBySeverity(result.getFindings(), AnalysisResult.Severity.BLOCKER);
    long warnings = countBySeverity(result.getFindings(), AnalysisResult.Severity.WARNING);
    long infos = countBySeverity(result.getFindings(), AnalysisResult.Severity.INFO);
    System.out.printf("  Findings:  %d blocker(s)  %d warning(s)  %d info(s)%n",
        blockers, warnings, infos);
    System.out.println();

    List<AnalysisFinding> sorted = result.getFindings().stream()
        .sorted(Comparator.comparing(f -> f.getSeverity().ordinal()))
        .toList();

    AnalysisResult.AnalysisFinding.Category currentCategory = null;
    for (AnalysisFinding f : sorted) {
      if (f.getCategory() != currentCategory) {
        currentCategory = f.getCategory();
        System.out.printf("  [%s]%n", currentCategory);
      }
      System.out.printf("    %s  %s%n", severityBadge(f.getSeverity()), f.getTitle());
      if (f.getDetail() != null && !f.getDetail().isBlank()) {
        System.out.printf("         Detail: %s%n", f.getDetail());
      }
      if (f.getRecommendation() != null && !f.getRecommendation().isBlank()) {
        System.out.printf("         Action: %s%n", f.getRecommendation());
      }
      System.out.println();
    }
    System.out.println(line);
  }

  private String formatReadiness(MigrationReadiness r) {
    return switch (r) {
      case READY -> "READY";
      case READY_WITH_WARNINGS -> "READY (with warnings)";
      case BLOCKED -> "BLOCKED";
    };
  }

  private long countBySeverity(List<AnalysisFinding> findings, AnalysisResult.Severity s) {
    return findings.stream().filter(f -> f.getSeverity() == s).count();
  }

  private String severityBadge(AnalysisResult.Severity s) {
    return switch (s) {
      case BLOCKER -> "[BLOCKER]";
      case WARNING -> "[WARNING]";
      case INFO -> "[ INFO  ]";
    };
  }

}
