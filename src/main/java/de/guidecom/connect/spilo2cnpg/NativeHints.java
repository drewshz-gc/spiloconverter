package de.guidecom.connect.spilo2cnpg;

import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult;
import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult.AnalysisFinding;
import de.guidecom.connect.spilo2cnpg.model.cnpg.CnpgCluster;
import de.guidecom.connect.spilo2cnpg.model.cnpg.CnpgCluster.*;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql.*;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Spring AOT runtime hints for GraalVM native image.
 *
 * <p>Registers all Jackson-serialized model classes for reflection so they remain
 * accessible after dead-code elimination during native compilation.
 * Picocli command classes are registered separately via the {@code picocli-codegen}
 * annotation processor (generates {@code META-INF/native-image/.../reflect-config.json}).
 */
public class NativeHints implements RuntimeHintsRegistrar {

  private static final MemberCategory[] ALL = MemberCategory.values();

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Spilo input model
    registerAll(hints,
        SpiloPostgresql.class,
        SpiloMetadata.class,
        SpiloSpec.class,
        SpiloPostgresqlConfig.class,
        SpiloPatroniConfig.class,
        SpiloVolume.class,
        SpiloResources.class,
        SpiloResources.SpiloResourceRequirements.class,
        SpiloEnvVar.class,
        SpiloEnvVar.SpiloEnvVarSource.class,
        SpiloEnvVar.SpiloEnvVarSource.SecretKeySelector.class,
        SpiloEnvVar.SpiloEnvVarSource.ConfigMapKeySelector.class,
        SpiloTls.class,
        SpiloUser.class
    );

    // CNPG output model
    registerAll(hints,
        CnpgCluster.class,
        CnpgMetadata.class,
        ClusterSpec.class,
        PostgresqlConfig.class,
        StorageConfig.class,
        ResourceConfig.class,
        ResourceConfig.ResourceRequests.class,
        BootstrapConfig.class,
        InitdbConfig.class,
        RecoveryConfig.class,
        BackupConfig.class,
        BarmanObjectStoreConfig.class,
        S3CredentialsConfig.class,
        WalConfig.class,
        DataConfig.class,
        ExternalCluster.class,
        CertificatesConfig.class,
        MonitoringConfig.class,
        ManagedConfig.class,
        RoleConfig.class,
        SecretKeyRef.class,
        SecretNameRef.class
    );

    // Analysis result
    registerAll(hints,
        AnalysisResult.class,
        AnalysisFinding.class,
        ConversionOptions.class
    );
  }

  private void registerAll(RuntimeHints hints, Class<?>... types) {
    for (Class<?> type : types) {
      hints.reflection().registerType(type, ALL);
    }
  }

}
