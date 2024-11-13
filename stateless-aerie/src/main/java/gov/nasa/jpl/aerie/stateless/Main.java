package gov.nasa.jpl.aerie.stateless;

import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.orchestration.parsers.GoalSpecificationParser;
import gov.nasa.jpl.aerie.orchestration.ModelUtility;
import gov.nasa.jpl.aerie.orchestration.parsers.SimulationResultsParser;
import gov.nasa.jpl.aerie.orchestration.scheduling.SchedulingUtility;
import gov.nasa.jpl.aerie.orchestration.simulation.CanceledListener;
import gov.nasa.jpl.aerie.orchestration.parsers.PlanJsonParser;
import gov.nasa.jpl.aerie.orchestration.simulation.ResourceFileStreamer;
import gov.nasa.jpl.aerie.orchestration.simulation.SimulationExtentConsumer;
import gov.nasa.jpl.aerie.orchestration.simulation.SimulationResultsWriter;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.MissionModelLoader;
import gov.nasa.jpl.aerie.merlin.driver.SimulationException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import gov.nasa.jpl.aerie.orchestration.simulation.SimulationUtility;
import gov.nasa.jpl.aerie.types.Plan;
import org.apache.commons.cli.*;

import javax.json.Json;
import javax.json.stream.JsonGenerator;

public class Main {
  private static final String VERSION = "v2.16.0";
  private static final String FOOTER = "\nStateless Aerie "+VERSION;

  private static final Option HELP_OPTION = new Option("h", "help", false, "display this message and exit");

  private sealed interface Arguments {
    record SimulationArguments<Model>(
        MissionModel<Model> missionModel,
        Plan plan,
        boolean verbose,
        Optional<Path> outputFilePath,
        long extentUpdatePeriod
    ) implements Arguments {}

    record SchedulingArguments<Model>(
        MissionModel<Model> missionModel,
        SchedulerModel schedulerModel,
        Plan plan,
        List<GoalSpecificationParser.GoalRecord> goalSpecification,
        Optional<SimulationResults> initialSimResults,
        Optional<Path> outputPlanPath,
        Optional<Path> outputSimResultsPath,
        Optional<Path> outputGoalSatisfactionPath,
        boolean simulateAfter,
        boolean verbose,
        int maxEngines
    ) implements Arguments {}
  }

  public static void main(String[] args) {
    if(args.length == 0) {
      displayTopLevelHelp();
      return;
    }

    final var command = args[0];

    switch (command.toLowerCase()) {
      case "simulate": {
        simulate(parseSimulationArgs(args));
        break;
      }
      case "schedule": {
        schedule(parseSchedulingArgs(args));
        break;
      }
      case "-h":
      case "--help":
      default:
        displayTopLevelHelp();
        break;
    }
  }

  private static Arguments.SimulationArguments<?> parseSimulationArgs(String[] args) {
    final Path modelJarPath;
    final Path planJsonPath;
    final Optional<Path> configJsonPath;
    final boolean verbose;
    final Optional<Path> outputFilePath;
    final long extentUpdatePeriod;

    // Parse the command line arguments
    final Options simulationOptions = createSimulationOptions();
    try {
      checkForHelp(args, simulationOptions, "simulate", "Simulate a plan using the specified model and configuration");

      final CommandLineParser parser = new DefaultParser();
      final CommandLine cmd = parser.parse(simulationOptions, args);

      modelJarPath = cmd.getParsedOptionValue('m');
      planJsonPath = cmd.getParsedOptionValue('p');
      verbose = cmd.hasOption("verbose");
      configJsonPath = cmd.getParsedOptionValue('s', Optional.empty());
      outputFilePath = cmd.getParsedOptionValue('f', Optional.empty());
      extentUpdatePeriod = cmd.getParsedOptionValue('i', 500L);
    } catch (ParseException e) {
      printHelp(simulationOptions, "simulate", "Simulate a plan using the specified model and configuration");
      System.exit(2);
      // The below is included as java doesn't recognize System.exit() as stopping the method,
      // which causes compilation issues when trying to use the values assigned above
      throw new RuntimeException(e);
    }

    // Parse the plan and simulation config files into a Plan object
    if (verbose) { System.out.println("Parsing plan "+planJsonPath+"..."); }
    final var plan = PlanJsonParser.parsePlan(planJsonPath);
    configJsonPath.ifPresent(path -> {
      if (verbose) { System.out.println("Parsing simulation configuration "+path+"..."); }
      PlanJsonParser.parseSimulationConfiguration(path, plan);
    });

    // Load the mission model
    try {
      if (verbose) { System.out.println("Loading mission model "+modelJarPath+"..."); }
      final var model = ModelUtility.instantiateMissionModel(
          modelJarPath,
          plan.simulationStartTimestamp.toInstant(),
          plan.simulationConfiguration()
      );

      return new Arguments.SimulationArguments<>(model, plan, verbose, outputFilePath, extentUpdatePeriod);
    } catch (MissionModelLoader.MissionModelLoadException | MissionModelLoader.MissionModelInstantiationException e) {
      throw new RuntimeException("Error while loading mission model: "+modelJarPath, e);
    }
  }

