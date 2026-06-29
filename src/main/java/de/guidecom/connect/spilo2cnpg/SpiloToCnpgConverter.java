package de.guidecom.connect.spilo2cnpg;

import de.guidecom.connect.spilo2cnpg.ConversionOptions.MigrationStrategy;
import de.guidecom.connect.spilo2cnpg.model.cnpg.CnpgCluster;
import de.guidecom.connect.spilo2cnpg.model.cnpg.CnpgCluster.*;
import de.guidecom.connect.spilo2cnpg.model.cnpg.CnpgCluster.ResourceConfig.ResourceRequests;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql.SpiloSpec;
import de.guidecom.connect.spilo2cnpg.support.CnpgCompatibility;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a Spilo {@link SpiloPostgresql} CR into a CNPG manifest set: the {@link CnpgCluster}
 * plus the companion resources (Barman Cloud ObjectStore, Poolers, PodMonitors, ScheduledBackup)
 * established by the CCPSQL-128 pilot.
 *
 * <p>Only fields with a direct CNPG equivalent are mapped automatically.
 * Fields that require manual intervention are noted in log warnings during conversion.
 */
@Component
@Slf4j
public class SpiloToCnpgConverter {

  private static final String EXTERNAL_CLUSTER_PREFIX = "spilo-";
  private static final String ARGOCD_SYNC_OPTIONS = "ServerSideApply=true,SkipDryRunOnMissingResource=true";
  private static final String BARMAN_PLUGIN = "barman-cloud.cloudnative-pg.io";

  /**
   * Spilo/Zalando-specific dump scaffolding that must be excluded, otherwise {@code pg_restore}
   * (pre-data) aborts: the {@code metric_helpers} monitoring schema and extensions that are not
   * present in the vanilla CNPG image. {@code --no-acl} skips the related GRANT/REVOKE statements.
   */
  private static final List<String> DEFAULT_PG_DUMP_EXTRA_OPTIONS = List.of(
      "--exclude-schema=metric_helpers",
      "--exclude-extension=pg_stat_kcache",
      "--exclude-extension=set_user",
      "--no-acl");

  // -------------------------------------------------------------------------
  // Public API
  // -------------------------------------------------------------------------

  /**
   * Converts the given Spilo CR to the core CNPG Cluster CR.
   */
  public CnpgCluster convert(SpiloPostgresql spilo, ConversionOptions options) {
    log.info("Converting Spilo cluster: {}/{}", spilo.getMetadata().getNamespace(), spilo.getMetadata().getName());

    SpiloSpec spec = spilo.getSpec();
    String sourceClusterName = spilo.getMetadata().getName();
    String cnpgClusterName = toCnpgClusterName(sourceClusterName);
    String targetNamespace = resolveNamespace(spilo, options);

    return CnpgCluster.builder()
        .metadata(buildMetadata(spilo, cnpgClusterName, targetNamespace, options))
        .spec(buildSpec(spec, spilo, cnpgClusterName, sourceClusterName, targetNamespace, options))
        .build();
  }

  /**
   * Converts the Spilo CR into the full ordered manifest set: the Cluster first, followed by the
   * companion resources (ObjectStore, Poolers, PodMonitors, ScheduledBackup) that the selected
   * options enable. Companion resources are plain ordered maps so they serialize as additional
   * YAML documents.
   */
  public List<Object> convertAll(SpiloPostgresql spilo, ConversionOptions options) {
    CnpgCluster cluster = convert(spilo, options);
    String cnpgClusterName = cluster.getMetadata().getName();
    String namespace = cluster.getMetadata().getNamespace();
    String tenant = deriveTenant(spilo);

    List<Object> manifests = new ArrayList<>();
    manifests.add(cluster);

    if (isBackupPluginEnabled(options)) {
      manifests.add(buildObjectStore(cnpgClusterName, namespace, tenant, options));
    }
    if (options.isGeneratePooler()) {
      manifests.add(buildPooler(cnpgClusterName, namespace, tenant, "rw", options));
      manifests.add(buildPooler(cnpgClusterName, namespace, tenant, "ro", options));
    }
    if (options.isGeneratePodMonitor()) {
      manifests.add(buildPodMonitorForCluster(cnpgClusterName, namespace, tenant, options));
      if (options.isGeneratePooler()) {
        manifests.add(buildPodMonitorForPooler(cnpgClusterName, namespace, tenant, "rw", options));
        manifests.add(buildPodMonitorForPooler(cnpgClusterName, namespace, tenant, "ro", options));
      }
    }
    if (isBackupPluginEnabled(options) && options.isGenerateScheduledBackup()) {
      manifests.add(buildScheduledBackup(cnpgClusterName, namespace, options));
    }
    return manifests;
  }

