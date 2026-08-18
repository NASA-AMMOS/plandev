package gov.nasa.ammos.plandev.merlin.driver.engine;

import gov.nasa.ammos.plandev.types.SerializedActivity;
import gov.nasa.ammos.plandev.types.ActivityDirectiveId;

import java.util.List;
import java.util.Optional;

public record DirectiveDetail(Optional<ActivityDirectiveId> directiveId, List<SerializedActivity> activityStackTrace) {}