  private static void simulate(Arguments.SimulationArguments<?> simArgs) {
    if (simArgs.verbose()) { System.out.println("Simulating Plan..."); }

    Thread shutdownHook = null;
    final var rfs = new ResourceFileStreamer();
    final var canceledListener = new CanceledListener();

    // Cancel support
    try (final var extentConsumer = simArgs.verbose
            ? new SimulationExtentConsumer(simArgs.extentUpdatePeriod)
            : new SimulationExtentConsumer();
         final var simUtil = new SimulationUtility(rfs)
    ) {
      final var resultsFuture = simUtil.simulate(
          simArgs.missionModel(),
          simArgs.plan(),
          canceledListener,
          extentConsumer
      );

      shutdownHook = new Thread(() -> {
        canceledListener.cancel();
        try {
          final var results = resultsFuture.get();

          if (simArgs.verbose()) { System.out.println("Writing Results..."); }
          final var resultsWriter = new SimulationResultsWriter(results, simArgs.plan, rfs);

          simArgs.outputFilePath().ifPresentOrElse(
              p -> resultsWriter.writeResults(canceledListener, p),
              () -> resultsWriter.writeResults(canceledListener)
          );

        } catch (InterruptedException | ExecutionException e) {
          throw new RuntimeException(e);
        }
      });

      // Surround awaiting sim results in a thread to output partial results during SIGINT
      Runtime.getRuntime().addShutdownHook(shutdownHook);
      final var results = resultsFuture.get();
      if (!canceledListener.get()) {
        // Avoid two threads writing to the output file at the same time
        Runtime.getRuntime().removeShutdownHook(shutdownHook);

        if (simArgs.verbose()) { System.out.println("Writing Results..."); }
        final var resultsWriter = new SimulationResultsWriter(results, simArgs.plan, rfs);
        simArgs.outputFilePath().ifPresentOrElse(
            p -> resultsWriter.writeResults(canceledListener, p),
            () -> resultsWriter.writeResults(canceledListener)
        );
      }
    } catch (ExecutionException e) {
      if (e.getCause() instanceof SimulationException se) {
        // Write Formatted Sim Exception to std.err
        final Map<String,String> config = Map.of(JsonGenerator.PRETTY_PRINTING, "");
        try(final var jsonWriter = Json.createWriterFactory(config).createWriter(System.err)) {
          jsonWriter.writeObject(SimulationUtility.formatSimulationException(se));
        }
        System.exit(1);
      }
      throw new RuntimeException(e);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (IllegalStateException ise) {
      // If this is the message, it must've come from Runtime.getRuntime().removeShutdownHook and can be safely ignored
      if (!ise.getMessage().contains("Shutdown in progress")) throw ise;
    } finally {
      // Try-catch wrapping in case this is executed while the shutdown hook is running.
      try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
      catch (IllegalStateException ise) {}
    }
  }

  private static Arguments.SchedulingArguments<?> parseSchedulingArgs(String[] args) {
    final Path modelJarPath;
    final Path planJsonPath;
    final Path schedulingSpecJsonPath;
    final Optional<Path> simConfigJsonPath;
    final Optional<Path> initialSimResultJsonPath;

    final boolean verbose;
    final boolean simulateAfter;
    final int maxEngines;

    final Optional<Path> outputPlanPath;
    final Optional<Path> outputSimResultsPath;
    final Optional<Path> outputGoalSatisfactionPath;

    // Parse the command line arguments
    final Options schedulingOptions = createSchedulingOptions();
    try {
      checkForHelp(
          args,
          schedulingOptions,
          "schedule",
          "Schedule a plan using the specified model, configuration, and procedural goal specification");

      final CommandLineParser parser = new DefaultParser();
      final CommandLine cmd = parser.parse(schedulingOptions, args);

      modelJarPath = cmd.getParsedOptionValue('m');
      planJsonPath = cmd.getParsedOptionValue('p');
      schedulingSpecJsonPath = cmd.getParsedOptionValue('g');
      simConfigJsonPath = cmd.getParsedOptionValue('s', Optional.empty());
      initialSimResultJsonPath = cmd.getParsedOptionValue('r', Optional.empty());

      verbose = cmd.hasOption("verbose");
      simulateAfter = cmd.hasOption("simulate_after");
      maxEngines = cmd.getParsedOptionValue('e', 1);
      if(maxEngines < 1) {
        System.err.println("Maximum engines must be greater than 0");
        System.exit(2);
      }

      outputPlanPath = cmd.getParsedOptionValue("op", Optional.empty());
      outputSimResultsPath = cmd.getParsedOptionValue("or", Optional.empty());
      outputGoalSatisfactionPath = cmd.getParsedOptionValue("og", Optional.empty());


      // TODO: DEBUG PRINTS
      System.out.println(String.join(", ", args));
      System.out.println("modelJar " + modelJarPath);
      System.out.println("planJson " + planJsonPath);
      System.out.println("schedspec " + schedulingSpecJsonPath);
      System.out.println("simconfig " + simConfigJsonPath);
      System.out.println("initResults " + initialSimResultJsonPath);
      System.out.println("outputPlanPath " + outputPlanPath);
      System.out.println("outputSimResultsPath " + outputSimResultsPath);
      System.out.println("outputGoalSatisfactionPath " + outputGoalSatisfactionPath);
      System.out.println("verbose " + verbose);
      System.out.println("simulateAfter " + simulateAfter);
      System.out.println("maxEngines " + maxEngines);
    } catch (ParseException e) {
      printHelp(
          schedulingOptions,
          "schedule",
          "Schedule a plan using the specified model, configuration, and procedural goal specification");
      System.exit(2);
      // The below is included as java doesn't recognize System.exit() as stopping the method,
      // which causes compilation issues when trying to use the values assigned above
      throw new RuntimeException(e);
    }

    if (verbose) { System.out.println("Parsing scheduling specification "+schedulingSpecJsonPath+"..."); }
    final var goalSpec = GoalSpecificationParser.parseGoalSpecification(schedulingSpecJsonPath);

    if (verbose) { System.out.println("Parsing plan "+planJsonPath+"..."); }
    final var plan = PlanJsonParser.parsePlan(planJsonPath);
    simConfigJsonPath.ifPresent(path -> {
      if (verbose) {
        System.out.println("Parsing simulation configuration " + path + "...");
      }
      PlanJsonParser.parseSimulationConfiguration(path, plan);
    });

    final Optional<SimulationResults> initialSimResults = initialSimResultJsonPath.map(path -> {
       if (verbose) { System.out.println("Parsing initial simulation results "+path+"..."); }
       return SimulationResultsParser.parseSimulationResults(path);
    });

    // Load the mission model
    try {
      if (verbose) { System.out.println("Loading mission model "+modelJarPath+"..."); }
      final var model = ModelUtility.instantiateMissionModel(
          modelJarPath,
          plan.simulationStartTimestamp.toInstant(),
          plan.simulationConfiguration()
      );

      final var schedulerModel = ModelUtility.instantiateSchedulerModel(modelJarPath);

      return new Arguments.SchedulingArguments<>(
          model,
          schedulerModel,
          plan,
          goalSpec,
          initialSimResults,
          outputPlanPath,
          outputSimResultsPath,
          outputGoalSatisfactionPath,
          simulateAfter,
          verbose,
          maxEngines
      );
    } catch (MissionModelLoader.MissionModelLoadException | MissionModelLoader.MissionModelInstantiationException | ModelUtility.SchedulerModelLoadException e) {
      throw new RuntimeException("Error while loading mission model: " + modelJarPath, e);
    }
  }

  private static void schedule(Arguments.SchedulingArguments<?> schedArgs) {
    final var schedulingUtility = new SchedulingUtility(schedArgs.missionModel, schedArgs.schedulerModel, schedArgs.maxEngines());

    try {
      schedulingUtility.schedule(schedArgs.goalSpecification, schedArgs.plan(), new CanceledListener(), schedArgs.initialSimResults);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

  }

  /**
   * Display top-level help for the application
   */
  private static void displayTopLevelHelp() {
    System.out.printf(
    """
    usage: stateless-aerie COMMAND [ARGS]...

    Available commands:
     - simulate: Simulate a plan using the specified model and configuration
     - schedule: Schedule a plan using the specified model, configuration, and procedural goal specification
    %s
    %n""", FOOTER);
  }

  /**
   * Build the parser options for the "simulate" command.
   */
  private static Options createSimulationOptions() {
    // Required Args
    final Option modelPath = new Option("m", "model", true, "path to model jar");
    modelPath.setRequired(true);
    modelPath.setConverter(Path::of);

    final Option planPath = new Option("p", "plan", true, "path to plan json");
    planPath.setRequired(true);
    planPath.setConverter(Path::of);

    // Optional Path Args
    final Option simConfigPath = new Option("s", "sim_config", true, "path to simulation configuration json");
    simConfigPath.setRequired(false);
    simConfigPath.setConverter(s -> Optional.of(Path.of(s)));

    final Option outputFile = new Option("f", "file", true, "output file path");
    outputFile.setRequired(false);
    outputFile.setConverter(f -> Optional.of(Path.of(f)));

    // Other Optional Args
    final Option verbose = new Option("v", "verbose", false, "verbosity of simulation");

    final Option extentUpdateFrequency = new Option("i", "update_interval", true, "minimum interval that simulation extent updates are posted, in milliseconds" );
    extentUpdateFrequency.setRequired(false);
    extentUpdateFrequency.setConverter(Long::parseLong);

    final Options simulationOptions = new Options();
    simulationOptions.addOption(verbose);
    simulationOptions.addOption(modelPath);
    simulationOptions.addOption(planPath);
    simulationOptions.addOption(simConfigPath);
    simulationOptions.addOption(outputFile);
    simulationOptions.addOption(extentUpdateFrequency);
    return simulationOptions;
  }

  /**
   * Build the parser options for the "schedule" command.
   */
  private static Options createSchedulingOptions() {
    // Required Args
    final Option modelPath = new Option("m", "model", true, "path to model jar");
    modelPath.setRequired(true);
    modelPath.setConverter(Path::of);

    final Option planPath = new Option("p", "plan", true, "path to plan json");
    planPath.setRequired(true);
    planPath.setConverter(Path::of);

    final Option goalSpecPath = new Option("g", "goals", true, "path to goal specification json");
    goalSpecPath.setRequired(true);
    goalSpecPath.setConverter(Path::of);

    // Optional Input Args
    final Option simConfigPath = new Option("s", "sim_config", true, "path to simulation configuration json");
    simConfigPath.setRequired(false);
    simConfigPath.setConverter(s -> Optional.of(Path.of(s)));

    final Option simResultsPath = new Option("r", "initial_sim_results", true, "path to a simulation results json to be used as the initial sim results");
    simResultsPath.setRequired(false);
    simConfigPath.setConverter(s -> Optional.of(Path.of(s)));

    final Option simulateAfter = new Option("a", "simulate_after", false, "ensure a final simulation is run after scheduling has completed but before simulation results are returned");

    final Option verbose = new Option("v", "verbose", false, "verbosity of scheduling");

    final Option maxEngineCount = new Option("e", "max_engine_count", true, "maximum number of parallel engines permitted. defaults to 1" );
    maxEngineCount.setRequired(false);
    maxEngineCount.setConverter(Integer::parseInt);

    // Optional Output Args
    final Option outputPlanPath = new Option("op", "output_plan", true, "output plan file");
    outputPlanPath.setRequired(false);
    outputPlanPath.setConverter(f -> Optional.of(Path.of(f)));

    final Option outputSimResultsPath = new Option("or", "output_sim_results", true, "output simulation results file");
    outputSimResultsPath.setRequired(false);
    outputSimResultsPath.setConverter(f -> Optional.of(Path.of(f)));

    final Option outputGoalSatisfactionPath = new Option("og", "output_goal_satisfaction", true, "output goal satisfaction file");
    outputGoalSatisfactionPath.setRequired(false);
    outputGoalSatisfactionPath.setConverter(f -> Optional.of(Path.of(f)));

    final Options schedulingOptions = new Options();
    schedulingOptions.addOption(modelPath);
    schedulingOptions.addOption(planPath);
    schedulingOptions.addOption(goalSpecPath);
    schedulingOptions.addOption(simConfigPath);
    schedulingOptions.addOption(simResultsPath);
    schedulingOptions.addOption(simulateAfter);
    schedulingOptions.addOption(verbose);
    schedulingOptions.addOption(maxEngineCount);
    schedulingOptions.addOption(outputPlanPath);
    schedulingOptions.addOption(outputSimResultsPath);
    schedulingOptions.addOption(outputGoalSatisfactionPath);
    return schedulingOptions;
  }

  /**
   * Check if the "help" option was passed for a given command
   *   and, if so, print the command's help message and exit the program with status code 0.
   * Checked independently to avoid required args for the command causing parsing issues.
   * @param args the args passed into the commandline.
   * @param subCommandOptions the parser options normally used to parse this command.
   * @param subcommand the name of the subcommand (ie "simulate").
   * @param subcommandDescription the description of what the subcommand does.
   */
  private static void checkForHelp(
      String[] args,
      Options subCommandOptions,
      String subcommand,
      String subcommandDescription
  ) throws ParseException  {
    for(final var opt : args) {
      if (opt.equals("-h") || opt.equals("--help")) {
        printHelp(subCommandOptions, subcommand, subcommandDescription);
        System.exit(0);
      }
    }
  }

  private static void printHelp(Options subCommandOptions, String subcommand, String subcommandDescription) {
    subCommandOptions.addOption(HELP_OPTION);
    new HelpFormatter().printHelp(
        "stateless-aerie " + subcommand,
        subcommandDescription,
        subCommandOptions,
        FOOTER,
        true);
  }
}
