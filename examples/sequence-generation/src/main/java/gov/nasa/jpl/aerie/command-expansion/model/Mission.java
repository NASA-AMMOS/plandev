package gov.nasa.jpl.aerie.command_expansion.model;

import gov.nasa.jpl.aerie.command_expansion.Configuration;
import gov.nasa.jpl.aerie.command_expansion.model.fsw.GlobalVariables;
import gov.nasa.jpl.aerie.command_expansion.model.sequencing.SequencingModel;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.Registrar;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.Unstructured;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.UnstructuredResources;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.time.Instant;

public final class Mission {
    // Small hack to always have the current time available as an Instant.
    // Really, this should be handled in a more structured way to deal with different time systems...
    public final Resource<Unstructured<Instant>> clock;

    public final PowerModel power;
    public final SequencingModel sequencing;
    public final GlobalVariables globals;

    public Mission(final gov.nasa.jpl.aerie.merlin.framework.Registrar registrar$, Instant planStart, final Configuration config) {
        var registrar = new Registrar(registrar$, Registrar.ErrorBehavior.Log);

        clock = UnstructuredResources.timeBased(simTime -> Duration.addToInstant(planStart, simTime));

        power = new PowerModel(registrar);
        sequencing = new SequencingModel(registrar, this);
        globals = new GlobalVariables(registrar);
    }
}
