package de.guidecom.connect.spilo2cnpg;

import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult;
import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult.AnalysisFinding;
import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult.AnalysisFinding.Category;
import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult.MigrationReadiness;
import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult.Severity;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql.SpiloEnvVar;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql.SpiloSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpiloAnalyzerTest {

  private final SpiloAnalyzer analyzer = new SpiloAnalyzer();

  private boolean hasFinding(AnalysisResult r, Category category, Severity severity) {
    return r.getFindings().stream()
        .anyMatch(f -> f.getCategory() == category && f.getSeverity() == severity);
  }

  @Test
  void readyClusterHasNoBlockersOrWarnings() {
    AnalysisResult result = analyzer.analyze(SpiloTestFixtures.readyCr());

    assertEquals(MigrationReadiness.READY, result.getReadiness());
    assertTrue(result.getFindings().stream().noneMatch(f -> f.getSeverity() == Severity.BLOCKER));
    assertTrue(result.getFindings().stream().noneMatch(f -> f.getSeverity() == Severity.WARNING));
  }

  @Test
  void unsupportedPostgresVersionIsBlocker() {
    SpiloSpec spec = SpiloTestFixtures.readySpec();
    spec.setPostgresqlConfig(SpiloPostgresql.SpiloPostgresqlConfig.builder().version("13").build());

    AnalysisResult result = analyzer.analyze(SpiloTestFixtures.crWith(spec));

    assertEquals(MigrationReadiness.BLOCKED, result.getReadiness());
    assertTrue(hasFinding(result, Category.POSTGRES_VERSION, Severity.BLOCKER));
  }

  @Test
  void missingPostgresVersionIsWarning() {
    SpiloSpec spec = SpiloTestFixtures.readySpec();
    spec.setPostgresqlConfig(null);

    AnalysisResult result = analyzer.analyze(SpiloTestFixtures.crWith(spec));

    assertEquals(MigrationReadiness.READY_WITH_WARNINGS, result.getReadiness());
    assertTrue(hasFinding(result, Category.POSTGRES_VERSION, Severity.WARNING));
  }

  @Test
  void unsupportedSpiloEnvVarIsBlocker() {
    SpiloSpec spec = SpiloTestFixtures.readySpec();
    spec.setEnv(List.of(
        SpiloTestFixtures.walgEnv(),
        SpiloEnvVar.builder().name("SPILO_CONFIGURATION").value("...").build()));

    AnalysisResult result = analyzer.analyze(SpiloTestFixtures.crWith(spec));

    assertEquals(MigrationReadiness.BLOCKED, result.getReadiness());
    assertTrue(hasFinding(result, Category.ENV_VARS, Severity.BLOCKER));
  }

  @Test
  void missingWalgConfigurationIsBackupWarning() {
    SpiloSpec spec = SpiloTestFixtures.readySpec();
    spec.setEnv(List.of());

    AnalysisResult result = analyzer.analyze(SpiloTestFixtures.crWith(spec));

    assertTrue(hasFinding(result, Category.BACKUP, Severity.WARNING));
    assertEquals(MigrationReadiness.READY_WITH_WARNINGS, result.getReadiness());
  }

  @Test
  void walgEnvIsDetectedAsBackupInfo() {
    AnalysisResult result = analyzer.analyze(SpiloTestFixtures.readyCr());

    assertTrue(hasFinding(result, Category.BACKUP, Severity.INFO));
  }
}