  // -------------------------------------------------------------------------
  // Naming helpers
  // -------------------------------------------------------------------------

  /**
   * Derives the CNPG cluster name from the Spilo CR name following the naming
   * convention {@code <tenant><nn>-cnpg-cluster} (see CCPSQL-130). The legacy
   * Spilo suffix {@code -postgres-cluster} (or a bare {@code -cluster}) is
   * replaced by {@code -cnpg-cluster}.
   */
  static String toCnpgClusterName(String spiloName) {
    return baseName(spiloName) + "-cnpg-cluster";
  }

  /**
   * Name of the external cluster entry that points at the live Spilo primary
   * used as the logical-import source, e.g. {@code spilo-tifa01-source}.
   */
  static String importSourceName(String spiloName) {
    return EXTERNAL_CLUSTER_PREFIX + baseName(spiloName) + "-source";
  }

  private static String baseName(String spiloName) {
    String base = spiloName;
    if (base.endsWith("-postgres-cluster")) {
      base = base.substring(0, base.length() - "-postgres-cluster".length());
    } else if (base.endsWith("-cluster")) {
      base = base.substring(0, base.length() - "-cluster".length());
    }
    return base;
  }

  private static String objectStoreName(String cnpgClusterName) {
    return cnpgClusterName + "-store";
  }

  private static String backupCredentialsSecret(String cnpgClusterName, ConversionOptions options) {
    return options.getBackupCredentialsSecret() != null
        ? options.getBackupCredentialsSecret()
        : cnpgClusterName + "-backup";
  }

  private static String monitoringConfigMapName(String cnpgClusterName) {
    return cnpgClusterName + "-monitoring";
  }

  private static String zalandoSecretName(String user, String spiloName) {
    return user + "." + spiloName + ".credentials.postgresql.acid.zalan.do";
  }

  private static String resolveNamespace(SpiloPostgresql spilo, ConversionOptions options) {
    return options.getTargetNamespace() != null
        ? options.getTargetNamespace()
        : spilo.getMetadata().getNamespace();
  }

  // -------------------------------------------------------------------------
  // Labels
  // -------------------------------------------------------------------------

  /**
   * Builds the common label set (CCPSQL-130). These labels are set on the Cluster CR.
   */
  private Map<String, String> buildCommonLabels(SpiloPostgresql spilo, String cnpgClusterName, String namespace) {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("app.kubernetes.io/name", "postgresql");
    labels.put("app.kubernetes.io/instance", cnpgClusterName);
    labels.put("app.kubernetes.io/managed-by", "spilo2cnpg");
    String tenant = deriveTenant(spilo);
    if (tenant != null) {
      labels.put("app.kubernetes.io/part-of", tenant);
      labels.put("tenant", tenant);
    }
    String environment = deriveEnvironment(namespace);
    if (environment != null) {
      labels.put("environment", environment);
    }
    labels.put("spilo2cnpg/source-cluster", spilo.getMetadata().getName());
    return labels;
  }

  /**
   * Labels propagated to all operator-managed resources (pods, services, PVCs and the bootstrap /
   * import job pods). In addition to the convention labels this adds the platform egress labels and
   * the {@code application=cnpg} identity label so CNPG integrates with the default-deny-egress model
   * and is not selected by Spilo's {@code application=spilo} selectors during parallel operation.
   */
  private Map<String, String> buildInheritedLabels(SpiloPostgresql spilo, String cnpgClusterName,
      String namespace, ConversionOptions options) {
    Map<String, String> labels = buildCommonLabels(spilo, cnpgClusterName, namespace);
    if (options.isAddNetworkingLabels()) {
      labels.put("networking.gc/to-apiserver", "allowed");
      labels.put("networking.gc/to-s3", "allowed");
      labels.put("application", "cnpg");
    }
    return labels;
  }

