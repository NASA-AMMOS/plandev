package gov.nasa.ammos.plandev.merlin.server.http;

import gov.nasa.ammos.plandev.json.JsonParser;
import gov.nasa.ammos.plandev.merlin.driver.engine.ProfileSegment;
import gov.nasa.ammos.plandev.merlin.driver.resources.ResourceProfile;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.merlin.protocol.types.RealDynamics;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.merlin.server.models.DiscreteProfile;
import gov.nasa.ammos.plandev.merlin.server.models.ProfileSet;
import gov.nasa.ammos.plandev.merlin.server.models.RealProfile;

import java.util.HashMap;
import java.util.Optional;
import java.util.function.BiFunction;

import static gov.nasa.ammos.plandev.json.BasicParsers.chooseP;
import static gov.nasa.ammos.plandev.json.BasicParsers.doubleP;
import static gov.nasa.ammos.plandev.json.BasicParsers.listP;
import static gov.nasa.ammos.plandev.json.BasicParsers.literalP;
import static gov.nasa.ammos.plandev.json.BasicParsers.mapP;
import static gov.nasa.ammos.plandev.json.BasicParsers.productP;
import static gov.nasa.ammos.plandev.json.Uncurry.tuple;
import static gov.nasa.ammos.plandev.json.Uncurry.untuple;
import static gov.nasa.ammos.plandev.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.ammos.plandev.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;
import static gov.nasa.ammos.plandev.merlin.server.http.MerlinParsers.durationP;

public final class ProfileParsers {
  public static final JsonParser<RealDynamics> realDynamicsP
      = productP
      . field("initial", doubleP)
      . field("rate", doubleP)
      . map(
          untuple(RealDynamics::linear),
          $ -> tuple($.initial, $.rate));

  public static final JsonParser<ProfileSegment<Optional<RealDynamics>>> realProfileSegmentP
      = productP
      . field("duration", durationP)
      . optionalField("dynamics", realDynamicsP)
      . map(
          untuple((BiFunction<Duration, Optional<RealDynamics>, ProfileSegment<Optional<RealDynamics>>>) ProfileSegment::new),
          $ -> tuple($.extent(), $.dynamics()));

  public static final JsonParser<ProfileSegment<Optional<SerializedValue>>> discreteProfileSegmentP
      = productP
      . field("duration", durationP)
      . optionalField("dynamics", serializedValueP)
      . map(
          untuple((BiFunction<Duration, Optional<SerializedValue>, ProfileSegment<Optional<SerializedValue>>>) ProfileSegment::new),
          $ -> tuple($.extent(), $.dynamics()));

  public static final JsonParser<RealProfile> realProfileP
      = productP
      . field("type", literalP("real"))
      . field("schema", valueSchemaP)
      . field("segments", listP(realProfileSegmentP))
      . map(
          untuple((type, schema, segments) -> new RealProfile(schema, segments)),
          $ -> tuple(null, $.schema(), $.segments()));

  public static final JsonParser<DiscreteProfile> discreteProfileP
      = productP
      . field("type", literalP("discrete"))
      . field("schema", valueSchemaP)
      . field("segments", listP(discreteProfileSegmentP))
      . map(
          untuple((type, schema, segments) -> new DiscreteProfile(schema, segments)),
          $ -> tuple(null, $.schema(), $.segments()));

  public static final JsonParser<ProfileSet> profileSetP
      = mapP(chooseP(realProfileP, discreteProfileP))
      . map(
          profiles -> {
            final var realProfiles = new HashMap<String, ResourceProfile<Optional<RealDynamics>>>();
            final var discreteProfiles = new HashMap<String, ResourceProfile<Optional<SerializedValue>>>();
            for (final var entry : profiles.entrySet()) {
              final var name = entry.getKey();
              final var profile = entry.getValue();
              if (profile instanceof RealProfile p) {
                realProfiles.put(name, ResourceProfile.of(p.schema(), p.segments()));
              } else if (profile instanceof DiscreteProfile p) {
                discreteProfiles.put(name, ResourceProfile.of(p.schema(), p.segments()));
              } else {
                // If this happens, then the parser must have been updated without updating the mapping code
                // It should not be possible to reach this point unless a new profile type is introduced and we
                // forget to update the above mapping code
                throw new Error("Parsing of Profile Set failed due to unexpected profile type");
              }
            }
            return new ProfileSet(realProfiles, discreteProfiles);
          },
          profileSet -> {
            final var profiles = new HashMap<String, Record>();
            profileSet
                .realProfiles()
                .forEach((name, profile) ->
                             profiles.put(name, new RealProfile(profile.schema(), profile.segments())));
            profileSet
                .discreteProfiles()
                .forEach((name, profile) ->
                             profiles.put(name, new DiscreteProfile(profile.schema(), profile.segments())));
            return profiles;
          }
      );
}
