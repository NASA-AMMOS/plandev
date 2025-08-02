package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.constraints.model.EDSLConstraintResult;
import gov.nasa.jpl.aerie.constraints.tree.Expression;
import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.merlin.server.http.InvalidEntityException;
import gov.nasa.jpl.aerie.merlin.server.http.InvalidJsonException;
import gov.nasa.jpl.aerie.merlin.server.models.ConstraintsCompilationError;
import gov.nasa.jpl.aerie.constraints.json.ConstraintParsers;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.aerie.types.MissionModelId;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.stream.JsonParsingException;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class ConstraintsDSLCompilationService {

  private final Process nodeProcess;
  private final TypescriptCodeGenerationServiceAdapter typescriptCodeGenerationService;

  public ConstraintsDSLCompilationService(final TypescriptCodeGenerationServiceAdapter typescriptCodeGenerationService)
  throws IOException
  {
    this.typescriptCodeGenerationService = typescriptCodeGenerationService;
    final var constraintsDslCompilerRoot = System.getenv("CONSTRAINTS_DSL_COMPILER_ROOT");
    final var constraintsDslCompilerCommand = System.getenv("CONSTRAINTS_DSL_COMPILER_COMMAND");
    final var nodePath = System.getenv("NODE_PATH");
    final var processBuilder = new ProcessBuilder(nodePath, "--experimental-vm-modules", constraintsDslCompilerCommand)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .directory(new File(constraintsDslCompilerRoot));
    processBuilder.environment().put("NODE_NO_WARNINGS", "1");
    this.nodeProcess = processBuilder.start();

    final var inputStream = this.nodeProcess.outputWriter();
    inputStream.write("ping\n");
    inputStream.flush();
    if (!Objects.equals(this.nodeProcess.inputReader().readLine(), "pong")) {
      throw new Error("Could not create node subprocess");
    }
  }

  public void close() {
    this.nodeProcess.destroy();
  }

  /**
   * NOTE: This method is not re-entrant (assumes only one call to this method is running at any given time)
   */
  synchronized public ConstraintsDSLCompilationResult compileConstraintsDSL(
      final MissionModelId missionModelId,
      final Optional<PlanId> planId,
      final Optional<SimulationDatasetId> simulationDatasetId,
      final String constraintTypescript
  ) throws MissionModelService.NoSuchMissionModelException, NoSuchPlanException
  {
    final var missionModelGeneratedCode = this.typescriptCodeGenerationService.generateTypescriptTypes(missionModelId, planId, simulationDatasetId);
    final JsonObject messageJson = Json.createObjectBuilder()
        .add("constraintCode", constraintTypescript)
        .add("missionModelGeneratedCode", missionModelGeneratedCode)
        .add("expectedReturnType", "Constraint")
        .build();
    /*
     * PROTOCOL:
     *   denote this java program as JAVA, and the node subprocess as NODE
     *
     *   JAVA -- stdin --> NODE: { "constraintCode": "sourcecode", "missionModelGeneratedCode": "generatedcode" } \n
     *   NODE -- stdout --> JAVA: one of "success\n", "error\n", or "panic\n"
     *   NODE -- stdout --> JAVA: payload associated with success, error, or panic, must be exactly one line terminated with \n
     * */
    final var inputWriter = this.nodeProcess.outputWriter();
    final var outputReader = this.nodeProcess.inputReader();
    try {
      inputWriter.write(messageJson +"\n");
      inputWriter.flush();
      final var status = outputReader.readLine();
      return switch (status) {
        case "panic" -> throw new Error(outputReader.readLine());
        case "error" -> {
          final var output = outputReader.readLine();
          try {
            yield new ConstraintsDSLCompilationResult.Error(parseJson(output, ConstraintsCompilationError.constraintsErrorJsonP));
          } catch (InvalidJsonException | InvalidEntityException e) {
            throw new Error("Could not parse error JSON returned from typescript: " + output, e);
          }
        }
        case "success" -> {
          final var output = outputReader.readLine();
          try {
            yield new ConstraintsDSLCompilationResult.Success(parseJson(output, ConstraintParsers.constraintP));
          } catch (InvalidJsonException | InvalidEntityException e) {
            throw new Error("Could not parse success JSON returned from typescript: " + output, e);
          }
        }
        default -> throw new Error("constraints dsl compiler returned unexpected status: " + status);
      };
    } catch (IOException e) {
      throw new Error(e);
    }
  }

  private static <T> T parseJson(final String jsonStr, final JsonParser<T> parser)
  throws InvalidJsonException, InvalidEntityException
  {
    try (final var reader = Json.createReader(new StringReader(jsonStr))) {
      final var requestJson = reader.readValue();
      final var result = parser.parse(requestJson);
      return result.getSuccessOrThrow(reason -> new InvalidEntityException(List.of(reason)));
    } catch (JsonParsingException e) {
      throw new InvalidJsonException(e);
    }
  }

  public sealed interface ConstraintsDSLCompilationResult {
    record Success(Expression<EDSLConstraintResult> constraintExpression) implements ConstraintsDSLCompilationResult {}
    record Error(List<ConstraintsCompilationError> errors) implements ConstraintsDSLCompilationResult {}
  }
}