  private static String deriveTenant(SpiloPostgresql spilo) {
    if (spilo.getSpec() != null && spilo.getSpec().getTeamId() != null
        && !spilo.getSpec().getTeamId().isBlank()) {
      return spilo.getSpec().getTeamId();
    }
    if (spilo.getMetadata().getLabels() != null) {
      String team = spilo.getMetadata().getLabels().get("team");
      if (team != null && !team.isBlank()) {
        return team;
      }
    }
    return null;
  }

  private static String deriveEnvironment(String namespace) {
    if (namespace == null) {
      return null;
    }
    if (namespace.equals("prod") || namespace.endsWith("-prod")) {
      return "prod";
    }
    if (namespace.endsWith("-stg") || namespace.endsWith("-staging")) {
      return "stg";
    }
    if (namespace.endsWith("-dev")) {
      return "dev";
    }
    if (namespace.endsWith("-test")) {
      return "test";
    }
    return null;
  }

  private CnpgMetadata buildMetadata(SpiloPostgresql spilo, String cnpgClusterName,
      String targetNamespace, ConversionOptions options) {
    Map<String, String> labels = buildCommonLabels(spilo, cnpgClusterName, targetNamespace);

    if (spilo.getMetadata().getLabels() != null) {
      spilo.getMetadata().getLabels().forEach((k, v) -> {
        if (!k.startsWith("team") && !k.startsWith("acid.zalan.do") && !labels.containsKey(k)) {
          labels.put(k, v);
        }
      });
    }

    return CnpgMetadata.builder()
        .name(cnpgClusterName)
        .namespace(targetNamespace)
        .labels(labels)
        .annotations(buildArgocdAnnotations(options, options.getSyncWave()))
        .build();
  }

  /**
   * Builds ArgoCD-specific annotations so the generated resource syncs cleanly under GitOps.
   *
   * @return the annotations map, or {@code null} when ArgoCD mode is disabled (field is then omitted)
   */
  private Map<String, String> buildArgocdAnnotations(ConversionOptions options, Integer wave) {
    if (!options.isArgocd()) {
      return null;
    }
    Map<String, String> annotations = new LinkedHashMap<>();
    annotations.put("argocd.argoproj.io/sync-options", ARGOCD_SYNC_OPTIONS);
    if (wave != null) {
      annotations.put("argocd.argoproj.io/sync-wave", String.valueOf(wave));
    }
    return annotations;
  }

  /**
   * Annotations for companion resources: only a sync-wave (no sync-options needed), and only in
   * ArgoCD mode.
   */
  private Map<String, String> buildWaveAnnotations(ConversionOptions options, int wave) {
    if (!options.isArgocd()) {
      return null;
    }
    Map<String, String> annotations = new LinkedHashMap<>();
    annotations.put("argocd.argoproj.io/sync-wave", String.valueOf(wave));
    return annotations;
  }

  // -------------------------------------------------------------------------
  // Cluster spec
  // -------------------------------------------------------------------------

  private ClusterSpec buildSpec(SpiloSpec spec, SpiloPostgresql spilo, String cnpgClusterName,
      String sourceClusterName, String targetNamespace, ConversionOptions options) {
    ClusterSpec.ClusterSpecBuilder builder = ClusterSpec.builder()
        .instances(spec.getNumberOfInstances())
        .imageName(resolveImageName(spec, options))
        .postgresqlConfig(buildPostgresqlConfig(options))
        .storage(buildStorage(spec))
        .resources(buildResources(spec))
        .bootstrap(buildBootstrap(spec, sourceClusterName, options))
        .plugins(buildPlugins(cnpgClusterName, options))
        .enableSuperuserAccess(options.isEnableSuperuserAccess())
        .primaryUpdateStrategy("unsupervised")
        .primaryUpdateMethod("switchover")
        .monitoring(buildMonitoring(cnpgClusterName))
        .managed(buildManagedRoles(spec, sourceClusterName, options))
        .tolerations(spec.getTolerations())
        .certificates(buildCertificates(spec))
        .inheritedMetadata(InheritedMetadata.builder()
            .labels(buildInheritedLabels(spilo, cnpgClusterName, targetNamespace, options))
            .build());

    if (options.getStrategy() == MigrationStrategy.MONOLITH_IMPORT) {
      builder.externalClusters(buildImportExternalClusters(sourceClusterName));
    } else if (options.getStrategy() == MigrationStrategy.WAL_RECOVERY) {
      builder.externalClusters(buildWalExternalClusters(sourceClusterName, options));
    }

    if (spec.getPatroniConfig() != null && Boolean.TRUE.equals(spec.getPatroniConfig().getSynchronousMode())) {
      if (spec.getNumberOfInstances() >= 2) {
        builder.minSyncReplicas(1);
        builder.maxSyncReplicas(Math.max(1, spec.getNumberOfInstances() - 1));
      } else {
        log.warn("Synchronous mode requested but numberOfInstances={} (<2); "
            + "skipping minSyncReplicas/maxSyncReplicas to avoid an invalid CNPG spec",
            spec.getNumberOfInstances());
      }
    }

    return builder.build();
  }

