package gov.nasa.jpl.aerie.stateless;

import gov.nasa.jpl.aerie.stateless.utils.BlockExitSecurityManager;
import gov.nasa.jpl.aerie.stateless.utils.SystemExit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CLIArgumentsTest {
  private ByteArrayOutputStream out;
  private ByteArrayOutputStream err;
  private PrintStream outputStream;
  private PrintStream errorStream;

  @BeforeEach
  void beforeEach() {
    // Redirect System streams to buffers that can be examined
    out = new ByteArrayOutputStream();
    err = new ByteArrayOutputStream();
    outputStream = new PrintStream(out);
    errorStream = new PrintStream(err);

    System.setOut(outputStream);
    System.setErr(errorStream);
  }

  @AfterEach
  void afterEach() {
    outputStream.close();
    errorStream.close();
  }

  /**
   * Top level help text only displays if:
   *  - the first argument is '-h' or '--help'
   *  - no arguments are passed
   *  - a nonexistent subcommand is passed
   */
  @Test
  void topLevelHelp() {
    final String helpString =
    """
    usage: stateless-aerie COMMAND [ARGS]...

    Available commands:
     - simulate: Simulate a plan using the specified model and configuration

    Stateless Aerie v""";

    final var validArgs = new String[][]{{"-h"}, {"--help"}, {}, {"fakeCommand"},
                                         {"-h", "simulate"}, {"--help", "simulate"},
                                         {"-h", "--help"}, {"--help", "-h"}};
    for(final var args : validArgs) {
      Main.main(args);
      outputStream.flush();
      assertTrue(out.toString().contains(helpString));
      assertTrue(err.toString().isBlank());
      out.reset();
      err.reset();
    }

    // '-h' or '--help' as the second argument does not display the top-level help string
    final var otherArgs = new String[][]{{"simulate", "-h"}, {"simulate", "--help"}};
    BlockExitSecurityManager.install();
    for(final var args : otherArgs) {
      final var sysExit = assertThrows(SystemExit.class, () -> Main.main(args));
      assertEquals(0, sysExit.getStatusCode());

      outputStream.flush();
      assertFalse(out.toString().contains(helpString));
      assertTrue(err.toString().isBlank());
      out.reset();
      err.reset();
    }
    BlockExitSecurityManager.uninstall();
  }

  @Nested
  public class SimulationArguments {
    /**
     * Subcommand help message appears if the '-h' or '--help' flag is passed after the subcommand,
     * regardless of presence of other arguments or position.
     */
    @Test
    void simulationHelp() {
      final var helpString =
       """
       usage: stateless-aerie simulate [-f <arg>] [-h] [-i <arg>] -m <arg> -p
              <arg> [-s <arg>] [-v]
       Simulate a plan using the specified model and configuration
        -f,--file <arg>              output file path
        -h,--help                    display this message and exit
        -i,--update_interval <arg>   minimum interval that simulation extent
                                     updates are posted, in milliseconds
        -m,--model <arg>             path to model jar
        -p,--plan <arg>              path to plan json
        -s,--sim_config <arg>        path to simulation configuration json
        -v,--verbose                 verbosity of simulation

       Stateless Aerie v""";

      final var helpArgs = new String[][] {{"simulate", "-h"}, {"simulate", "--help"},
                                           {"simulate", "-p", "foo plan.json", "-h"},
                                           {"simulate", "-i", "1000", "--help"}};

      BlockExitSecurityManager.install();
      for (final var args : helpArgs) {
        final var sysExit = assertThrows(SystemExit.class, () -> Main.main(args));
        assertEquals(0, sysExit.getStatusCode());

        outputStream.flush();
        assertTrue(out.toString().contains(helpString));
        assertTrue(err.toString().isBlank());
        out.reset();
        err.reset();
      }
      BlockExitSecurityManager.uninstall();
    }

    /** An exception is thrown if simulation is run for a plan that doesn't exist or cannot be parsed. */
    @Test
    void badPlan() {
      final var missingFileError = assertThrows(RuntimeException.class,
                                      () -> Main.main(new String[]{
                                          "simulate",
                                          "-m", "../examples/foo-missionmodel/build/libs/foo-missionmodel.jar",
                                          "-p", "src/test/resources/fake_plan.json"}));
      assertEquals("Specified plan JSON file does not exist: src/test/resources/fake_plan.json",
                   missingFileError.getMessage());

      final var badFileError = assertThrows(RuntimeException.class,
                                      () -> Main.main(new String[]{
                                          "simulate",
                                          "-m", "../examples/foo-missionmodel/build/libs/foo-missionmodel.jar",
                                          "-p", "../examples/foo-missionmodel/build/libs/foo-missionmodel.jar"}));
      assertEquals("Error while reading plan JSON file: ../examples/foo-missionmodel/build/libs/foo-missionmodel.jar",
                   badFileError.getMessage());
    }

    /** An exception is thrown if simulation is run using a model that doesn't exist or cannot be parsed. */
    @Test
    void badModel() {
      final var missingFileError = assertThrows(RuntimeException.class,
                                      () -> Main.main(new String[]{
                                          "simulate",
                                          "-m", "fake-mission-model.jar",
                                          "-p", "src/test/resources/simpleFooPlan.json"}));
      assertEquals("Error while loading mission model: fake-mission-model.jar",
                   missingFileError.getMessage());

      final var badFileError = assertThrows(RuntimeException.class,
                                      () -> Main.main(new String[]{
                                          "simulate",
                                          "-m", "src/test/resources/simpleFooPlan.json",
                                          "-p", "src/test/resources/simpleFooPlan.json"}));
      assertEquals("Error while loading mission model: src/test/resources/simpleFooPlan.json",
                   badFileError.getMessage());
    }

    /** An exception is thrown if simulation is run using a sim config that doesn't exist or cannot be parsed. */
    @Test
    void badSimConfig() {
      final var missingFileError = assertThrows(RuntimeException.class,
                                      () -> Main.main(new String[]{
                                          "simulate",
                                          "-m", "../examples/foo-missionmodel/build/libs/foo-missionmodel.jar",
                                          "-p", "src/test/resources/simpleFooPlan.json",
                                          "-s", "src/test/resources/fake_config.json"}));
      assertEquals("Specified simulation configuration JSON file does not exist: src/test/resources/fake_config.json",
                   missingFileError.getMessage());

      final var badFileError = assertThrows(RuntimeException.class,
                                      () -> Main.main(new String[]{
                                          "simulate",
                                          "-m", "../examples/foo-missionmodel/build/libs/foo-missionmodel.jar",
                                          "-p", "src/test/resources/simpleFooPlan.json",
                                          "-s", "src/test/resources/simpleFooPlan.json"}));
      assertEquals("Error while reading simulation configuration JSON file: src/test/resources/simpleFooPlan.json",
                   badFileError.getMessage());
    }

    /** When verbose is on, progress is reported prior to simulation results. */
    @Test
    void verboseOn() throws IOException {
      Main.main(new String[]{"simulate",
                             "-m", "../examples/foo-missionmodel/build/libs/foo-missionmodel.jar",
                             "-p", "src/test/resources/simpleFooPlan.json",
                             // Extent interval cranked way up to guarantee no extent is printed (5000s)
                             "-i", "5000000000",
                             "--verbose"});
      outputStream.flush();
      try(final var reader = new BufferedReader(new FileReader("src/test/resources/simpleFooPlanResults.json"))) {
        final var fileLines = reader.lines().toList();
        final var output = out.toString();
        assertEquals(fileLines.size() + 5, output.split("\n").length);

        int truncateIndex = 0;
        for(int i = 0; i < 5; ++i) {
          truncateIndex = output.indexOf("\n", truncateIndex + 1);
        }

        final var introLines = """
            Parsing plan src/test/resources/simpleFooPlan.json...
            Loading mission model ../examples/foo-missionmodel/build/libs/foo-missionmodel.jar...
            Simulating Plan...
            Writing Results...
            """;

        assertEquals(introLines, output.substring(0, truncateIndex));

        try(final var fileReader = Json.createReader(new FileReader("src/test/resources/simpleFooPlanResults.json"));
            final var outputReader = Json.createReader(new StringReader(output.substring(truncateIndex)))) {
          final var fileJson = fileReader.readObject();
          final var outputJson = outputReader.readObject();
          assertEquals(fileJson, outputJson);
        }
      }
    }

    /** When verbose is off, only simulation results are output. */
    @Test
    void verboseOff() throws IOException {
      Main.main(new String[]{"simulate",
                             "-m", "../examples/foo-missionmodel/build/libs/foo-missionmodel.jar",
                             "-p", "src/test/resources/simpleFooPlan.json"});
      outputStream.flush();

      try(final var fileReader = Json.createReader(new FileReader("src/test/resources/simpleFooPlanResults.json"));
          final var outputReader = Json.createReader(new StringReader(out.toString()))) {
        final var fileJson = fileReader.readObject();
        final var outputJson = outputReader.readObject();
        assertEquals(fileJson, outputJson);
      }
    }

    /** Sim config bounds take precedence over plan bounds */
    @Test
    void simConfigTemporalSubset() throws FileNotFoundException {
      Main.main(new String[] {"simulate",
                              "-m", "../examples/foo-missionmodel/build/libs/foo-missionmodel.jar",
                              "-p", "src/test/resources/simpleFooPlan.json",
                              "-s", "src/test/resources/temporalSubsetFooConfiguration.json"});
      try(final var fileReader = Json.createReader(new FileReader("src/test/resources/subsetFooPlanResults.json"));
          final var outputReader = Json.createReader(new StringReader(out.toString()))) {
        final var fileJson = fileReader.readObject();
        final var outputJson = outputReader.readObject();
        assertEquals(fileJson, outputJson);
      }
    }

    /**
     * Sim exceptions are given as a well-formatted JSON.
     * Also tests that sim config arguments are applied to simulation.
     */
    @Test
    void simException() {
      BlockExitSecurityManager.install();
      final var sysExit = assertThrows(SystemExit.class,
                                       () -> Main.main(new String[]{
                                          "simulate",
                                          "-m", "../examples/foo-missionmodel/build/libs/foo-missionmodel.jar",
                                          "-p", "src/test/resources/simpleFooPlan.json",
                                          "-s", "src/test/resources/exceptionFooConfiguration.json",}));
      assertEquals(1, sysExit.getStatusCode());
      BlockExitSecurityManager.uninstall();

      assertTrue(out.toString().isBlank());
      assertFalse(err.toString().isBlank());

      try(final var errorReader = Json.createReader(new StringReader(err.toString()))) {
        final var errorJson = errorReader.readObject();
        final var dataObject = Json.createObjectBuilder()
                                   .add("elapsedTime", "01:00:00.000000")
                                   .add( "utcTimeDoy", "2024-183T01:00:00")
                                   .build();

        assertEquals(4, errorJson.keySet().size());
        assertTrue(errorJson.keySet().containsAll(List.of("type", "message", "data", "trace")));

        assertEquals("SIMULATION_EXCEPTION", errorJson.getString("type"));
        assertEquals("Daemon task exception raised.", errorJson.getString("message"));
        assertEquals(dataObject, errorJson.getJsonObject("data"));
        assertTrue(errorJson.getString("trace").startsWith("java.lang.RuntimeException: Daemon task exception raised."));
      }
    }
  }
}
