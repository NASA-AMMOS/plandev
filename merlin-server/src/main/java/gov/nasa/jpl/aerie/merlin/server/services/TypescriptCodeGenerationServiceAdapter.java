
package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.constraints.TypescriptCodeGenerationService;
import gov.nasa.jpl.aerie.merlin.driver.MissionModelLoader;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.merlin.server.models.SimulationDatasetId;
import gov.nasa.jpl.aerie.types.MissionModelId;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class TypescriptCodeGenerationServiceAdapter {
  private final MissionModelService missionModelService;
  private final PlanService planService;

  public TypescriptCodeGenerationServiceAdapter(final MissionModelService missionModelService, final PlanService planService) {
    this.missionModelService = missionModelService;
    this.planService = planService;
  }

  public String generateTypescriptTypes(final MissionModelId missionModelId, final Optional<PlanId> planId, final Optional<SimulationDatasetId> simulationDatasetId)
  throws MissionModelService.NoSuchMissionModelException, NoSuchPlanException, MissionModelLoader.MissionModelLoadException
  {
    return TypescriptCodeGenerationService
        .generateTypescriptTypes(
            activityTypes(missionModelService, missionModelId),
            resourceSchemas(missionModelService, missionModelId, planService, planId, simulationDatasetId));
  }

  static Map<String, TypescriptCodeGenerationService.ActivityType> activityTypes(final MissionModelService missionModelService, final MissionModelId missionModelId)
  throws MissionModelService.NoSuchMissionModelException
  {
    return missionModelService
        .getActivityTypes(missionModelId)
        .entrySet()
        .stream()
        .map(entry -> Map.entry(
            entry.getKey(),
            new TypescriptCodeGenerationService.ActivityType(
                entry
                    .getValue()
                    .parameters()
                    .stream()
                    .map(p -> new TypescriptCodeGenerationService.Parameter(p.name(), p.schema()))
                    .toList())))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }
  static Map<String, ValueSchema> resourceSchemas(
      final MissionModelService missionModelService,
      final MissionModelId modelId,
      final PlanService planService,
      final Optional<PlanId> planId,
      final Optional<SimulationDatasetId> simulationDatasetId
  ) throws MissionModelService.NoSuchMissionModelException, NoSuchPlanException, MissionModelLoader.MissionModelLoadException {
    final var simulatedResourceSchemas = missionModelService.getResourceSchemas(modelId);
    final var results = new HashMap<String, ValueSchema>(simulatedResourceSchemas);
    if (planId.isPresent()) {
        final var externalResourceSchemas = planService.getExternalResourceSchemas(planId.get(), simulationDatasetId);
        results.putAll(externalResourceSchemas);
    }
    return results;
  }
}

