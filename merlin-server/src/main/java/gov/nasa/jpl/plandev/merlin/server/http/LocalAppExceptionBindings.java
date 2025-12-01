package gov.nasa.jpl.plandev.merlin.server.http;

import gov.nasa.jpl.plandev.merlin.server.services.LocalMissionModelService;
import io.javalin.Javalin;
import io.javalin.plugin.Plugin;


public final class LocalAppExceptionBindings implements Plugin {
    @Override
    public void apply(final Javalin javalin) {
        javalin.exception(LocalMissionModelService.MissionModelLoadException.class, (ex, ctx) -> ctx
            .status(500)
            .result(ResponseSerializers.serializeMissionModelLoadException(ex).toString())
            .contentType("application/json"));
    }
}
