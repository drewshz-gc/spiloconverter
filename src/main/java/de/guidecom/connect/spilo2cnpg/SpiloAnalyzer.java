package de.guidecom.connect.spilo2cnpg;

import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult;
import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult.AnalysisFinding;
import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult.AnalysisFinding.Category;
import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult.MigrationReadiness;
import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult.Severity;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql.SpiloSpec;
import de.guidecom.connect.spilo2cnpg.support.CnpgCompatibility;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Analyzes a Spilo {@link SpiloPostgresql} CR for CNPG migration compatibility.
 *
 * <p>Checks are organized by category and produce {@link AnalysisFinding} entries
 * with severity BLOCKER, WARNING, or INFO. The overall {@link MigrationReadiness}
 * is derived from the highest severity found.
 */
@Component
@Slf4j
public class SpiloAnalyzer {

  /**
   * Spilo environment variables that have no direct CNPG equivalent.
   */
  private static final Set<String> UNSUPPORTED_SPILO_ENV_VARS = Set.of(
      "CLONE_WITH_WALE",
      "CLONE_WITH_WALG",
      "STANDBY_WITH_WALE",
      "SPILO_CONFIGURATION",
      "PAM_OAUTH2",
      "CALLBACK_SCRIPT",
      "RESTORE_COMMAND"
  );

  /**
   * Analyzes the given Spilo CR and returns a structured {@link AnalysisResult}.
   *
   * @param spilo the Spilo PostgreSQL CR to analyze
   * @return analysis result with all findings
   */
  public AnalysisResult analyze(SpiloPostgresql spilo) {
    log.info("Analyzing Spilo cluster: {}/{}", spilo.getMetadata().getNamespace(), spilo.getMetadata().getName());

    List<AnalysisFinding> findings = new ArrayList<>();
    SpiloSpec spec = spilo.getSpec();

    checkPostgresVersion(spec, findings);
    checkPatroniConfiguration(spec, findings);
    checkBackupConfiguration(spec, findings);
    checkTlsConfiguration(spec, findings);
    checkConnectionPooler(spec, findings);
    checkSidecars(spec, findings);
    checkEnvVars(spec, findings);
    checkResources(spec, findings);
    checkUsers(spec, findings);
    checkStorageConfiguration(spec, findings);
    checkPreparedDatabases(spec, findings);

    MigrationReadiness readiness = deriveReadiness(findings);

    log.info("Analysis complete for {}: readiness={}, blockers={}, warnings={}, info={}",
        spilo.getMetadata().getName(),
        readiness,
        findings.stream().filter(f -> f.getSeverity() == Severity.BLOCKER).count(),
        findings.stream().filter(f -> f.getSeverity() == Severity.WARNING).count(),
        findings.stream().filter(f -> f.getSeverity() == Severity.INFO).count());

    return AnalysisResult.builder()
        .clusterName(spilo.getMetadata().getName())
        .namespace(spilo.getMetadata().getNamespace())
        .readiness(readiness)
        .findings(findings)
        .build();
  }

  private void checkPostgresVersion(SpiloSpec spec, List<AnalysisFinding> findings) {
    if (spec.getPostgresqlConfig() == null || spec.getPostgresqlConfig().getVersion() == null) {
      findings.add(AnalysisFinding.builder()
          .severity(Severity.WARNING)
          .category(Category.POSTGRES_VERSION)
          .title("PostgreSQL version not specified")
          .detail("No explicit version found in spec.postgresql.version")
          .recommendation("Specify the target version explicitly in the CNPG Cluster spec.imageName")
          .build());
      return;
    }

    String version = spec.getPostgresqlConfig().getVersion();
    if (!CnpgCompatibility.isSupportedPostgresVersion(version)) {
      findings.add(AnalysisFinding.builder()
          .severity(Severity.BLOCKER)
          .category(Category.POSTGRES_VERSION)
          .title("PostgreSQL version " + version + " not supported by CNPG")
          .detail("CNPG supports versions: " + CnpgCompatibility.supportedVersionsDisplay())
          .recommendation("Upgrade PostgreSQL to a supported version before migrating")
          .build());
    } else {
      findings.add(AnalysisFinding.builder()
          .severity(Severity.INFO)
          .category(Category.POSTGRES_VERSION)
          .title("PostgreSQL " + version + " is supported as a migration source")
          .detail("CNPG migrates this version via a logical initdb.import directly onto the pinned "
              + "target (PG " + CnpgCompatibility.TARGET_POSTGRES_VERSION + ")")
          .recommendation("Use strategy MONOLITH_IMPORT; target image " + CnpgCompatibility.TARGET_IMAGE)
          .build());
    }
  }

