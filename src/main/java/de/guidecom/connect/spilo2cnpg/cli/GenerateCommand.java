package de.guidecom.connect.spilo2cnpg.cli;

import de.guidecom.connect.spilo2cnpg.ConversionOptions;
import de.guidecom.connect.spilo2cnpg.ConversionOptions.MigrationStrategy;
import de.guidecom.connect.spilo2cnpg.ConversionService;
import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.concurrent.Callable;

/**
 * CLI command: {@code spilo2cnpg generate <file> [options]}
 *
 * <p>Converts a Spilo {@code postgresql} CR into a single CNPG {@code Cluster} YAML document.
 * Companion resources (ObjectStore, Pooler, PodMonitor, ScheduledBackup) are not emitted.
 * Runs the analyzer first and aborts if blockers are found (unless {@code --force} is set).
 */
@Command(
    name = "generate",
    mixinStandardHelpOptions = true,
    description = "Convert a Spilo postgresql CR to a CNPG Cluster YAML"
)
public class GenerateCommand implements Callable<Integer> {

  private final ConversionService conversionService;
  @Parameters(index = "0", description = "Path to the Spilo YAML input file")
  private File inputFile;
  @Option(names = {"--output", "-o"}, description = "Output file path (default: stdout)")
  private File outputFile;
  @Option(
      names = {"--strategy", "-s"},
      description = "Bootstrap strategy: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})"
  )
  private MigrationStrategy strategy = MigrationStrategy.MONOLITH_IMPORT;
  @Option(names = {"--target-image"}, description = "Pinned target image (default: PG18 standard)")
  private String targetImage;
  @Option(names = {"--max-connections"}, description = "max_connections for the target cluster (default: 150)")
  private Integer maxConnections;
  @Option(names = {"--target-namespace"}, description = "Target namespace for the manifests (default: source namespace)")
  private String targetNamespace;
  @Option(names = {"--s3-bucket"}, description = "S3 bucket for the Barman Cloud ObjectStore (enables physical backups)")
  private String s3Bucket;
  @Option(names = {"--s3-endpoint-url"}, description = "S3 endpoint URL for on-prem / S3-compatible storage")
  private String s3EndpointUrl;
  @Option(names = {"--s3-destination-path"}, description = "Full destination path override (default: s3://<bucket>/cnpg/<cluster>)")
  private String s3DestinationPath;
  @Option(names = {"--backup-credentials-secret"}, description = "Secret with S3 credentials (default: <cluster>-backup)")
  private String backupCredentialsSecret;
  @Option(names = {"--force"}, description = "Generate manifests even when analysis blockers are present")
  private boolean force;
  @Option(
      names = {"--enable-superuser-access"},
      description = "Enable the CNPG superuser and superuser managed roles (default: false)"
  )
  private boolean enableSuperuserAccess;
  @Option(names = {"--pooler"}, negatable = true, description = "Emit Pooler (PgBouncer) CRs (default: true)")
  private boolean pooler = true;
  @Option(names = {"--pod-monitor"}, negatable = true, description = "Emit standalone PodMonitor CRs (default: true)")
  private boolean podMonitor = true;
  @Option(names = {"--scheduled-backup"}, negatable = true, description = "Emit a ScheduledBackup (default: true)")
  private boolean scheduledBackup = true;
  @Option(names = {"--backup-plugin"}, negatable = true, description = "Emit Barman Cloud plugin + ObjectStore (default: true)")
  private boolean backupPlugin = true;
  @Option(names = {"--networking-labels"}, negatable = true, description = "Add networking.gc/* + application=cnpg labels (default: true)")
  private boolean networkingLabels = true;
  @Option(
      names = {"--argocd"},
      description = "Add ArgoCD sync annotations / sync-waves for GitOps"
  )
  private boolean argocd;
  @Option(
      names = {"--sync-wave"},
      description = "ArgoCD sync-wave for the generated Cluster (implies --argocd)"
  )
  private Integer syncWave;

  public GenerateCommand(ConversionService conversionService) {
    this.conversionService = conversionService;
  }

  @Override
  public Integer call() throws IOException {
    SpiloPostgresql spilo;
    try {
      spilo = conversionService.read(inputFile);
    } catch (IOException e) {
      System.err.println("ERROR: could not read Spilo YAML '" + inputFile + "': " + e.getMessage());
      return 1;
    }

    AnalysisResult analysis = conversionService.analyze(spilo);
    if (conversionService.isBlocked(analysis) && !force) {
      System.err.println("ERROR: Analysis found blocker(s). Run 'analyze' for details or use --force to override.");
      analysis.getFindings().stream()
          .filter(f -> f.getSeverity() == AnalysisResult.Severity.BLOCKER)
          .forEach(f -> System.err.printf("  [BLOCKER] %s - %s%n", f.getCategory(), f.getTitle()));
      return 1;
    }

    if (strategy == MigrationStrategy.WAL_RECOVERY && s3Bucket == null) {
      System.err.println("ERROR: --s3-bucket is required for WAL_RECOVERY strategy.");
      return 1;
    }

    ConversionOptions.ConversionOptionsBuilder optionsBuilder = ConversionOptions.builder()
        .strategy(strategy)
        .targetNamespace(targetNamespace)
        .s3Bucket(s3Bucket)
        .s3EndpointUrl(s3EndpointUrl)
        .s3DestinationPath(s3DestinationPath)
        .backupCredentialsSecret(backupCredentialsSecret)
        .enableSuperuserAccess(enableSuperuserAccess)
        .generatePooler(pooler)
        .generatePodMonitor(podMonitor)
        .generateScheduledBackup(scheduledBackup)
        .generateBackupPlugin(backupPlugin)
        .addNetworkingLabels(networkingLabels)
        .argocd(argocd || syncWave != null)
        .syncWave(syncWave);
    if (targetImage != null) {
      optionsBuilder.targetImage(targetImage);
    }
    if (maxConnections != null) {
      optionsBuilder.maxConnections(maxConnections);
    }
    ConversionOptions options = optionsBuilder.build();

    String yaml = conversionService.convertToYaml(spilo, options);

    if (outputFile != null) {
      Files.writeString(outputFile.toPath(), yaml);
      System.err.printf("Written to: %s%n", outputFile.getAbsolutePath());
    } else {
      try (PrintWriter pw = new PrintWriter(System.out)) {
        pw.print(yaml);
        pw.flush();
      }
    }

    return 0;
  }

}
