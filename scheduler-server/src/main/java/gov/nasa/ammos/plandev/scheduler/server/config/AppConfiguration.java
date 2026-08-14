package gov.nasa.jpl.aerie.scheduler.server.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/**
 * @param httpPort the network port on which the scheduler should listen for http requests
 * @param enableJavalinDevLogging controls the level of http access logging from javalin endpoints
 * @param merlinGraphqlURI endpoint of the merlin graphql service that should be used to fetch/store plan data
 */
//TODO: remove backdoor access to directly mounted merlinFileStore (eg via merlin endpoint for downloading mission jars)
public record AppConfiguration(
    int httpPort,
    boolean enableJavalinDevLogging,
    Store store,
    URI merlinGraphqlURI,
    String hasuraGraphQlAdminSecret
)
{
  public AppConfiguration {
    Objects.requireNonNull(store);
    Objects.requireNonNull(merlinGraphqlURI);
    //NB: ok if the merlin file store not created yet at app init; just needs to exist by first use
  }
}
