package de.guidecom.connect.spilo2cnpg.support;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Central definition of CloudNativePG compatibility facts shared by the analyzer and the converter.
 *
 * <p>Keeping the supported PostgreSQL versions and the target image in a single place avoids drift
 * between the compatibility analysis ({@code SpiloAnalyzer}) and the actual image-name resolution
 * ({@code SpiloToCnpgConverter}).
 */
public final class CnpgCompatibility {

  /**
   * PostgreSQL major versions that this tool targets/validates for CNPG.
   *
   * <p>Spilo clusters in scope run PG 14-15; the migration performs a logical import directly
   * onto the current target major (see {@link #TARGET_POSTGRES_VERSION}). The full set is kept so
   * the analyzer does not flag still-supported source versions as blockers.
   */
  public static final Set<String> SUPPORTED_POSTGRES_VERSIONS =
      Set.of("14", "15", "16", "17", "18");

  /**
   * Target PostgreSQL major version for migrated clusters. The pilot (CCPSQL-128) standardized on
   * PostgreSQL 18; the logical {@code initdb.import} bootstrap upgrades older majors on the fly.
   */
  public static final String TARGET_POSTGRES_VERSION = "18";

  /**
   * Pinned, reproducible target image for migrated clusters.
   *
   * <p>Tag scheme {@code MM.mm-TYPE-OS}. {@code standard} ships the JIT-enabled build (the
   * {@code minimal} flavor has no JIT from PG18 on). Pin to a digest or append a build timestamp
   * for fully reproducible rollouts.
   */
  public static final String TARGET_IMAGE =
      "ghcr.io/cloudnative-pg/postgresql:18.3-standard-trixie";

  /**
   * Base image repository, used when an explicit per-version image has to be derived
   * (e.g. for in-place {@code WAL_RECOVERY}, where the major version must not change).
   */
  public static final String IMAGE_BASE = "ghcr.io/cloudnative-pg/postgresql";

  private CnpgCompatibility() {
  }

  /**
   * @return {@code true} if the given PostgreSQL major version is supported by CNPG
   */
  public static boolean isSupportedPostgresVersion(String version) {
    return version != null && SUPPORTED_POSTGRES_VERSIONS.contains(version);
  }

  /**
   * @return the supported versions as a deterministic, comma-separated display string
   */
  public static String supportedVersionsDisplay() {
    return SUPPORTED_POSTGRES_VERSIONS.stream().sorted().collect(Collectors.joining(", "));
  }
}