  private void checkPatroniConfiguration(SpiloSpec spec, List<AnalysisFinding> findings) {
    if (spec.getPatroniConfig() == null) {
      return;
    }

    var patroni = spec.getPatroniConfig();

    if (patroni.getSynchronousMode() != null && patroni.getSynchronousMode()) {
      findings.add(AnalysisFinding.builder()
          .severity(Severity.INFO)
          .category(Category.PATRONI)
          .title("Synchronous replication mode detected")
          .detail("Patroni synchronous_mode=true")
          .recommendation("Use spec.minSyncReplicas and spec.maxSyncReplicas in CNPG Cluster spec")
          .build());
    }

    if (patroni.getSlots() != null && !patroni.getSlots().isEmpty()) {
      findings.add(AnalysisFinding.builder()
          .severity(Severity.WARNING)
          .category(Category.PATRONI)
          .title("Replication slots defined in Patroni config")
          .detail("Slots found: " + String.join(", ", patroni.getSlots().keySet()))
          .recommendation("Recreate replication slots manually on CNPG cluster after migration; "
              + "CNPG manages its own HA slots but does not migrate custom Patroni slots")
          .build());
    }

    if (patroni.getInitdb() != null && !patroni.getInitdb().isEmpty()) {
      findings.add(AnalysisFinding.builder()
          .severity(Severity.INFO)
          .category(Category.PATRONI)
          .title("Custom initdb parameters found")
          .detail("Parameters: " + patroni.getInitdb())
          .recommendation("Map to spec.bootstrap.initdb.options in CNPG if migrating fresh; "
              + "not relevant for WAL-based recovery")
          .build());
    }

    if (patroni.getFailsafeMode() != null && patroni.getFailsafeMode()) {
      findings.add(AnalysisFinding.builder()
          .severity(Severity.INFO)
          .category(Category.PATRONI)
          .title("Patroni failsafe_mode enabled")
          .detail("CNPG has its own fencing/failover mechanism and does not require failsafe_mode")
          .recommendation("No action required; CNPG handles this natively via the operator")
          .build());
    }
  }

  private void checkBackupConfiguration(SpiloSpec spec, List<AnalysisFinding> findings) {
    boolean walGConfigured = isWalGConfiguredViaEnv(spec);

    if (walGConfigured) {
      findings.add(AnalysisFinding.builder()
          .severity(Severity.INFO)
          .category(Category.BACKUP)
          .title("WAL-G backup detected via environment variables")
          .detail("Spilo archives WAL with WAL-G; this format is NOT compatible with CNPG's "
              + "barman-cloud, so the existing archive cannot be replayed by CNPG (no physical PITR)")
          .recommendation("Migrate data with a logical initdb.import (MONOLITH_IMPORT) and configure "
              + "NEW physical backups via the Barman Cloud Plugin (ObjectStore CR + spec.plugins)")
          .build());
    } else {
      findings.add(AnalysisFinding.builder()
          .severity(Severity.WARNING)
          .category(Category.BACKUP)
          .title("No WAL-G backup configuration detected")
          .detail("No WAL_S3_BUCKET, WALG_S3_PREFIX or similar env vars found")
          .recommendation("Configure physical backups via the Barman Cloud Plugin "
              + "(ObjectStore + spec.plugins); data is migrated via the logical initdb.import")
          .build());
    }

    if (spec.getEnableLogicalBackup() != null && spec.getEnableLogicalBackup()) {
      findings.add(AnalysisFinding.builder()
          .severity(Severity.INFO)
          .category(Category.BACKUP)
          .title("Logical backup (pg_dump) enabled in Spilo")
          .detail("Schedule: " + spec.getLogicalBackupSchedule())
          .recommendation("Replace with a CNPG ScheduledBackup CR or an external pg_dump CronJob")
          .build());
    }
  }

  private void checkTlsConfiguration(SpiloSpec spec, List<AnalysisFinding> findings) {
    if (spec.getTls() == null) {
      return;
    }

    var tls = spec.getTls();
    if (tls.getSecretName() != null) {
      findings.add(AnalysisFinding.builder()
          .severity(Severity.WARNING)
          .category(Category.TLS)
          .title("Custom TLS secret configured")
          .detail("Spilo TLS secret: " + tls.getSecretName())
          .recommendation("Map to spec.certificates.serverTLSSecret and spec.certificates.serverCASecret in CNPG; "
              + "ensure the secret is present in the target namespace")
          .build());
    }
  }

  private void checkConnectionPooler(SpiloSpec spec, List<AnalysisFinding> findings) {
    if (spec.getEnableConnectionPooler() == null || !spec.getEnableConnectionPooler()) {
      return;
    }

    findings.add(AnalysisFinding.builder()
        .severity(Severity.WARNING)
        .category(Category.CONNECTION_POOLER)
        .title("PgBouncer connection pooler enabled")
        .detail("Spilo deploys PgBouncer as a separate Deployment")
        .recommendation("CNPG has native PgBouncer support via spec.managed.services.additional or "
            + "a dedicated Pooler CR; configure pooler mode (transaction/session) explicitly")
        .build());
  }

  private void checkSidecars(SpiloSpec spec, List<AnalysisFinding> findings) {
    if (spec.getSidecars() == null || spec.getSidecars().isEmpty()) {
      return;
    }

    findings.add(AnalysisFinding.builder()
        .severity(Severity.WARNING)
        .category(Category.SIDECARS)
        .title(spec.getSidecars().size() + " sidecar container(s) configured")
        .detail("Sidecars: " + extractSidecarNames(spec.getSidecars()))
        .recommendation("CNPG does not natively support sidecars; "
            + "evaluate moving sidecar functionality to external services or init containers")
        .build());
  }

