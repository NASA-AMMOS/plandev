package gov.nasa.ammos.plandev.scheduler.worker;

import gov.nasa.ammos.plandev.scheduler.server.remotes.ResultsCellRepository;
import gov.nasa.ammos.plandev.scheduler.server.remotes.SpecificationRepository;

public record Stores(SpecificationRepository specifications, ResultsCellRepository results) { }
