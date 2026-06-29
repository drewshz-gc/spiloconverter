package de.guidecom.connect.spilo2cnpg;

import de.guidecom.connect.spilo2cnpg.ConversionOptions.MigrationStrategy;
import de.guidecom.connect.spilo2cnpg.model.cnpg.CnpgCluster;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql.SpiloPatroniConfig;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql.SpiloSpec;
import de.guidecom.connect.spilo2cnpg.support.CnpgCompatibility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpiloToCnpgConverterTest {

  private final SpiloToCnpgConverter converter = new SpiloToCnpgConverter();

  private static ConversionOptions initdb() {
    return ConversionOptions.builder().strategy(MigrationStrategy.INITDB).build();
  }

  private static ConversionOptions monolith() {
    return ConversionOptions.builder().build();
  }

  @Test
  void defaultStrategyIsMonolithImport() {
    assertEquals(MigrationStrategy.MONOLITH_IMPORT,
        ConversionOptions.builder().build().getStrategy());
  }

  @Test
  void targetImageIsPinnedPg18() {
    CnpgCluster cluster = converter.convert(SpiloTestFixtures.readyCr(), monolith());
    assertEquals(CnpgCompatibility.TARGET_IMAGE, cluster.getSpec().getImageName());
    assertTrue(cluster.getSpec().getImageName().contains("postgresql:18.3"));
  }

  @Test
  void imageIsTargetEvenWhenSourceVersionMissing() {
    SpiloSpec spec = SpiloTestFixtures.readySpec();
    spec.setPostgresqlConfig(null);

    CnpgCluster cluster = converter.convert(SpiloTestFixtures.crWith(spec), monolith());

    assertEquals(CnpgCompatibility.TARGET_IMAGE, cluster.getSpec().getImageName());
  }

  @Test
  void walRecoveryKeepsSourceMajorImage() {
    ConversionOptions options = ConversionOptions.builder()
        .strategy(MigrationStrategy.WAL_RECOVERY)
        .s3Bucket("my-bucket")
        .build();

    CnpgCluster cluster = converter.convert(SpiloTestFixtures.readyCr(), options);

    assertTrue(cluster.getSpec().getImageName().endsWith(":16"));
  }

  @Test
  void mapsInstances() {
    CnpgCluster cluster = converter.convert(SpiloTestFixtures.readyCr(), monolith());
    assertEquals(3, cluster.getSpec().getInstances());
  }

  @Test
  void curatedPostgresParameters() {
    CnpgCluster cluster = converter.convert(SpiloTestFixtures.readyCr(),
        ConversionOptions.builder().maxConnections(150).enableIndexScan(true).build());

    Map<String, String> params = cluster.getSpec().getPostgresqlConfig().getParameters();
    assertEquals("150", params.get("max_connections"));
    assertEquals("on", params.get("enable_indexscan"));
  }

  @Test
  void usesDefaultStorageWhenVolumeMissing() {
    SpiloSpec spec = SpiloTestFixtures.readySpec();
    spec.setVolume(null);

    CnpgCluster cluster = converter.convert(SpiloTestFixtures.crWith(spec), monolith());

    assertEquals("10Gi", cluster.getSpec().getStorage().getSize());
  }

  @Test
  void skipsSyncReplicasForSingleInstance() {
    SpiloSpec spec = SpiloTestFixtures.readySpec();
    spec.setNumberOfInstances(1);
    spec.setPatroniConfig(SpiloPatroniConfig.builder().synchronousMode(true).build());

    CnpgCluster cluster = converter.convert(SpiloTestFixtures.crWith(spec), monolith());

    assertNull(cluster.getSpec().getMinSyncReplicas());
    assertNull(cluster.getSpec().getMaxSyncReplicas());
  }

  @Test
  void monolithImportBootstrapFromLiveSpiloSource() {
    CnpgCluster cluster = converter.convert(SpiloTestFixtures.readyCr(), monolith());

    var initdb = cluster.getSpec().getBootstrap().getInitdb();
    assertNotNull(initdb);
    var imp = initdb.getImportConfig();
    assertNotNull(imp);
    assertEquals("monolith", imp.getType());
    assertEquals(List.of("*"), imp.getDatabases());
    assertEquals(List.of("*"), imp.getRoles());
    assertEquals("spilo-test-source", imp.getSource().getExternalCluster());
    assertTrue(imp.getPgDumpExtraOptions().contains("--exclude-schema=metric_helpers"));
    assertTrue(imp.getPgDumpExtraOptions().contains("--no-acl"));

    var external = cluster.getSpec().getExternalClusters();
    assertEquals(1, external.size());
    assertEquals("spilo-test-source", external.get(0).getName());
    assertEquals("test-cluster", external.get(0).getConnectionParameters().get("host"));
    assertEquals("postgres", external.get(0).getConnectionParameters().get("user"));
    assertEquals("postgres.test-cluster.credentials.postgresql.acid.zalan.do",
        external.get(0).getPassword().getName());
  }

  @Test
  void initdbBootstrapUsesFirstDatabase() {
    SpiloSpec spec = SpiloTestFixtures.readySpec();
    spec.setDatabasesMap(Map.of("mydb", "app"));

    CnpgCluster cluster = converter.convert(SpiloTestFixtures.crWith(spec), initdb());

    assertNotNull(cluster.getSpec().getBootstrap().getInitdb());
    assertEquals("mydb", cluster.getSpec().getBootstrap().getInitdb().getDatabase());
    assertEquals("mydb", cluster.getSpec().getBootstrap().getInitdb().getOwner());
    assertNull(cluster.getSpec().getBootstrap().getInitdb().getImportConfig());
    assertNull(cluster.getSpec().getBootstrap().getRecovery());
  }

  @Test
  void walRecoveryBootstrapBuildsExternalClusterBarmanStore() {
    ConversionOptions options = ConversionOptions.builder()
        .strategy(MigrationStrategy.WAL_RECOVERY)
        .s3Bucket("my-bucket")
        .build();

    CnpgCluster cluster = converter.convert(SpiloTestFixtures.readyCr(), options);

    assertNotNull(cluster.getSpec().getBootstrap().getRecovery());
    assertEquals("spilo-test", cluster.getSpec().getBootstrap().getRecovery().getSource());
    assertNotNull(cluster.getSpec().getExternalClusters());
    assertEquals(1, cluster.getSpec().getExternalClusters().size());
    assertEquals("spilo-test", cluster.getSpec().getExternalClusters().get(0).getName());
    assertNotNull(cluster.getSpec().getExternalClusters().get(0).getBarmanObjectStore());
    // spec.backup is no longer used; physical backups come from the Barman Cloud Plugin.
    assertNull(cluster.getSpec().getBackup());
  }

  @Test
  void mapsManagedRolesWithZalandoSecretAndNoSuperuserByDefault() {
    SpiloSpec spec = SpiloTestFixtures.readySpec();
    spec.setUsersMap(Map.of("app", List.of("superuser", "createdb")));

    CnpgCluster cluster = converter.convert(SpiloTestFixtures.crWith(spec), monolith());

    var roles = cluster.getSpec().getManaged().getRoles();
    assertEquals(1, roles.size());
    var role = roles.get(0);
    assertEquals("app", role.getName());
    assertTrue(role.isLogin());
    assertFalse(role.isSuperuser());
    assertTrue(role.isCreatedb());
    assertFalse(role.isCreaterole());
    assertEquals("app.test-cluster.credentials.postgresql.acid.zalan.do",
        role.getPasswordSecret().getName());
    assertNull(role.getPasswordSecret().getKey());
  }

  @Test
  void superuserRoleHonoredWhenFlagEnabled() {
    SpiloSpec spec = SpiloTestFixtures.readySpec();
    spec.setUsersMap(Map.of("app", List.of("superuser")));

    CnpgCluster cluster = converter.convert(SpiloTestFixtures.crWith(spec),
        ConversionOptions.builder().enableSuperuserAccess(true).build());

    assertTrue(cluster.getSpec().getManaged().getRoles().get(0).isSuperuser());
  }

  @Test
  void monitoringUsesCustomQueriesNotDeprecatedPodMonitor() {
    CnpgCluster cluster = converter.convert(SpiloTestFixtures.readyCr(), monolith());

    var monitoring = cluster.getSpec().getMonitoring();
    assertNull(monitoring.getEnablePodMonitor());
    assertNotNull(monitoring.getCustomQueriesConfigMap());
    assertEquals("test-cnpg-cluster-monitoring",
        monitoring.getCustomQueriesConfigMap().get(0).getName());
  }

  @Test
  void securityDefaultsAreDisabled() {
    CnpgCluster cluster = converter.convert(SpiloTestFixtures.readyCr(), monolith());
    assertFalse(cluster.getSpec().getEnableSuperuserAccess());
  }

  @Test
  void inheritedMetadataCarriesNetworkingLabels() {
    CnpgCluster cluster = converter.convert(SpiloTestFixtures.readyCr(), monolith());

    var labels = cluster.getSpec().getInheritedMetadata().getLabels();
    assertEquals("allowed", labels.get("networking.gc/to-apiserver"));
    assertEquals("allowed", labels.get("networking.gc/to-s3"));
    assertEquals("cnpg", labels.get("application"));
  }

  @Test
  void noArgocdAnnotationsByDefault() {
    CnpgCluster cluster = converter.convert(SpiloTestFixtures.readyCr(), monolith());
    assertNull(cluster.getMetadata().getAnnotations());
  }

  @Test
  void argocdAnnotationsAddedWhenEnabled() {
    ConversionOptions options = ConversionOptions.builder()
        .argocd(true)
        .syncWave(0)
        .build();

    CnpgCluster cluster = converter.convert(SpiloTestFixtures.readyCr(), options);

    var annotations = cluster.getMetadata().getAnnotations();
    assertNotNull(annotations);
    assertEquals("ServerSideApply=true,SkipDryRunOnMissingResource=true",
        annotations.get("argocd.argoproj.io/sync-options"));
    assertEquals("0", annotations.get("argocd.argoproj.io/sync-wave"));
  }

  @Test
  void clusterNameUsesCnpgSuffix() {
    CnpgCluster cluster = converter.convert(SpiloTestFixtures.readyCr(), monolith());
    assertEquals("test-cnpg-cluster", cluster.getMetadata().getName());
  }

  @Test
  void toCnpgClusterNameReplacesSpiloSuffix() {
    assertEquals("condor02-cnpg-cluster",
        SpiloToCnpgConverter.toCnpgClusterName("condor02-postgres-cluster"));
    assertEquals("tifa01-cnpg-cluster",
        SpiloToCnpgConverter.toCnpgClusterName("tifa01"));
  }

  @Test
  void appliesConventionLabelsAndInheritedMetadata() {
    CnpgCluster cluster = converter.convert(SpiloTestFixtures.readyCr(), monolith());

    var labels = cluster.getMetadata().getLabels();
    assertEquals("postgresql", labels.get("app.kubernetes.io/name"));
    assertEquals("test-cnpg-cluster", labels.get("app.kubernetes.io/instance"));
    assertEquals("spilo2cnpg", labels.get("app.kubernetes.io/managed-by"));
    assertEquals("team", labels.get("tenant"));
    assertEquals("test-cluster", labels.get("spilo2cnpg/source-cluster"));

    var inherited = cluster.getSpec().getInheritedMetadata();
    assertNotNull(inherited);
    assertEquals("test-cnpg-cluster", inherited.getLabels().get("app.kubernetes.io/instance"));
    assertEquals("team", inherited.getLabels().get("tenant"));
  }

  // --- Companion resources -------------------------------------------------

  @SuppressWarnings("unchecked")
  private static Map<String, Object> findKind(List<Object> manifests, String kind) {
    return manifests.stream()
        .filter(m -> m instanceof Map)
        .map(m -> (Map<String, Object>) m)
        .filter(m -> kind.equals(m.get("kind")))
        .findFirst()
        .orElse(null);
  }

  private ConversionOptions withBackup() {
    return ConversionOptions.builder()
        .s3Bucket("cnpg-test")
        .s3EndpointUrl("https://s3.example.local")
        .build();
  }

  @Test
  void convertAllEmitsClusterFirst() {
    List<Object> manifests = converter.convertAll(SpiloTestFixtures.readyCr(), withBackup());
    assertTrue(manifests.get(0) instanceof CnpgCluster);
  }

  @Test
  void backupPluginWiredWhenBucketPresent() {
    CnpgCluster cluster = converter.convert(SpiloTestFixtures.readyCr(), withBackup());

    var plugins = cluster.getSpec().getPlugins();
    assertNotNull(plugins);
    assertEquals("barman-cloud.cloudnative-pg.io", plugins.get(0).getName());
    assertTrue(plugins.get(0).getIsWALArchiver());
    assertEquals("test-cnpg-cluster-store", plugins.get(0).getParameters().get("barmanObjectName"));
  }

  @Test
  void noBackupPluginWithoutBucket() {
    CnpgCluster cluster = converter.convert(SpiloTestFixtures.readyCr(), monolith());
    assertNull(cluster.getSpec().getPlugins());
  }

  @Test
  @SuppressWarnings("unchecked")
  void objectStoreCompanionGenerated() {
    List<Object> manifests = converter.convertAll(SpiloTestFixtures.readyCr(), withBackup());
    Map<String, Object> objectStore = findKind(manifests, "ObjectStore");
    assertNotNull(objectStore);
    assertEquals("barmancloud.cnpg.io/v1", objectStore.get("apiVersion"));
    Map<String, Object> spec = (Map<String, Object>) objectStore.get("spec");
    Map<String, Object> config = (Map<String, Object>) spec.get("configuration");
    assertEquals("s3://cnpg-test/cnpg/test-cnpg-cluster", config.get("destinationPath"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void poolersGeneratedWithNetworkingLabels() {
    List<Object> manifests = converter.convertAll(SpiloTestFixtures.readyCr(), withBackup());
    long poolers = manifests.stream()
        .filter(m -> m instanceof Map)
        .map(m -> (Map<String, Object>) m)
        .filter(m -> "Pooler".equals(m.get("kind")))
        .count();
    assertEquals(2, poolers);

    Map<String, Object> pooler = findKind(manifests, "Pooler");
    Map<String, Object> spec = (Map<String, Object>) pooler.get("spec");
    Map<String, Object> template = (Map<String, Object>) spec.get("template");
    Map<String, Object> meta = (Map<String, Object>) template.get("metadata");
    Map<String, String> labels = (Map<String, String>) meta.get("labels");
    assertEquals("allowed", labels.get("networking.gc/to-apiserver"));
  }

  @Test
  void podMonitorsGenerated() {
    List<Object> manifests = converter.convertAll(SpiloTestFixtures.readyCr(), withBackup());
    long podMonitors = manifests.stream()
        .filter(m -> m instanceof Map)
        .map(m -> (java.util.Map<?, ?>) m)
        .filter(m -> "PodMonitor".equals(m.get("kind")))
        .count();
    assertEquals(3, podMonitors);
  }

  @Test
  void scheduledBackupUsesPluginMethod() {
    List<Object> manifests = converter.convertAll(SpiloTestFixtures.readyCr(), withBackup());
    Map<String, Object> sb = findKind(manifests, "ScheduledBackup");
    assertNotNull(sb);
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = (Map<String, Object>) sb.get("spec");
    assertEquals("plugin", spec.get("method"));
  }

  @Test
  void companionResourcesCanBeDisabled() {
    ConversionOptions options = ConversionOptions.builder()
        .s3Bucket("cnpg-test")
        .generatePooler(false)
        .generatePodMonitor(false)
        .generateScheduledBackup(false)
        .build();

    List<Object> manifests = converter.convertAll(SpiloTestFixtures.readyCr(), options);

    assertNull(findKind(manifests, "Pooler"));
    assertNull(findKind(manifests, "PodMonitor"));
    assertNull(findKind(manifests, "ScheduledBackup"));
    // ObjectStore still present because backup plugin is enabled.
    assertNotNull(findKind(manifests, "ObjectStore"));
  }
}