  private void checkEnvVars(SpiloSpec spec, List<AnalysisFinding> findings) {
    if (spec.getEnv() == null || spec.getEnv().isEmpty()) {
      return;
    }

    spec.getEnv().forEach(env -> {
      if (UNSUPPORTED_SPILO_ENV_VARS.contains(env.getName())) {
        findings.add(AnalysisFinding.builder()
            .severity(Severity.BLOCKER)
            .category(Category.ENV_VARS)
            .title("Unsupported Spilo env var: " + env.getName())
            .detail("This variable controls Spilo-specific behavior with no CNPG equivalent")
            .recommendation("Review the functionality controlled by " + env.getName()
                + " and configure the equivalent in CNPG spec before migrating")
            .build());
      }
    });
  }

  private void checkResources(SpiloSpec spec, List<AnalysisFinding> findings) {
    if (spec.getResources() == null) {
      findings.add(AnalysisFinding.builder()
          .severity(Severity.WARNING)
          .category(Category.RESOURCES)
          .title("No resource requests/limits configured")
          .detail("Running without resource constraints is not recommended in production")
          .recommendation("Add spec.resources.requests and spec.resources.limits to the CNPG Cluster spec")
          .build());
    }
  }

  private void checkUsers(SpiloSpec spec, List<AnalysisFinding> findings) {
    if (spec.getUsers() == null || spec.getUsers().isEmpty()) {
      return;
    }

    findings.add(AnalysisFinding.builder()
        .severity(Severity.INFO)
        .category(Category.USERS_ROLES)
        .title(spec.getUsers().size() + " Spilo user(s) defined")
        .detail("Users: " + spec.getUsers().stream()
            .map(SpiloPostgresql.SpiloUser::getName)
            .reduce((a, b) -> a + ", " + b)
            .orElse(""))
        .recommendation("Migrate to spec.managed.roles in CNPG; "
            + "create corresponding Kubernetes secrets for passwords")
        .build());
  }

  private void checkStorageConfiguration(SpiloSpec spec, List<AnalysisFinding> findings) {
    if (spec.getVolume() == null) {
      findings.add(AnalysisFinding.builder()
          .severity(Severity.WARNING)
          .category(Category.STORAGE)
          .title("No volume configuration found")
          .detail("Storage size and storageClass are required for CNPG")
          .recommendation("Add spec.storage.size and optionally spec.storage.storageClass")
          .build());
      return;
    }

    findings.add(AnalysisFinding.builder()
        .severity(Severity.INFO)
        .category(Category.STORAGE)
        .title("Storage: " + spec.getVolume().getSize())
        .detail("StorageClass: " + (spec.getVolume().getStorageClass() != null
            ? spec.getVolume().getStorageClass() : "default"))
        .recommendation("Map to spec.storage.size and spec.storage.storageClass in CNPG")
        .build());
  }

  private void checkPreparedDatabases(SpiloSpec spec, List<AnalysisFinding> findings) {
    if (spec.getPreparedDatabases() == null || spec.getPreparedDatabases().isEmpty()) {
      return;
    }
    findings.add(AnalysisFinding.builder()
        .severity(Severity.WARNING)
        .category(Category.CONFIGURATION)
        .title(spec.getPreparedDatabases().size() + " preparedDatabase(s) defined")
        .detail("Databases: " + String.join(", ", spec.getPreparedDatabases().keySet()))
        .recommendation("preparedDatabases has no direct CNPG equivalent; "
            + "create databases via spec.bootstrap.initdb.postInitSQL or an external init Job")
        .build());
  }

  private MigrationReadiness deriveReadiness(List<AnalysisFinding> findings) {
    boolean hasBlockers = findings.stream().anyMatch(f -> f.getSeverity() == Severity.BLOCKER);
    boolean hasWarnings = findings.stream().anyMatch(f -> f.getSeverity() == Severity.WARNING);

    if (hasBlockers) {
      return MigrationReadiness.BLOCKED;
    }
    if (hasWarnings) {
      return MigrationReadiness.READY_WITH_WARNINGS;
    }
    return MigrationReadiness.READY;
  }

  private boolean isWalGConfiguredViaEnv(SpiloSpec spec) {
    if (spec.getEnv() == null) {
      return false;
    }
    return spec.getEnv().stream()
        .anyMatch(env -> env.getName() != null && (
            env.getName().startsWith("WALG_") ||
                env.getName().startsWith("WAL_S3") ||
                env.getName().startsWith("WAL_GCS") ||
                env.getName().startsWith("WAL_AZ")));
  }

  private String extractSidecarNames(List<Map<String, Object>> sidecars) {
    return sidecars.stream()
        .map(s -> (String) s.getOrDefault("name", "unknown"))
        .reduce((a, b) -> a + ", " + b)
        .orElse("");
  }

}
