package de.guidecom.connect.spilo2cnpg.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

  /**
   * Mapper used to read (potentially partial / unknown-field) Spilo CR YAML input.
   */
  @Bean("yamlMapper")
  public ObjectMapper yamlMapper() {
    return new YAMLMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false);
  }

  /**
   * Mapper used to serialize generated CNPG manifests.
   *
   * <p>The leading {@code ---} document-start marker is omitted so the output can be
   * embedded or concatenated cleanly. A single, shared, pre-configured instance avoids
   * creating an ad-hoc {@link YAMLMapper} per invocation.
   */
  @Bean("yamlOutputMapper")
  public ObjectMapper yamlOutputMapper() {
    YAMLFactory factory = new YAMLFactory()
        .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER);
    return new YAMLMapper(factory);
  }

}