  /**
   * Resolves the target image. For the logical migration paths the cluster is created directly on
   * the pinned target major (PG18). {@code WAL_RECOVERY} cannot change the major version, so the
   * source version's image is used instead.
   */
  private String resolveImageName(SpiloSpec spec, ConversionOptions options) {
    if (options.getStrategy() == MigrationStrategy.WAL_RECOVERY) {
      String version = spec.getPostgresqlConfig() != null ? spec.getPostgresqlConfig().getVersion() : null;
      if (version == null) {
        log.warn("WAL_RECOVERY without a source version; defaulting image to target {}", options.getTargetImage());
        return options.getTargetImage();
      }
      if (!CnpgCompatibility.isSupportedPostgresVersion(version)) {
        log.warn("Source PostgreSQL version {} is not in the supported set ({})",
            version, CnpgCompatibility.supportedVersionsDisplay());
      }
      return CnpgCompatibility.IMAGE_BASE + ":" + version;
    }
    return options.getTargetImage();
  }

  /**
   * Curated target parameters. {@code max_connections} is moderated (pooling handles client fan-out)
   * and {@code enable_indexscan} is forced on per pilot requirement.
   */
  private PostgresqlConfig buildPostgresqlConfig(ConversionOptions options) {
    Map<String, String> parameters = new LinkedHashMap<>();
    if (options.getMaxConnections() != null) {
      parameters.put("max_connections", String.valueOf(options.getMaxConnections()));
    }
    parameters.put("enable_indexscan", options.isEnableIndexScan() ? "on" : "off");
    return PostgresqlConfig.builder().parameters(parameters).build();
  }

  private StorageConfig buildStorage(SpiloSpec spec) {
    if (spec.getVolume() == null) {
      log.warn("No volume config found, using default 10Gi");
      return StorageConfig.builder().size("10Gi").build();
    }
    return StorageConfig.builder()
        .size(spec.getVolume().getSize())
        .storageClass(spec.getVolume().getStorageClass())
        .build();
  }

  private ResourceConfig buildResources(SpiloSpec spec) {
    if (spec.getResources() == null) {
      return null;
    }

    var src = spec.getResources();

    ResourceRequests requests = src.getRequests() == null ? null :
        ResourceRequests.builder()
            .cpu(src.getRequests().getCpu())
            .memory(src.getRequests().getMemory())
            .build();

    ResourceRequests limits = src.getLimits() == null ? null :
        ResourceRequests.builder()
            .cpu(src.getLimits().getCpu())
            .memory(src.getLimits().getMemory())
            .build();

    return ResourceConfig.builder()
        .requests(requests)
        .limits(limits)
        .build();
  }

  // -------------------------------------------------------------------------
  // Bootstrap
  // -------------------------------------------------------------------------

  private BootstrapConfig buildBootstrap(SpiloSpec spec, String sourceClusterName, ConversionOptions options) {
    return switch (options.getStrategy()) {
      case MONOLITH_IMPORT -> buildMonolithImportBootstrap(sourceClusterName);
      case INITDB -> buildInitdbBootstrap(spec);
      case WAL_RECOVERY -> buildWalRecoveryBootstrap(sourceClusterName);
    };
  }

  private BootstrapConfig buildMonolithImportBootstrap(String sourceClusterName) {
    ImportConfig importConfig = ImportConfig.builder()
        .type("monolith")
        .databases(List.of("*"))
        .roles(List.of("*"))
        .source(ImportSource.builder().externalCluster(importSourceName(sourceClusterName)).build())
        .pgDumpExtraOptions(new ArrayList<>(DEFAULT_PG_DUMP_EXTRA_OPTIONS))
        .build();
    return BootstrapConfig.builder()
        .initdb(InitdbConfig.builder().importConfig(importConfig).build())
        .build();
  }

