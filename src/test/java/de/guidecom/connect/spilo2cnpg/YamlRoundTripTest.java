package de.guidecom.connect.spilo2cnpg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import de.guidecom.connect.spilo2cnpg.config.JacksonConfig;
import de.guidecom.connect.spilo2cnpg.model.cnpg.CnpgCluster;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Loads every Spilo fixture under {@code data/}, runs the full pipeline
 * (read -> analyze -> convert -> serialize) and verifies the output is valid CNPG YAML.
 */
class YamlRoundTripTest {

  private final JacksonConfig jacksonConfig = new JacksonConfig();
  private final ObjectMapper inputMapper = jacksonConfig.yamlMapper();
  private final ObjectMapper outputMapper = jacksonConfig.yamlOutputMapper();
  private final ObjectMapper plainYaml = new YAMLMapper();

  private final SpiloAnalyzer analyzer = new SpiloAnalyzer();
  private final SpiloToCnpgConverter converter = new SpiloToCnpgConverter();

  private List<Path> fixtures() throws IOException {
    Path dataDir = Path.of("data");
    if (!Files.isDirectory(dataDir)) {
      return List.of();
    }
    List<Path> files = new ArrayList<>();
    try (Stream<Path> walk = Files.list(dataDir)) {
      walk.filter(Files::isRegularFile)
          .filter(p -> {
            String n = p.getFileName().toString();
            return n.endsWith(".yml") || n.endsWith(".yaml");
          })
          .sorted()
          .forEach(files::add);
    }
    return files;
  }

  @TestFactory
  Stream<DynamicTest> roundTripAllFixtures() throws IOException {
    List<Path> fixtures = fixtures();
    // Guard so the suite fails loudly if the sample data ever disappears.
    org.junit.jupiter.api.Assertions.assertFalse(fixtures.isEmpty(), "no fixtures found under data/");

    return fixtures.stream().map(file -> DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
      SpiloPostgresql spilo = inputMapper.readValue(file.toFile(), SpiloPostgresql.class);

      // Analysis must never throw for valid Spilo input.
      assertNotNull(analyzer.analyze(spilo).getReadiness());

      CnpgCluster cluster = converter.convert(spilo,
          ConversionOptions.builder().build());
      String yaml = outputMapper.writeValueAsString(cluster);

      assertFalse(yaml.isBlank(), "serialized YAML is blank");
      assertFalse(yaml.startsWith("---"), "doc-start marker should be disabled");

      @SuppressWarnings("unchecked")
      Map<String, Object> reparsed = plainYaml.readValue(yaml, Map.class);
      assertEquals("postgresql.cnpg.io/v1", reparsed.get("apiVersion"));
      assertEquals("Cluster", reparsed.get("kind"));
    }));
  }
}
