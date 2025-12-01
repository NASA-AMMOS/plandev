package gov.nasa.jpl.plandev.merlin.driver.engine;

import gov.nasa.jpl.plandev.types.SerializedActivity;
import gov.nasa.jpl.plandev.types.ActivityDirectiveId;

import java.util.List;
import java.util.Optional;

public record DirectiveDetail(Optional<ActivityDirectiveId> directiveId, List<SerializedActivity> activityStackTrace) {}