  private BootstrapConfig buildInitdbBootstrap(SpiloSpec spec) {
    String ownerDb = spec.getDatabases() != null && !spec.getDatabases().isEmpty()
        ? spec.getDatabases().get(0)
        : "app";

    return BootstrapConfig.builder()
        .initdb(InitdbConfig.builder()
            .database(ownerDb)
            .owner(ownerDb)
            .build())
        .build();
  }

  private BootstrapConfig buildWalRecoveryBootstrap(String sourceClusterName) {
    return BootstrapConfig.builder()
        .recovery(RecoveryConfig.builder()
            .source(EXTERNAL_CLUSTER_PREFIX + baseName(sourceClusterName))
            .build())
        .build();
  }

  // -------------------------------------------------------------------------
  // External clusters
  // -------------------------------------------------------------------------

  /**
   * Live connection to the running Spilo primary, used as the logical-import source. The Spilo
   * master service is reachable under the cluster name; credentials come from the Zalando-managed
   * {@code postgres} user secret.
   */
  private List<ExternalCluster> buildImportExternalClusters(String sourceClusterName) {
    Map<String, String> connection = new LinkedHashMap<>();
    connection.put("host", sourceClusterName);
    connection.put("port", "5432");
    connection.put("user", "postgres");
    connection.put("dbname", "postgres");
    connection.put("sslmode", "require");

    ExternalCluster source = ExternalCluster.builder()
        .name(importSourceName(sourceClusterName))
        .connectionParameters(connection)
        .password(SecretKeyRef.builder()
            .name(zalandoSecretName("postgres", sourceClusterName))
            .key("password")
            .build())
        .build();
    return List.of(source);
  }

  private List<ExternalCluster> buildWalExternalClusters(String sourceClusterName, ConversionOptions options) {
    if (options.getS3Bucket() == null) {
      log.warn("WAL_RECOVERY strategy selected but no s3Bucket provided; externalClusters will be incomplete");
      return null;
    }
    String cnpgName = toCnpgClusterName(sourceClusterName);
    ExternalCluster externalCluster = ExternalCluster.builder()
        .name(EXTERNAL_CLUSTER_PREFIX + baseName(sourceClusterName))
        .barmanObjectStore(BarmanObjectStoreConfig.builder()
            .destinationPath(destinationPath(cnpgName, options))
            .serverName(cnpgName)
            .endpointUrl(options.getS3EndpointUrl())
            .s3Credentials(s3Credentials(cnpgName, options))
            .wal(WalConfig.builder().compression("gzip").build())
            .data(DataConfig.builder().compression("gzip").build())
            .build())
        .build();
    return List.of(externalCluster);
  }

  // -------------------------------------------------------------------------
  // Backups (Barman Cloud Plugin)
  // -------------------------------------------------------------------------

  private boolean isBackupPluginEnabled(ConversionOptions options) {
    if (!options.isGenerateBackupPlugin()) {
      return false;
    }
    if (options.getS3Bucket() == null && options.getS3DestinationPath() == null) {
      log.warn("Backup plugin requested but neither s3Bucket nor s3DestinationPath set; "
          + "skipping ObjectStore/plugin/ScheduledBackup");
      return false;
    }
    return true;
  }

  private List<PluginConfig> buildPlugins(String cnpgClusterName, ConversionOptions options) {
    if (!isBackupPluginEnabled(options)) {
      return null;
    }
    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("barmanObjectName", objectStoreName(cnpgClusterName));
    parameters.put("serverName", cnpgClusterName);
    return List.of(PluginConfig.builder()
        .name(BARMAN_PLUGIN)
        .isWALArchiver(true)
        .parameters(parameters)
        .build());
  }

  private String destinationPath(String cnpgClusterName, ConversionOptions options) {
    if (options.getS3DestinationPath() != null) {
      return options.getS3DestinationPath();
    }
    return "s3://" + options.getS3Bucket() + "/cnpg/" + cnpgClusterName;
  }

  private S3CredentialsConfig s3Credentials(String cnpgClusterName, ConversionOptions options) {
    String secret = backupCredentialsSecret(cnpgClusterName, options);
    return S3CredentialsConfig.builder()
        .accessKeyId(SecretKeyRef.builder().name(secret).key("ACCESS_KEY_ID").build())
        .secretAccessKey(SecretKeyRef.builder().name(secret).key("ACCESS_SECRET_KEY").build())
        .build();
  }

