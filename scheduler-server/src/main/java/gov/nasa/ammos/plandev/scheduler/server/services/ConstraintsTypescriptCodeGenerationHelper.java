package gov.nasa.jpl.aerie.scheduler.server.services;

import java.util.Map;
import java.util.stream.Collectors;
import gov.nasa.jpl.aerie.constraints.TypescriptCodeGenerationService;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

public final class ConstraintsTypescriptCodeGenerationHelper
{
    private ConstraintsTypescriptCodeGenerationHelper() { }

    public static Map<String, TypescriptCodeGenerationService.ActivityType> activityTypes(final MerlinDatabaseService.MissionModelTypes missionModelTypes) {
        return missionModelTypes
            .activityTypes()
            .stream()
            .map(activityType -> Map.entry(
                activityType.name(),
                new gov.nasa.jpl.aerie.constraints.TypescriptCodeGenerationService.ActivityType(
                    activityType
                        .parameters()
                        .entrySet()
                        .stream()
                        .map(entry -> new gov.nasa.jpl.aerie.constraints.TypescriptCodeGenerationService.Parameter(
                            entry.getKey(),
                            entry.getValue()))
                        .toList())))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public static Map<String, ValueSchema> resources(final MerlinDatabaseService.MissionModelTypes missionModelTypes) {
        return missionModelTypes
            .resourceTypes()
            .stream()
            .map(resourceType -> Map.entry(resourceType.name(), resourceType.schema()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
