package gov.nasa.jpl.plandev.merlin.worker;

import gov.nasa.jpl.plandev.merlin.server.remotes.MissionModelRepository;
import gov.nasa.jpl.plandev.merlin.server.remotes.PlanRepository;
import gov.nasa.jpl.plandev.merlin.server.remotes.ResultsCellRepository;

public record Stores (PlanRepository plans, MissionModelRepository missionModels, ResultsCellRepository results){}
