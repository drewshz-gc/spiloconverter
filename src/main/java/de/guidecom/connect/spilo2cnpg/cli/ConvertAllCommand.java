package de.guidecom.connect.spilo2cnpg.cli;

import de.guidecom.connect.spilo2cnpg.ConversionOptions;
import de.guidecom.connect.spilo2cnpg.ConversionOptions.MigrationStrategy;
import de.guidecom.connect.spilo2cnpg.ConversionService;
import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult;
import de.guidecom.connect.spilo2cnpg.analyzer.AnalysisResult.MigrationReadiness;
import de.guidecom.connect.spilo2cnpg.model.spilo.SpiloPostgresql;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * CLI command: {@code spilo2cnpg convert-all <dir> [options]}
 *
 * <p>Recursively scans a directory tree for Spilo postgres CR files (matched by a glob
 * pattern, default {@code *postgres*}), analyzes and converts each one, and writes
 * the resulting CNPG manifest set next to the source file (or into {@code --output-dir}).
 */
@Command(
    name = "convert-all",
    mixinStandardHelpOptions = true,
    description = "Batch-convert all Spilo postgres CRs found under a directory tree"
)
public class ConvertAllCommand implements Callable<Integer> {

  private final ConversionService conversionService;
  @Parameters(
      index = "0",
      description = "Root directory to scan recursively",
      defaultValue = "."
  )
  private Path rootDir;
  @Option(
      names = {"--pattern", "-p"},
      description = "Filename glob pattern for input files (default: ${DEFAULT-VALUE})"
  )
  private String pattern = "*postgres*";
  @Option(
      names = {"--output-dir", "-o"},
      description = "Output root directory (default: write next to each input file). "
          + "Relative path from root is preserved."
  )
  private Path outputDir;
  @Option(
      names = {"--strategy", "-s"},
      description = "Bootstrap strategy: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})"
  )
  private MigrationStrategy strategy = MigrationStrategy.MONOLITH_IMPORT;
  @Option(names = {"--target-image"}, description = "Pinned target image (default: PG18 standard)")
  private String targetImage;
  @Option(names = {"--max-connections"}, description = "max_connections for the target clusters (default: 150)")
  private Integer maxConnections;
  @Option(names = {"--s3-bucket"}, description = "S3 bucket for the Barman Cloud ObjectStore (enables physical backups)")
  private String s3Bucket;
  @Option(names = {"--s3-endpoint-url"}, description = "S3 endpoint URL for on-prem / S3-compatible storage")
  private String s3EndpointUrl;
  @Option(names = {"--s3-destination-path"}, description = "Full destination path override (default: s3://<bucket>/cnpg/<cluster>)")
  private String s3DestinationPath;
  @Option(names = {"--backup-credentials-secret"}, description = "Secret with S3 credentials (default: <cluster>-backup)")
  private String backupCredentialsSecret;
  @Option(names = {"--target-namespace"}, description = "Override target namespace for all clusters")
  private String targetNamespace;
  @Option(names = {"--force"}, description = "Write output even when analysis finds blockers")
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
  @Option(names = {"--dry-run"}, description = "Show what would be converted without writing any files")
  private boolean dryRun;

  public ConvertAllCommand(ConversionService conversionService) {
    this.conversionService = conversionService;
  }

  // -------------------------------------------------------------------------

  @Override
  public Integer call() throws Exception {
    Path root = rootDir.toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      System.err.println("ERROR: not a directory: " + root);
      return 1;
    }

    List<Path> inputFiles = findInputFiles(root);
    if (inputFiles.isEmpty()) {
      System.out.println("No files matching '" + pattern + "' found under " + root);
      return 0;
    }

    System.out.printf("Found %d file(s) matching '%s'%n%n", inputFiles.size(), pattern);

    List<ConversionResult> results = new ArrayList<>();
    for (Path inputFile : inputFiles) {
      results.add(processFile(inputFile, root));
    }

