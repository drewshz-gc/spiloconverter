package de.guidecom.connect.spilo2cnpg;

import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql.SpiloEnvVar;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql.SpiloMetadata;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql.SpiloPostgresqlConfig;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql.SpiloResources;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql.SpiloResources.SpiloResourceRequirements;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql.SpiloSpec;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql.SpiloVolume;

import java.util.List;

/**
 * Builders for Spilo test CRs shared across analyzer and converter tests.
 */
final class SpiloTestFixtures {

  private SpiloTestFixtures() {
  }

  static SpiloMetadata metadata() {
    return SpiloMetadata.builder().name("test-cluster").namespace("db").build();
  }

  static SpiloVolume volume() {
    return SpiloVolume.builder().size("10Gi").storageClass("gp2").build();
  }

  static SpiloResources resources() {
    return SpiloResources.builder()
        .requests(SpiloResourceRequirements.builder().cpu("500m").memory("1Gi").build())
        .limits(SpiloResourceRequirements.builder().cpu("2").memory("2Gi").build())
        .build();
  }

  static SpiloEnvVar walgEnv() {
    return SpiloEnvVar.builder().name("WALG_S3_PREFIX").value("s3://backups/test").build();
  }

  /**
   * A spec that should analyze as READY (no warnings, no blockers).
   */
  static SpiloSpec readySpec() {
    return SpiloSpec.builder()
        .teamId("team")
        .numberOfInstances(3)
        .postgresqlConfig(SpiloPostgresqlConfig.builder().version("16").build())
        .volume(volume())
        .resources(resources())
        .env(List.of(walgEnv()))
        .build();
  }

  static SpiloPostgresql readyCr() {
    return SpiloPostgresql.builder()
        .apiVersion("acid.zalan.do/v1")
        .kind("postgresql")
        .metadata(metadata())
        .spec(readySpec())
        .build();
  }

  static SpiloPostgresql crWith(SpiloSpec spec) {
    return SpiloPostgresql.builder()
        .apiVersion("acid.zalan.do/v1")
        .kind("postgresql")
        .metadata(metadata())
        .spec(spec)
        .build();
  }
}
