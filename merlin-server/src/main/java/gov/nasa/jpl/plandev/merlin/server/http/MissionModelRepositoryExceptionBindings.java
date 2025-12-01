package gov.nasa.jpl.plandev.merlin.server.http;

import gov.nasa.jpl.plandev.merlin.server.remotes.MissionModelAccessException;
import io.javalin.Javalin;
import io.javalin.plugin.Plugin;

public final class MissionModelRepositoryExceptionBindings implements Plugin {
    @Override
    public void apply(final Javalin javalin) {
        javalin.exception(MissionModelAccessException.class, (ex, ctx) -> ctx
            .status(500)
            .result(ResponseSerializers.serializeMissionModelAccessException(ex).toString())
            .contentType("application/json"));
    }
}
