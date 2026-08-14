package gov.nasa.jpl.aerie.merlin.driver.engine;

import gov.nasa.jpl.aerie.types.SerializedActivity;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;

import java.util.List;
import java.util.Optional;

public record DirectiveDetail(Optional<ActivityDirectiveId> directiveId, List<SerializedActivity> activityStackTrace) {}
