package de.guidecom.connect.spilo2cnpg.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;

/**
 * Root Picocli command for the {@code spilo2cnpg} CLI.
 * Delegates to {@code analyze}, {@code generate}, {@code convert-all} and {@code compare} subcommands.
 */
@Command(
    name = "spilo2cnpg",
    mixinStandardHelpOptions = true,
    version = "1.0.0",
    description = "Migrate Zalando Spilo / Postgres Operator clusters to CloudNativePG",
    subcommands = {HelpCommand.class}
)
public class SpiloCnpgCommand implements Runnable {

  @Override
  public void run() {
    System.out.println("Use a subcommand: analyze | generate | convert-all | compare  (--help for details)");
  }

}