  // -------------------------------------------------------------------------
  // Monitoring / managed roles / certs
  // -------------------------------------------------------------------------

  /**
   * Monitoring config that references a custom-queries ConfigMap. {@code enablePodMonitor} is left
   * unset (deprecated); scraping is done via the standalone PodMonitor companion resources.
   */
  private MonitoringConfig buildMonitoring(String cnpgClusterName) {
    return MonitoringConfig.builder()
        .customQueriesConfigMap(List.of(SecretKeyRef.builder()
            .name(monitoringConfigMapName(cnpgClusterName))
            .key("custom-queries")
            .build()))
        .build();
  }

  private ManagedConfig buildManagedRoles(SpiloSpec spec, String sourceClusterName, ConversionOptions options) {
    if (spec.getUsers() == null || spec.getUsers().isEmpty()) {
      return null;
    }

    List<RoleConfig> roles = spec.getUsers().stream()
        .map(user -> {
          List<String> opts = user.getOptions() != null ? user.getOptions() : List.of();
          return RoleConfig.builder()
              .name(user.getName())
              .login(true)
              .superuser(options.isEnableSuperuserAccess() && opts.contains("superuser"))
              .createdb(opts.contains("createdb"))
              .createrole(opts.contains("createrole"))
              .inherit(true)
              .replication(opts.contains("replication"))
              .passwordSecret(SecretKeyRef.builder()
                  .name(zalandoSecretName(user.getName(), sourceClusterName))
                  .build())
              .build();
        })
        .toList();

    return ManagedConfig.builder().roles(roles).build();
  }

  private CertificatesConfig buildCertificates(SpiloSpec spec) {
    if (spec.getTls() == null || spec.getTls().getSecretName() == null) {
      return null;
    }

    log.warn("Custom TLS secret '{}' detected; map manually to spec.certificates in CNPG", spec.getTls().getSecretName());

    return CertificatesConfig.builder()
        .serverTlsSecret(spec.getTls().getSecretName())
        .build();
  }

  // -------------------------------------------------------------------------
  // Companion resources (emitted as ordered maps -> extra YAML documents)
  // -------------------------------------------------------------------------

