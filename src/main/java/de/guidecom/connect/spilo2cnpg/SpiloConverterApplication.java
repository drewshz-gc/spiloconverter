package de.guidecom.connect.spilo2cnpg;

import de.guidecom.connect.spilo2cnpg.cli.AnalyzeCommand;
import de.guidecom.connect.spilo2cnpg.cli.CompareCommand;
import de.guidecom.connect.spilo2cnpg.cli.ConvertAllCommand;
import de.guidecom.connect.spilo2cnpg.cli.GenerateCommand;
import de.guidecom.connect.spilo2cnpg.cli.SpiloCnpgCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import picocli.CommandLine;

/**
 * Spring Boot entry point for the {@code spilo2cnpg} CLI tool.
 *
 * <p>Spring Boot wires the {@link ConversionService} (which in turn aggregates the analyzer,
 * converter, and YAML mappers) via dependency injection; Picocli handles CLI argument parsing.
 *
 * <p>The {@code NativeHints} registrar ensures all Jackson-serialized model classes
 * are accessible in a GraalVM native image.
 */
@SpringBootApplication
public class SpiloConverterApplication implements CommandLineRunner, ExitCodeGenerator {

  @Autowired
  private ConversionService conversionService;

  private int exitCode;

  public static void main(String[] args) {
    System.exit(SpringApplication.exit(
        new SpringApplicationBuilder(SpiloConverterApplication.class)
            .logStartupInfo(false)
            .run(args)));
  }

  @Override
  public void run(String... args) {
    exitCode = new CommandLine(new SpiloCnpgCommand())
        .addSubcommand("analyze", new AnalyzeCommand(conversionService))
        .addSubcommand("generate", new GenerateCommand(conversionService))
        .addSubcommand("convert-all", new ConvertAllCommand(conversionService))
        .addSubcommand("compare", new CompareCommand())
        .execute(args);
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }

}
