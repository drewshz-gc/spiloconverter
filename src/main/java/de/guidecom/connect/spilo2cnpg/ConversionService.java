package de.guidecom.connect.spilo2cnpg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult;
import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult.MigrationReadiness;
import de.guidecom.connect.spilo2cnpg.model.cnpg.CnpgCluster;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Shared orchestration for the CLI commands: reading a Spilo CR, running the compatibility
 * analysis, and converting + serializing it into a CNPG manifest set.
 *
 * <p>Centralizing this flow keeps the {@code generate} and {@code convert-all} commands in
 * sync and avoids duplicated parse/analyze/convert logic.
 */
@Service
public class ConversionService {

  private final SpiloAnalyzer analyzer;
  private final SpiloToCnpgConverter converter;
  private final ObjectMapper inputMapper;
  private final ObjectMapper outputMapper;

  public ConversionService(SpiloAnalyzer analyzer,
                           SpiloToCnpgConverter converter,
                           @Qualifier("yamlMapper") ObjectMapper inputMapper,
                           @Qualifier("yamlOutputMapper") ObjectMapper outputMapper) {
    this.analyzer = analyzer;
    this.converter = converter;
    this.inputMapper = inputMapper;
    this.outputMapper = outputMapper;
  }

  /**
   * Reads and deserializes a Spilo CR from a YAML file.
   *
   * @throws IOException if the file cannot be read or parsed
   */
  public SpiloPostgresql read(File file) throws IOException {
    return inputMapper.readValue(file, SpiloPostgresql.class);
  }

  public AnalysisResult analyze(SpiloPostgresql spilo) {
    return analyzer.analyze(spilo);
  }

  public boolean isBlocked(AnalysisResult result) {
    return result.getReadiness() == MigrationReadiness.BLOCKED;
  }

  /**
   * Converts the Spilo CR to the core CNPG Cluster and serializes it to a single YAML document.
   */
  public String convertToYaml(SpiloPostgresql spilo, ConversionOptions options) throws JsonProcessingException {
    CnpgCluster cluster = converter.convert(spilo, options);
    return outputMapper.writeValueAsString(cluster);
  }

  /**
   * Converts the Spilo CR to the full manifest set (Cluster + companion resources) and serializes
   * it as a multi-document YAML string ({@code ---}-separated).
   */
  public String convertAllToYaml(SpiloPostgresql spilo, ConversionOptions options) throws JsonProcessingException {
    List<Object> manifests = converter.convertAll(spilo, options);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < manifests.size(); i++) {
      if (i > 0) {
        sb.append("---\n");
      }
      sb.append(outputMapper.writeValueAsString(manifests.get(i)));
    }
    return sb.toString();
  }
}
