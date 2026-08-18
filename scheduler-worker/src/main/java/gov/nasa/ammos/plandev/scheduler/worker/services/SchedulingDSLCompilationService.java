package gov.nasa.ammos.plandev.scheduler.worker.services;

import gov.nasa.ammos.plandev.json.JsonParser;
import gov.nasa.ammos.plandev.scheduler.server.http.InvalidJsonEntityException;
import gov.nasa.ammos.plandev.scheduler.server.models.PlanId;
import gov.nasa.ammos.plandev.scheduler.server.models.ResourceType;
import gov.nasa.ammos.plandev.scheduler.server.models.SchedulingCompilationError;
import gov.nasa.ammos.plandev.scheduler.server.models.SchedulingDSL;
import gov.nasa.ammos.plandev.scheduler.server.services.ConstraintsTypescriptCodeGenerationHelper;
import gov.nasa.ammos.plandev.scheduler.server.services.MerlinDatabaseService;
import gov.nasa.ammos.plandev.scheduler.server.services.MerlinServiceException;
import gov.nasa.ammos.plandev.scheduler.server.services.TypescriptCodeGenerationService;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.stream.JsonParsingException;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SchedulingDSLCompilationService {

  private final Process nodeProcess;
  private static final Logger logger = LoggerFactory.getLogger(SchedulingDSLCompilationService.class);

  public SchedulingDSLCompilationService()
  throws IOException
  {
    final var schedulingDslCompilerRoot = System.getenv("SCHEDULING_DSL_COMPILER_ROOT");
    final var schedulingDslCompilerCommand = System.getenv("SCHEDULING_DSL_COMPILER_COMMAND");
    final var nodePath = System.getenv("NODE_PATH");
    // --no-node-snapshot is required to use isolated-vm (dependency of plandev-ts-user-code-runner)
    final var nodeFlags = "--no-node-snapshot";
    logger.info("Starting Scheduling DSL compilation service subprocess with: {} {} {}", nodePath, nodeFlags, schedulingDslCompilerCommand);
    final var processBuilder = new ProcessBuilder(nodePath, nodeFlags, schedulingDslCompilerCommand)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .directory(new File(schedulingDslCompilerRoot));
    processBuilder.environment().put("NODE_NO_WARNINGS", "1");
    this.nodeProcess = processBuilder.start();

    final var inputStream = this.nodeProcess.outputWriter();
    inputStream.write("ping\n");
    inputStream.flush();
    if (!Objects.equals(this.nodeProcess.inputReader().readLine(), "pong")) {
      throw new Error("Could not create node subprocess");
    }
    logger.info("Scheduling DSL compilation service started successfully");
  }

  public void close() {
    this.nodeProcess.destroy();
  }

  public SchedulingDSLCompilationResult<SchedulingDSL.ConditionSpecifier> compileGlobalSchedulingCondition(final MerlinDatabaseService.ReaderRole merlinDatabaseService, final PlanId planId, final String conditionTypescript, final
                                                                                                           Collection<ResourceType> additionalResourceTypes) {
    try{
      final var missionModelTypes = merlinDatabaseService.getMissionModelTypes(planId);
      final var aggregatedResourceTypes = new ArrayList<>(missionModelTypes.resourceTypes());
      aggregatedResourceTypes.addAll(additionalResourceTypes);
      final var planTypes = new MerlinDatabaseService.MissionModelTypes(missionModelTypes.activityTypes(), aggregatedResourceTypes);
      return compile(planTypes,  conditionTypescript, SchedulingDSL.conditionSpecifierP, "GlobalSchedulingCondition");
    } catch (IOException | MerlinServiceException e) {
        throw new Error(e);
    }
  }

  public SchedulingDSLCompilationResult<SchedulingDSL.GoalSpecifier> compileSchedulingGoalDSL(
      final MerlinDatabaseService.ReaderRole merlinDatabaseService,
      final PlanId planId,
      final String goalTypescript){
    return compileSchedulingGoalDSL(merlinDatabaseService, planId, goalTypescript, List.of());
  }

  /**
   * NOTE: This method is not re-entrant (assumes only one call to this method is running at any given time)
   */
  public SchedulingDSLCompilationResult<SchedulingDSL.GoalSpecifier> compileSchedulingGoalDSL(
      final MerlinDatabaseService.ReaderRole merlinDatabaseService,
      final PlanId planId,
      final String goalTypescript,
      final Collection<ResourceType> additionalResourceTypes)
  {
    final long startNanos = System.nanoTime();
    logger.info("Compiling scheduling goal DSL ({} characters)", goalTypescript.length());
    try {
      final var missionModelTypes = merlinDatabaseService.getMissionModelTypes(planId);
      final var aggregatedResourceTypes = new ArrayList<>(missionModelTypes.resourceTypes());
      aggregatedResourceTypes.addAll(additionalResourceTypes);
      final var augmentedMissionModelTypes = new MerlinDatabaseService.MissionModelTypes(missionModelTypes.activityTypes(), aggregatedResourceTypes);
      return compile(augmentedMissionModelTypes, goalTypescript, SchedulingDSL.schedulingJsonP(augmentedMissionModelTypes), "Goal");
    } catch (IOException | MerlinServiceException e) {
      throw new Error(e);
    } finally {
      final long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
      logger.info("Scheduling goal compilation completed in {} ms", elapsedMs);
    }
  }

  private <T> SchedulingDSLCompilationResult<T> compile(
      final MerlinDatabaseService.MissionModelTypes missionModelTypes,
      final String goalTypescript,
      final JsonParser<T> parser,
      final String expectedReturnType)
  {
    final var schedulerGeneratedCode = TypescriptCodeGenerationService.generateTypescriptTypesFromMissionModel(missionModelTypes);
    final var constraintsGeneratedCode = gov.nasa.ammos.plandev.constraints.TypescriptCodeGenerationService.generateTypescriptTypes(
        ConstraintsTypescriptCodeGenerationHelper.activityTypes(missionModelTypes),
        ConstraintsTypescriptCodeGenerationHelper.resources(missionModelTypes));
    logger.info("Generated mission model TypeScript types for goal ({} characters)", goalTypescript.length());
    final JsonObject messageJson = Json.createObjectBuilder()
        .add("goalCode", goalTypescript)
        .add("schedulerGeneratedCode", schedulerGeneratedCode)
        .add("constraintsGeneratedCode", constraintsGeneratedCode)
        .add("expectedReturnType", expectedReturnType)
        .build();

    /*
    * PROTOCOL:
    *   denote this java program as JAVA, and the node subprocess as NODE
    *
    *   JAVA -- stdin --> NODE: { "goalCode": "sourcecode", "missionModelGeneratedCode": "generatedcode" } \n
    *   NODE -- stdout --> JAVA: one of "success\n", "error\n", or "panic\n"
    *   NODE -- stdout --> JAVA: payload associated with success, error, or panic, must be exactly one line terminated with \n
    * */
    final var inputWriter = this.nodeProcess.outputWriter();
    final var outputReader = this.nodeProcess.inputReader();
    try {
      inputWriter.write(messageJson+"\n");
      inputWriter.flush();
      final var status = outputReader.readLine();
      return switch (status) {
        case "panic" -> throw new Error(outputReader.readLine());
        case "error" -> {
          final var output = outputReader.readLine();
          try {
            logger.info("Received error from scheduling DSL compilation process");
            yield new SchedulingDSLCompilationResult.Error<>(parseJson(
                output,
                SchedulingCompilationError.schedulingErrorJsonP));
          } catch (InvalidJsonEntityException e) {
            throw new Error("Could not parse JSON returned from typescript: " + e.failures + "\n" + output);
          }
        }
        case "success" -> {
          final var output = outputReader.readLine();
          logger.info("Compiled scheduling DSL successfully ({} characters)", goalTypescript.length());
          try {
            yield new SchedulingDSLCompilationResult.Success<>(parseJson(output, parser));
          } catch (InvalidJsonEntityException e) {
            throw new Error("Could not parse JSON returned from typescript: " + e.failures + "\n" + output, e);
          }
        }
        default -> throw new Error("Scheduling DSL compiler returned unexpected status: " + status);
      };
    } catch (IOException e) {
      throw new Error(e);
    }
  }

  private static <T> T parseJson(final String jsonStr, final JsonParser<T> parser)
  throws JsonParsingException, InvalidJsonEntityException
  {
    try (final var reader = Json.createReader(new StringReader(jsonStr))) {
      final var requestJson = reader.readValue();
      final var result = parser.parse(requestJson);
      return result.getSuccessOrThrow(reason -> new InvalidJsonEntityException(List.of(reason)));
    }
  }

  public sealed interface SchedulingDSLCompilationResult<T> {
    record Success<T>(T value) implements SchedulingDSLCompilationResult<T> {}
    record Error<T>(List<SchedulingCompilationError.UserCodeError> errors) implements SchedulingDSLCompilationResult<T> {}
  }
}
