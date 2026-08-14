package gov.nasa.jpl.aerie.scheduler.server.services;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchMissionModelException;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.scheduler.server.models.PlanId;
import gov.nasa.jpl.aerie.types.MissionModelId;

public record GenerateSchedulingLibAction(
    MerlinDatabaseService.ReaderRole merlinDatabaseService
) {
  public GenerateSchedulingLibAction {
    Objects.requireNonNull(merlinDatabaseService);
  }

  /**
   * common interface for different possible results of the query
   */
  public sealed interface Response {
    record Failure(String reason) implements Response {}
    record Success(Map<String, String> files) implements Response {}
  }

  /**
   * generates the scheduling typescript files
   *
   * @param missionModelId the id of the mission model for which to generate a scheduling library
   * @param planId the optional id of the plan concerned by this code generation, if plan id is provided, code will be generated for external resources
   * associated with the plan
   * @return a response object wrapping the results of generating the code (either successful or not)
   */
  public Response run(final MissionModelId missionModelId, final Optional<PlanId> planId) {
    try {
      final var schedulingDsl         = getTypescriptResource("scheduler-edsl-fluent-api.ts");
      final var schedulerAst          = getTypescriptResource("scheduler-ast.ts");
      final var windowsDsl            = getTypescriptResource("constraints/constraints-edsl-fluent-api.ts");
      final var windowsAst            = getTypescriptResource("constraints/constraints-ast.ts");
      final var temporalPolyfillTypes = getTypescriptResource("constraints/TemporalPolyfillTypes.ts");


      var missionModelTypes = merlinDatabaseService.getMissionModelTypes(missionModelId);
      if(planId.isPresent()) {
        final var allResourceTypes = merlinDatabaseService.getResourceTypes(planId.get());
        missionModelTypes = new MerlinDatabaseService.MissionModelTypes(missionModelTypes.activityTypes(), allResourceTypes);
      }

      final var generatedSchedulerCode = TypescriptCodeGenerationService.generateTypescriptTypesFromMissionModel(missionModelTypes);
      final var generatedConstraintsCode = gov.nasa.jpl.aerie.constraints.TypescriptCodeGenerationService
          .generateTypescriptTypes(
              ConstraintsTypescriptCodeGenerationHelper.activityTypes(missionModelTypes),
              ConstraintsTypescriptCodeGenerationHelper.resources(missionModelTypes));
      return new Response.Success(
          Map.of("file:///%s".formatted(schedulingDsl.basename), schedulingDsl.source,
                 "file:///scheduler-mission-model-generated-code.ts", generatedSchedulerCode,
                 "file:///%s".formatted(schedulerAst.basename), schedulerAst.source,
                 "file:///%s".formatted(windowsDsl.basename), windowsDsl.source,
                 "file:///%s".formatted(windowsAst.basename), windowsAst.source,
                 "file:///mission-model-generated-code.ts", generatedConstraintsCode,
                 "file:///%s".formatted(temporalPolyfillTypes.basename), temporalPolyfillTypes.source
                 ));
    } catch (final IOException | MerlinServiceException |
                   NoSuchPlanException | NoSuchMissionModelException e) {
      return new Response.Failure(e.getMessage());
    }
  }

  /*package-private*/ record TypescriptResource(String basename, String source) { }

  /** Retrieve a static Typescript library as a resource by the file's basename. */
  /*package-private*/ static TypescriptResource getTypescriptResource(final String basename) {
    final var stream = GenerateSchedulingLibAction.class.getResourceAsStream("/"+basename);
    if (stream == null)
      throw new Error("Resource path does not exist: `/%s`".formatted(basename));
    final var source = new BufferedReader(new InputStreamReader(stream)).lines().collect(Collectors.joining("\n"));
    return new TypescriptResource(basename, source);
  }
}
