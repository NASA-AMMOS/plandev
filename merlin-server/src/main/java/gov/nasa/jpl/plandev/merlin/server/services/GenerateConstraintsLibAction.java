package gov.nasa.jpl.plandev.merlin.server.services;

import gov.nasa.jpl.plandev.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.plandev.merlin.server.models.PlanId;
import gov.nasa.jpl.plandev.types.MissionModelId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record GenerateConstraintsLibAction(TypescriptCodeGenerationServiceAdapter typescriptCodeGenerationService) {
  public GenerateConstraintsLibAction {
    Objects.requireNonNull(typescriptCodeGenerationService);
  }

  /**
   * common interface for different possible results of the query
   */
  public sealed interface Response {
    record Failure(String reason) implements Response {}
    record Success(Map<String, String> files) implements Response {}
  }

  /**
   * Generate the source files used for running constraint definitions.
   *
   * @return a response object wrapping the results of generating the code (either successful or not)
   */
  public Response run(final MissionModelId missionModelId, final Optional<PlanId> planId) {

    try {
      final var constraintsDslCompilerRoot = System.getenv("CONSTRAINTS_DSL_COMPILER_ROOT");
      final var constraintsApi = Files.readString(Paths.get(constraintsDslCompilerRoot, "src", "libs", "constraints-edsl-fluent-api.ts"));
      final var constraintsAst = Files.readString(Paths.get(constraintsDslCompilerRoot, "src", "libs", "constraints-ast.ts"));
      final var temporalPolyfillTypes = Files.readString(Paths.get(constraintsDslCompilerRoot, "src", "libs", "TemporalPolyfillTypes.ts"));
      final var generated = typescriptCodeGenerationService.generateTypescriptTypes(missionModelId, planId, Optional.empty());
      return new Response.Success(
          Map.of("constraints-edsl-fluent-api.ts", constraintsApi,
                 "mission-model-generated-code.ts", generated,
                 "constraints-ast.ts", constraintsAst,
                 "TemporalPolyfillTypes.ts", temporalPolyfillTypes
          ));
    } catch (MissionModelService.NoSuchMissionModelException | NoSuchPlanException | IOException e) {
      return new Response.Failure(e.getMessage());
    }
  }
}
