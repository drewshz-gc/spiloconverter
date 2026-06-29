package de.guidecom.connect.spilo2cnpg;

import de.guidecom.connect.spilo2cnpg.support.CnpgCompatibility;
import lombok.Builder;
import lombok.Data;

/**
 * Options controlling how a Spilo CR is converted to a CNPG manifest set.
 */
@Data
@Builder
public class ConversionOptions {

  /**
   * How the CNPG cluster should bootstrap.
   * Default: {@link MigrationStrategy#MONOLITH_IMPORT} (logical import, supports a major upgrade).
   */
  @Builder.Default
  private MigrationStrategy strategy = MigrationStrategy.MONOLITH_IMPORT;

  /**
   * Target Kubernetes namespace for the generated manifests.
   * Falls back to the source cluster's namespace when {@code null}.
   */
  private String targetNamespace;

  /**
   * Pinned target image for migrated clusters. Defaults to {@link CnpgCompatibility#TARGET_IMAGE}.
   *
   * <p>For {@link MigrationStrategy#WAL_RECOVERY} the major version must not change, so the source
   * version's image is used instead and this value is ignored.
   */
  @Builder.Default
  private String targetImage = CnpgCompatibility.TARGET_IMAGE;

  /**
   * {@code max_connections} for the target cluster. Pooling (see {@link #generatePooler}) keeps the
   * server-side connection count low, so a moderate value is the pilot default.
   */
  @Builder.Default
  private Integer maxConnections = 150;

  /**
   * Whether {@code enable_indexscan} is forced on (pilot requirement).
   */
  @Builder.Default
  private boolean enableIndexScan = true;

  // --- Backup (Barman Cloud Plugin / ObjectStore) -------------------------------------------

  /**
   * S3 bucket for the Barman Cloud Plugin ObjectStore. When {@code null} the backup plugin,
   * ObjectStore and ScheduledBackup are skipped (the cluster is generated without physical backups).
   */
  private String s3Bucket;

  /**
   * S3 endpoint URL for on-prem / S3-compatible storage (e.g. Ceph/MinIO). Optional for AWS S3.
   */
  private String s3EndpointUrl;

  /**
   * Full destination path override. When {@code null} it is derived as
   * {@code s3://<bucket>/cnpg/<cnpgClusterName>}.
   */
  private String s3DestinationPath;

  /**
   * Name of the Kubernetes secret holding the S3 credentials (keys {@code ACCESS_KEY_ID} /
   * {@code ACCESS_SECRET_KEY}). When {@code null} it is derived as {@code <cnpgClusterName>-backup}.
   */
  private String backupCredentialsSecret;

  // --- Companion resources ------------------------------------------------------------------

  /**
   * Emit the Barman Cloud Plugin wiring (cluster {@code spec.plugins} + ObjectStore CR). Requires
   * {@link #s3Bucket}.
   */
  @Builder.Default
  private boolean generateBackupPlugin = true;

  /**
   * Emit a {@code ScheduledBackup} (method {@code plugin}). Requires the backup plugin.
   */
  @Builder.Default
  private boolean generateScheduledBackup = true;

  /**
   * Emit read-write and read-only {@code Pooler} (PgBouncer) CRs.
   */
  @Builder.Default
  private boolean generatePooler = true;

  /**
   * Emit standalone {@code PodMonitor} CRs (cluster + poolers). Replaces the deprecated
   * {@code spec.monitoring.enablePodMonitor}.
   */
  @Builder.Default
  private boolean generatePodMonitor = true;

  /**
   * Add the platform egress labels ({@code networking.gc/to-apiserver}, {@code networking.gc/to-s3})
   * and the {@code application=cnpg} identity label so the operator-managed pods integrate with the
   * default-deny-egress network policy model.
   */
  @Builder.Default
  private boolean addNetworkingLabels = true;

  /**
   * Whether to enable the CNPG superuser. Defaults to {@code false}; CNPG recommends keeping
   * superuser access disabled unless explicitly required.
   */
  @Builder.Default
  private boolean enableSuperuserAccess = false;

  /**
   * When {@code true}, ArgoCD sync annotations / sync-waves are added so the manifests sync cleanly
   * under GitOps (server-side apply, skip dry-run on a missing CNPG CRD, ordered waves).
   */
  @Builder.Default
  private boolean argocd = false;

  /**
   * Optional ArgoCD sync-wave for the generated Cluster. Only emitted when {@link #argocd} is
   * {@code true}; companion resources are placed on later waves relative to it.
   */
  private Integer syncWave;

  public enum MigrationStrategy {
    /**
     * Logical import via {@code bootstrap.initdb.import} (type {@code monolith}) from the live
     * Spilo primary. Performs a major-version upgrade on the fly (e.g. PG15 -> PG18). Default and
     * recommended path for Spilo -> CNPG.
     */
    MONOLITH_IMPORT,

    /**
     * Bootstrap a fresh, empty CNPG cluster via {@code initdb}. Data must be migrated separately.
     */
    INITDB,

    /**
     * Bootstrap via WAL recovery from an existing Barman-compatible archive in S3.
     *
     * <p>NOTE: Spilo archives WAL with WAL-G, whose format is NOT compatible with CNPG's
     * barman-cloud. This strategy therefore only works when the source already archives in
     * Barman format, and it cannot change the major version.
     */
    WAL_RECOVERY
  }

}
