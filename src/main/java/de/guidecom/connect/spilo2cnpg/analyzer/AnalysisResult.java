package de.guidecom.connect.spilo2cnpg.analyzer;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Result of a Spilo-to-CNPG compatibility analysis.
 */
@Data
@Builder
public class AnalysisResult {

  private String clusterName;
  private String namespace;
  private MigrationReadiness readiness;
  private List<AnalysisFinding> findings;

  public enum MigrationReadiness {
    /**
     * All checks passed, migration is safe.
     */
    READY,
    /**
     * Warnings found; migration possible but manual review required.
     */
    READY_WITH_WARNINGS,
    /**
     * Blockers found; migration must not proceed without remediation.
     */
    BLOCKED
  }

  public enum Severity {
    /**
     * Migration cannot proceed without addressing this issue.
     */
    BLOCKER,
    /**
     * Migration possible but item requires manual intervention.
     */
    WARNING,
    /**
     * Informational note, no action required.
     */
    INFO
  }

  /**
   * A single finding produced by the analyzer.
   */
  @Data
  @Builder
  public static class AnalysisFinding {

    private Severity severity;
    private Category category;
    private String title;
    private String detail;
    private String recommendation;

    public enum Category {
      POSTGRES_VERSION,
      CONFIGURATION,
      BACKUP,
      PATRONI,
      CONNECTION_POOLER,
      TLS,
      SIDECARS,
      ENV_VARS,
      RESOURCES,
      USERS_ROLES,
      STORAGE
    }

  }

}