  private Map<String, Object> resourceMeta(String name, String namespace, String tenant, Map<String, String> annotations) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", name);
    metadata.put("namespace", namespace);
    if (annotations != null) {
      metadata.put("annotations", annotations);
    }
    Map<String, String> labels = new LinkedHashMap<>();
    if (tenant != null) {
      labels.put("tenant", tenant);
    }
    labels.put("app.kubernetes.io/managed-by", "spilo2cnpg");
    metadata.put("labels", labels);
    return metadata;
  }

  private Map<String, Object> buildObjectStore(String cnpgClusterName, String namespace,
      String tenant, ConversionOptions options) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("apiVersion", "barmancloud.cnpg.io/v1");
    root.put("kind", "ObjectStore");
    root.put("metadata", resourceMeta(objectStoreName(cnpgClusterName), namespace, tenant,
        buildWaveAnnotations(options, 0)));

    Map<String, Object> configuration = new LinkedHashMap<>();
    if (options.getS3EndpointUrl() != null) {
      configuration.put("endpointURL", options.getS3EndpointUrl());
    }
    configuration.put("destinationPath", destinationPath(cnpgClusterName, options));

    String secret = backupCredentialsSecret(cnpgClusterName, options);
    Map<String, Object> s3Credentials = new LinkedHashMap<>();
    s3Credentials.put("accessKeyId", keyRef(secret, "ACCESS_KEY_ID"));
    s3Credentials.put("secretAccessKey", keyRef(secret, "ACCESS_SECRET_KEY"));
    configuration.put("s3Credentials", s3Credentials);
    configuration.put("wal", Map.of("compression", "gzip"));
    configuration.put("data", Map.of("compression", "gzip"));

    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("retentionPolicy", "30d");
    spec.put("configuration", configuration);

    // On-prem / S3-compatible stores (Ceph/MinIO): disable newer aws-sdk checksums, otherwise
    // barman-cloud uploads fail. Only relevant when a custom endpoint is in use.
    if (options.getS3EndpointUrl() != null) {
      Map<String, Object> sidecar = new LinkedHashMap<>();
      sidecar.put("env", List.of(
          envVar("AWS_REQUEST_CHECKSUM_CALCULATION", "when_required"),
          envVar("AWS_RESPONSE_CHECKSUM_VALIDATION", "when_required")));
      spec.put("instanceSidecarConfiguration", sidecar);
    }

    root.put("spec", spec);
    return root;
  }

  private Map<String, Object> buildPooler(String cnpgClusterName, String namespace,
      String tenant, String type, ConversionOptions options) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("apiVersion", "postgresql.cnpg.io/v1");
    root.put("kind", "Pooler");
    root.put("metadata", resourceMeta(cnpgClusterName + "-pooler-" + type, namespace, tenant,
        buildWaveAnnotations(options, 1)));

    Map<String, Object> spec = new LinkedHashMap<>();
    if (options.isAddNetworkingLabels()) {
      Map<String, String> podLabels = new LinkedHashMap<>();
      podLabels.put("application", "cnpg");
      podLabels.put("networking.gc/to-apiserver", "allowed");
      spec.put("template", Map.of("metadata", Map.of("labels", podLabels)));
    }
    spec.put("cluster", Map.of("name", cnpgClusterName));
    spec.put("instances", 1);
    spec.put("type", type);

    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("max_client_conn", "1000");
    parameters.put("default_pool_size", "10");
    parameters.put("max_db_connections", "20");
    parameters.put("reserve_pool_size", "5");
    parameters.put("reserve_pool_timeout", "3");
    Map<String, Object> pgbouncer = new LinkedHashMap<>();
    pgbouncer.put("poolMode", "transaction");
    pgbouncer.put("parameters", parameters);
    spec.put("pgbouncer", pgbouncer);

    root.put("spec", spec);
    return root;
  }

  private Map<String, Object> buildPodMonitorForCluster(String cnpgClusterName, String namespace,
      String tenant, ConversionOptions options) {
    Map<String, String> selector = new LinkedHashMap<>();
    selector.put("cnpg.io/cluster", cnpgClusterName);
    selector.put("cnpg.io/podRole", "instance");
    return buildPodMonitor(cnpgClusterName, namespace, tenant, selector, options);
  }

  private Map<String, Object> buildPodMonitorForPooler(String cnpgClusterName, String namespace,
      String tenant, String type, ConversionOptions options) {
    String name = cnpgClusterName + "-pooler-" + type;
    return buildPodMonitor(name, namespace, tenant, Map.of("cnpg.io/poolerName", name), options);
  }

  private Map<String, Object> buildPodMonitor(String name, String namespace, String tenant,
      Map<String, String> matchLabels, ConversionOptions options) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("apiVersion", "monitoring.coreos.com/v1");
    root.put("kind", "PodMonitor");
    root.put("metadata", resourceMeta(name, namespace, tenant, buildWaveAnnotations(options, 2)));

    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("namespaceSelector", Map.of());
    spec.put("selector", Map.of("matchLabels", matchLabels));
    spec.put("podMetricsEndpoints", List.of(Map.of("port", "metrics")));
    root.put("spec", spec);
    return root;
  }

  private Map<String, Object> buildScheduledBackup(String cnpgClusterName, String namespace,
      ConversionOptions options) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("apiVersion", "postgresql.cnpg.io/v1");
    root.put("kind", "ScheduledBackup");

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", cnpgClusterName + "-daily");
    metadata.put("namespace", namespace);
    Map<String, String> waveAnnotations = buildWaveAnnotations(options, 2);
    if (waveAnnotations != null) {
      metadata.put("annotations", waveAnnotations);
    }
    root.put("metadata", metadata);

    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("schedule", "0 0 1 * * *");
    spec.put("backupOwnerReference", "self");
    spec.put("cluster", Map.of("name", cnpgClusterName));
    spec.put("method", "plugin");
    spec.put("pluginConfiguration", Map.of("name", BARMAN_PLUGIN));
    root.put("spec", spec);
    return root;
  }

  private static Map<String, Object> keyRef(String name, String key) {
    Map<String, Object> ref = new LinkedHashMap<>();
    ref.put("name", name);
    ref.put("key", key);
    return ref;
  }

  private static Map<String, Object> envVar(String name, String value) {
    Map<String, Object> env = new LinkedHashMap<>();
    env.put("name", name);
    env.put("value", value);
    return env;
  }

}
