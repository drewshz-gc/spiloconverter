package de.guidecom.connect.spilo2cnpg.model.cnpg;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Represents a CloudNativePG `Cluster` Custom Resource.
 *
 * @see <a href="https://cloudnative-pg.io/documentation/current/api_reference/">CNPG API Reference</a>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CnpgCluster {

  @Builder.Default
  private final String apiVersion = "postgresql.cnpg.io/v1";

  @Builder.Default
  private final String kind = "Cluster";

  private CnpgMetadata metadata;
  private ClusterSpec spec;

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class CnpgMetadata {
    private String name;
    private String namespace;
    private Map<String, String> labels;
    private Map<String, String> annotations;

  }

  /**
   * Metadata (labels/annotations) propagated by the operator to all resources
   * (Pods, Services, PVCs, ...) created for this cluster. Required so that our
   * own labels reach the Pods and NetworkPolicies can select them.
   */
  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class InheritedMetadata {
    private Map<String, String> labels;
    private Map<String, String> annotations;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class ClusterSpec {

    private int instances;
    private String imageName;

    @JsonProperty("postgresql")
    private PostgresqlConfig postgresqlConfig;

    private StorageConfig storage;
    private ResourceConfig resources;
    private BootstrapConfig bootstrap;
    private BackupConfig backup;

    /**
     * Barman Cloud Plugin (and other CNPG-I plugins) wiring. Replaces the deprecated
     * {@code spec.backup.barmanObjectStore} for physical backups / WAL archiving.
     */
    private List<PluginConfig> plugins;

    @JsonProperty("superuserSecret")
    private SecretNameRef superuserSecret;

    @JsonProperty("enableSuperuserAccess")
    private Boolean enableSuperuserAccess;

    @JsonProperty("primaryUpdateStrategy")
    private String primaryUpdateStrategy;

    @JsonProperty("primaryUpdateMethod")
    private String primaryUpdateMethod;

    @JsonProperty("minSyncReplicas")
    private Integer minSyncReplicas;

    @JsonProperty("maxSyncReplicas")
    private Integer maxSyncReplicas;

    private List<Map<String, Object>> tolerations;

    @JsonProperty("affinity")
    private Map<String, Object> affinity;

    @JsonProperty("monitoring")
    private MonitoringConfig monitoring;

    @JsonProperty("managed")
    private ManagedConfig managed;

    @JsonProperty("certificates")
    private CertificatesConfig certificates;

    @JsonProperty("externalClusters")
    private List<ExternalCluster> externalClusters;

    @JsonProperty("inheritedMetadata")
    private InheritedMetadata inheritedMetadata;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class PostgresqlConfig {
    private Map<String, String> parameters;

    @JsonProperty("pg_hba")
    private List<String> pgHba;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class StorageConfig {
    private String size;

    @JsonProperty("storageClass")
    private String storageClass;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class ResourceConfig {
    private ResourceRequests requests;
    private ResourceRequests limits;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResourceRequests {
      private String cpu;
      private String memory;

    }

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class BootstrapConfig {
    private InitdbConfig initdb;
    private RecoveryConfig recovery;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class InitdbConfig {
    private String database;
    private String owner;
    private String secret;
    private String encoding;
    private String locale;

    @JsonProperty("postInitSQL")
    private List<String> postInitSql;

    /**
     * Logical import (type {@code monolith}/{@code microservice}) from a live source database.
     * Used for the Spilo -> CNPG migration; supports a major-version upgrade on the fly.
     */
    @JsonProperty("import")
    private ImportConfig importConfig;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class ImportConfig {
    /** {@code monolith} (all DBs into one cluster) or {@code microservice}. */
    private String type;

    /** Databases to import; {@code ["*"]} for all. */
    private List<String> databases;

    /** Roles to import; {@code ["*"]} for all. */
    private List<String> roles;

    private ImportSource source;

    @JsonProperty("pgDumpExtraOptions")
    private List<String> pgDumpExtraOptions;

    @JsonProperty("pgRestoreExtraOptions")
    private List<String> pgRestoreExtraOptions;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class ImportSource {
    @JsonProperty("externalCluster")
    private String externalCluster;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class RecoveryConfig {
    private String source;

    @JsonProperty("recoveryTarget")
    private Map<String, String> recoveryTarget;

  }

  /**
   * CNPG-I plugin reference (e.g. the Barman Cloud Plugin for physical backups / WAL archiving).
   */
  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class PluginConfig {
    private String name;
    private Boolean enabled;

    @JsonProperty("isWALArchiver")
    private Boolean isWALArchiver;

    private Map<String, String> parameters;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class BackupConfig {
    @JsonProperty("barmanObjectStore")
    private BarmanObjectStoreConfig barmanObjectStore;

    @JsonProperty("retentionPolicy")
    private String retentionPolicy;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class BarmanObjectStoreConfig {
    @JsonProperty("destinationPath")
    private String destinationPath;

    @JsonProperty("serverName")
    private String serverName;

    @JsonProperty("endpointURL")
    private String endpointUrl;

    private S3CredentialsConfig s3Credentials;
    private WalConfig wal;
    private DataConfig data;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class S3CredentialsConfig {
    @JsonProperty("accessKeyId")
    private SecretKeyRef accessKeyId;

    @JsonProperty("secretAccessKey")
    private SecretKeyRef secretAccessKey;

    private String region;

    @JsonProperty("inheritFromIAMRole")
    private Boolean inheritFromIamRole;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class WalConfig {
    private String compression;
    private String encryption;

    @JsonProperty("maxParallel")
    private Integer maxParallel;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class DataConfig {
    private String compression;
    private String encryption;

    @JsonProperty("immediateCheckpoint")
    private Boolean immediateCheckpoint;

    private Integer jobs;

  }

  /**
   * External cluster definition. Used both as the live source for a logical
   * {@code initdb.import} and as the source for WAL-recovery bootstrap.
   */
  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class ExternalCluster {
    private String name;

    @JsonProperty("barmanObjectStore")
    private BarmanObjectStoreConfig barmanObjectStore;

    @JsonProperty("connectionParameters")
    private Map<String, String> connectionParameters;

    private SecretKeyRef password;

    @JsonProperty("sslCert")
    private SecretKeyRef sslCert;

    @JsonProperty("sslKey")
    private SecretKeyRef sslKey;

    @JsonProperty("sslRootCert")
    private SecretKeyRef sslRootCert;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class CertificatesConfig {
    @JsonProperty("serverTLSSecret")
    private String serverTlsSecret;

    @JsonProperty("serverCASecret")
    private String serverCaSecret;

    @JsonProperty("clientCASecret")
    private String clientCaSecret;

    @JsonProperty("replicationTLSSecret")
    private String replicationTlsSecret;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class MonitoringConfig {
    @JsonProperty("enablePodMonitor")
    private Boolean enablePodMonitor;

    @JsonProperty("customQueriesConfigMap")
    private List<SecretKeyRef> customQueriesConfigMap;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class ManagedConfig {
    private List<RoleConfig> roles;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class RoleConfig {
    private String name;
    private boolean login;
    private boolean superuser;
    private boolean createdb;
    private boolean createrole;
    private boolean inherit;
    private boolean replication;
    private String comment;

    @JsonProperty("passwordSecret")
    private SecretKeyRef passwordSecret;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class SecretKeyRef {
    private String name;
    private String key;

  }

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class SecretNameRef {
    private String name;

  }

}
