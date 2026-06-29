package de.guidecom.connect.spilo2cnpg.model.spilo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents a Zalando Postgres Operator {@code postgresql} Custom Resource.
 *
 * @see <a href="https://github.com/zalando/postgres-operator/blob/master/docs/reference/cluster_manifest.md">Zalando CRD Reference</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpiloPostgresql {

  private String apiVersion;
  private String kind;
  private SpiloMetadata metadata;
  private SpiloSpec spec;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class SpiloMetadata {
    private String name;
    private String namespace;
    private Map<String, String> labels;
    private Map<String, String> annotations;

  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class SpiloSpec {

    private String teamId;
    private int numberOfInstances;

    @JsonProperty("postgresql")
    private SpiloPostgresqlConfig postgresqlConfig;

    @JsonProperty("patroni")
    private SpiloPatroniConfig patroniConfig;

    private SpiloVolume volume;
    private SpiloResources resources;
    private List<SpiloEnvVar> env;
    private List<Map<String, Object>> sidecars;
    private List<Map<String, Object>> tolerations;

    @JsonProperty("enableConnectionPooler")
    private Boolean enableConnectionPooler;

    @JsonProperty("enableLogicalBackup")
    private Boolean enableLogicalBackup;

    @JsonProperty("logicalBackupSchedule")
    private String logicalBackupSchedule;

    private SpiloTls tls;

    /**
     * Stored as YAML map {@code {dbname: owner}}.
     * Exposed as list of db names via {@link #getDatabases()}.
     */
    @JsonProperty("databases")
    private Map<String, String> databasesMap;

    /**
     * Stored as YAML map {@code {username: [option, ...]}}.
     * Exposed as typed list via {@link #getUsers()}.
     */
    /**
     * Prepared databases with schema definitions (Zalando-specific).
     * No direct CNPG equivalent; detected by the analyzer.
     */
    @JsonProperty("preparedDatabases")
    private Map<String, Object> preparedDatabases;

    @JsonProperty("users")
    private Map<String, List<String>> usersMap;

    @JsonIgnore
    public List<String> getDatabases() {
      if (databasesMap == null) return List.of();
      return new ArrayList<>(databasesMap.keySet());
    }

    @JsonIgnore
    public List<SpiloUser> getUsers() {
      if (usersMap == null) return List.of();
      return usersMap.entrySet().stream()
          .map(e -> SpiloUser.builder()
              .name(e.getKey())
              .options(e.getValue() != null ? e.getValue() : List.of())
              .build())
          .toList();
    }

  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class SpiloPostgresqlConfig {
    private String version;
    private Map<String, String> parameters;

    @JsonProperty("pg_hba")
    private List<String> pgHba;

  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class SpiloPatroniConfig {

    @JsonProperty("synchronous_mode")
    private Boolean synchronousMode;

    @JsonProperty("synchronous_mode_strict")
    private Boolean synchronousModeStrict;

    @JsonProperty("failsafe_mode")
    private Boolean failsafeMode;

    @JsonProperty("pg_hba")
    private List<String> pgHba;

    /**
     * Map of slot name → slot config (e.g. {@code {type: physical}}).
     */
    private Map<String, Map<String, String>> slots;

    /**
     * Custom initdb parameters passed to {@code pg_ctl initdb}.
     */
    private Map<String, String> initdb;

  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class SpiloVolume {
    private String size;
    private String storageClass;

  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class SpiloResources {
    private SpiloResourceRequirements requests;
    private SpiloResourceRequirements limits;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpiloResourceRequirements {
      private String cpu;
      private String memory;

    }

  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class SpiloEnvVar {
    private String name;
    private String value;
    private SpiloEnvVarSource valueFrom;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpiloEnvVarSource {
      private SecretKeySelector secretKeyRef;
      private ConfigMapKeySelector configMapKeyRef;

      @Data
      @Builder
      @NoArgsConstructor
      @AllArgsConstructor
      @JsonIgnoreProperties(ignoreUnknown = true)
      public static class SecretKeySelector {
        private String name;
        private String key;

      }

      @Data
      @Builder
      @NoArgsConstructor
      @AllArgsConstructor
      @JsonIgnoreProperties(ignoreUnknown = true)
      public static class ConfigMapKeySelector {
        private String name;
        private String key;

      }

    }

  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class SpiloTls {
    private String secretName;
    private String caSecretName;
    private String caFile;

  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class SpiloUser {
    private String name;
    private List<String> options;

  }

}
