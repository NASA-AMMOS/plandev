package gov.nasa.ammos.plandev.merlin.server.remotes;

import gov.nasa.ammos.plandev.merlin.protocol.model.InputType.Parameter;
import gov.nasa.ammos.plandev.merlin.protocol.model.Resource;
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;
import gov.nasa.ammos.plandev.merlin.server.models.ActivityDirectiveForValidation;
import gov.nasa.ammos.plandev.merlin.server.models.ActivityType;
import gov.nasa.ammos.plandev.merlin.server.models.MissionModelJar;
import gov.nasa.ammos.plandev.merlin.server.services.MissionModelService.NoSuchMissionModelException;
import gov.nasa.ammos.plandev.merlin.server.services.MissionModelService.BulkArgumentValidationResponse;
import gov.nasa.ammos.plandev.types.MissionModelId;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;
import java.util.Map;

public interface MissionModelRepository {
    // Queries
    Map<MissionModelId, MissionModelJar> getAllMissionModels();
    MissionModelJar getMissionModel(MissionModelId id) throws NoSuchMissionModelException;
    Map<String, ActivityType> getActivityTypes(MissionModelId missionModelId) throws NoSuchMissionModelException;
    /** Configuration parameters as STORED, in declaration order. The read path for a model with no JAR
     *  to introspect. */
    List<Parameter> getModelParameters(MissionModelId missionModelId) throws NoSuchMissionModelException;
    /** Resource schemas as STORED. Same reason: for a JAR-less model this table is the only source. */
    Map<String, ValueSchema> getResourceTypes(MissionModelId missionModelId) throws NoSuchMissionModelException;

    // Mutations
    /**
     * Record the digest of a model's declared type surface, if it differs from what is stored.
     *
     * @return true if the stored digest changed. Because any mission_model update bumps its revision, a
     *         true here means the model revision moved -- which stamps subsequent results and invalidates
     *         cached simulations. Implementations must therefore not write an unchanged value.
     */
    boolean updateExternalIdentityHash(MissionModelId missionModelId, String identityHash);

    /**
     * Record what PlanDev may do with this model, as raw jsonb text.
     *
     * @return true if the stored capabilities changed, with the same revision consequences as
     *         {@link #updateExternalIdentityHash}. Implementations compare as jsonb rather than as
     *         text, so a producer that reorders its keys between writes is not a change.
     */
    boolean updateExternalCapabilities(MissionModelId missionModelId, String capabilitiesJson);

    void updateModelParameters(MissionModelId missionModelId, final List<Parameter> modelParameters) throws NoSuchMissionModelException;
    void updateActivityTypes(MissionModelId missionModelId, final Map<String, ActivityType> activityTypes, final List<String> subsystems) throws NoSuchMissionModelException;
    void updateResourceTypes(MissionModelId missionModelId, final Map<String, Resource<?>> resourceTypes) throws NoSuchMissionModelException;
    /** Write resource types from SCHEMAS rather than from live {@link Resource} objects, which a
     *  JAR-less model has none of. */
    void updateResourceTypeSchemas(MissionModelId missionModelId, final Map<String, ValueSchema> resourceTypes) throws NoSuchMissionModelException;
    Map<MissionModelId, List<ActivityDirectiveForValidation>> getUnvalidatedDirectives();
    void updateDirectiveValidations(List<Pair<ActivityDirectiveForValidation, BulkArgumentValidationResponse>> updates);
}
