package gov.nasa.jpl.plandev.scheduler.worker;

import gov.nasa.jpl.plandev.scheduler.server.remotes.ResultsCellRepository;
import gov.nasa.jpl.plandev.scheduler.server.remotes.SpecificationRepository;

public record Stores(SpecificationRepository specifications, ResultsCellRepository results) { }
