package gov.nasa.ammos.plandev.merlin.worker;

import gov.nasa.ammos.plandev.merlin.server.remotes.MissionModelRepository;
import gov.nasa.ammos.plandev.merlin.server.remotes.PlanRepository;
import gov.nasa.ammos.plandev.merlin.server.remotes.ResultsCellRepository;

public record Stores (PlanRepository plans, MissionModelRepository missionModels, ResultsCellRepository results){}