    printSummary(results);
    return results.stream().anyMatch(r -> r.status() == Status.ERROR) ? 1 : 0;
  }

  // -------------------------------------------------------------------------
  // File discovery
  // -------------------------------------------------------------------------

  private List<Path> findInputFiles(Path root) throws IOException {
    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    List<Path> found = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(root)) {
      walk.filter(Files::isRegularFile)
          .filter(p -> matcher.matches(p.getFileName()))
          .sorted()
          .forEach(found::add);
    }
    return found;
  }

  // -------------------------------------------------------------------------
  // Per-file processing
  // -------------------------------------------------------------------------

  private ConversionResult processFile(Path inputFile, Path root) {
    String relPath = root.relativize(inputFile).toString();
    System.out.printf("  %-70s ", relPath);

    SpiloPostgresql spilo;
    try {
      spilo = conversionService.read(inputFile.toFile());
    } catch (IOException e) {
      System.out.println("[PARSE ERROR]");
      return new ConversionResult(inputFile, null, null, Status.ERROR, "Parse error: " + e.getMessage());
    }

    // Skip non-postgresql resources (e.g. kustomization.yaml named with postgres)
    if (!"postgresql".equalsIgnoreCase(spilo.getKind())) {
      System.out.println("[SKIPPED - kind: " + spilo.getKind() + "]");
      return new ConversionResult(inputFile, null, null, Status.SKIPPED, null);
    }

    AnalysisResult analysis = conversionService.analyze(spilo);

    if (conversionService.isBlocked(analysis) && !force) {
      long blockerCount = analysis.getFindings().stream()
          .filter(f -> f.getSeverity() == AnalysisResult.Severity.BLOCKER).count();
      System.out.printf("[BLOCKED - %d blocker(s)]%n", blockerCount);
      return new ConversionResult(inputFile, null, analysis, Status.BLOCKED, null);
    }

    if (strategy == MigrationStrategy.WAL_RECOVERY && s3Bucket == null) {
      System.out.println("[CONVERT ERROR]");
      return new ConversionResult(inputFile, null, analysis, Status.ERROR,
          "--s3-bucket is required for WAL_RECOVERY strategy");
    }

    ConversionOptions.ConversionOptionsBuilder optionsBuilder = ConversionOptions.builder()
        .strategy(strategy)
        .s3Bucket(s3Bucket)
        .s3EndpointUrl(s3EndpointUrl)
        .s3DestinationPath(s3DestinationPath)
        .backupCredentialsSecret(backupCredentialsSecret)
        .targetNamespace(targetNamespace)
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

    String yaml;
    try {
      yaml = conversionService.convertAllToYaml(spilo, options);
    } catch (Exception e) {
      System.out.println("[CONVERT ERROR]");
      return new ConversionResult(inputFile, null, analysis, Status.ERROR, "Convert error: " + e.getMessage());
    }

    Path outputFile = resolveOutputFile(inputFile, root);
    Status status = analysis.getReadiness() == MigrationReadiness.READY_WITH_WARNINGS
        ? Status.WARNINGS : Status.OK;

    if (dryRun) {
      System.out.printf("[DRY-RUN -> %s]%n", root.relativize(outputFile));
      return new ConversionResult(inputFile, outputFile, analysis, status, null);
    }

    try {
      Files.createDirectories(outputFile.getParent());
      Files.writeString(outputFile, yaml);
      System.out.printf("[%s -> %s]%n", status, root.relativize(outputFile));
    } catch (IOException e) {
      System.out.println("[WRITE ERROR]");
      return new ConversionResult(inputFile, outputFile, analysis, Status.ERROR, "Write error: " + e.getMessage());
    }

    return new ConversionResult(inputFile, outputFile, analysis, status, null);
  }

  // -------------------------------------------------------------------------
  // Summary
  // -------------------------------------------------------------------------

  private void printSummary(List<ConversionResult> results) {
    long ok = results.stream().filter(r -> r.status() == Status.OK).count();
    long warnings = results.stream().filter(r -> r.status() == Status.WARNINGS).count();
    long blocked = results.stream().filter(r -> r.status() == Status.BLOCKED).count();
    long errors = results.stream().filter(r -> r.status() == Status.ERROR).count();
    long skipped = results.stream().filter(r -> r.status() == Status.SKIPPED).count();

    System.out.println();
    System.out.println("=".repeat(72));
    System.out.printf("  Summary: %d files | %d ok | %d warnings | %d blocked | %d errors | %d skipped%n",
        results.size(), ok, warnings, blocked, errors, skipped);
    System.out.println("=".repeat(72));

    if (errors > 0 || blocked > 0) {
      System.out.println("  Issues:");
      results.stream()
          .filter(r -> r.status() == Status.ERROR || r.status() == Status.BLOCKED)
          .forEach(r -> {
            System.out.printf("    [%s] %s%n", r.status(), r.inputFile().getFileName());
            if (r.errorMessage() != null) {
              System.out.printf("          %s%n", r.errorMessage());
            }
            if (r.analysis() != null && r.status() == Status.BLOCKED) {
              r.analysis().getFindings().stream()
                  .filter(f -> f.getSeverity() == AnalysisResult.Severity.BLOCKER)
                  .forEach(f -> System.out.printf("          [BLOCKER] %s: %s%n", f.getCategory(), f.getTitle()));
            }
          });
    }
  }

  // -------------------------------------------------------------------------
  // Output path resolution
  // -------------------------------------------------------------------------

  /**
   * Derives the output path for a given input file.
   *
   * <p>Output filename: replace last {@code .yml}/{@code .yaml} extension with {@code -cnpg.yaml}.
   * Output directory: same as input (default) or {@code outputDir} with the input's relative path.
   */
  private Path resolveOutputFile(Path inputFile, Path root) {
    String inputName = inputFile.getFileName().toString();
    String baseName = inputName.replaceAll("\\.(yml|yaml)$", "");
    String outputName = baseName + "-cnpg.yaml";

    if (outputDir != null) {
      Path rel = root.relativize(inputFile.getParent());
      return outputDir.toAbsolutePath().resolve(rel).resolve(outputName).normalize();
    }
    return inputFile.getParent().resolve(outputName);
  }

  // -------------------------------------------------------------------------

  private enum Status {OK, WARNINGS, BLOCKED, ERROR, SKIPPED}

  private record ConversionResult(
      Path inputFile,
      Path outputFile,
      AnalysisResult analysis,
      Status status,
      String errorMessage
  ) {
  }

}
