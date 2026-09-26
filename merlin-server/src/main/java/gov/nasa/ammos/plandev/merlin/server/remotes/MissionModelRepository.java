package gov.nasa.ammos.plandev.merlin.server.remotes;

import gov.nasa.ammos.plandev.merlin.protocol.model.InputType.Parameter;
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;
import gov.nasa.ammos.plandev.merlin.server.models.ActivityDirectiveForValidation;
import gov.nasa.ammos.plandev.merlin.server.models.ActivityType;
import gov.nasa.ammos.plandev.merlin.server.models.MissionModelFile;
import gov.nasa.ammos.plandev.merlin.server.services.MissionModelService.NoSuchMissionModelException;
import gov.nasa.ammos.plandev.merlin.server.services.MissionModelService.BulkArgumentValidationResponse;
import gov.nasa.ammos.plandev.types.MissionModelId;
import org.apache.commons.lang3.tuple.Pair;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public interface MissionModelRepository {
    // Queries
    Path getUploadedFilePath(int uploadedFileId) throws SQLException, NoSuchFileException;
    Map<MissionModelId, MissionModelFile> getAllMissionModels(final Path missionModelDataPath);
    MissionModelFile getMissionModel(MissionModelId id, Path missionModelDataPath) throws NoSuchMissionModelException;
    Map<String, ActivityType> getActivityTypes(MissionModelId missionModelId) throws NoSuchMissionModelException;

    // Mutations
    void updateModelParameters(MissionModelId missionModelId, final List<Parameter> modelParameters) throws NoSuchMissionModelException;
    void updateActivityTypes(MissionModelId missionModelId, final Map<String, ActivityType> activityTypes, final List<String> subsystems) throws NoSuchMissionModelException;
    void updateResourceTypes(MissionModelId missionModelId, final Map<String, ValueSchema> resourceTypes) throws NoSuchMissionModelException;
    Map<MissionModelId, List<ActivityDirectiveForValidation>> getUnvalidatedDirectives();
    void updateDirectiveValidations(List<Pair<ActivityDirectiveForValidation, BulkArgumentValidationResponse>> updates);
}
