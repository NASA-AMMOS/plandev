package gov.nasa.jpl.aerie.workspace.server;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import javax.json.JsonObject;
import java.util.ArrayList;

/**
 * A service for decoding JWTs.
 */
public final class JWTService {
  private final JWTVerifier verifier;

  /**
   * A representation of a user's session stored in the JWT.
   * @param userId the user's username
   * @param activeRole the user's active role
   */
  public record UserSession(String userId, String activeRole) {}

  JWTService(final JsonObject jwtInfo) {
    final var key = jwtInfo.getString("key");
    final var typeString = jwtInfo.getString("type");
    final var issuer = jwtInfo.containsKey("iss") ? jwtInfo.getString("iss") : null;

    // Expand on this switch statement as we support more Algorithm types.
    // Currently, the Gateway only supports HMAC key variants
    final Algorithm algorithm = switch (typeString) {
      case "HS256" -> Algorithm.HMAC256(key);
      case "HS384" -> Algorithm.HMAC384(key);
      case "HS512" -> Algorithm.HMAC512(key);
      default -> Algorithm.none();
    };

    final var vbuilder = JWT.require(algorithm);
    // add any specific claim validations
    if(issuer != null && !issuer.isBlank()) {
      vbuilder.withIssuer(issuer);
    }

    verifier = vbuilder.build();
  }

  /**
   * Decode a JWT authorization header into a validated UserSession
   * @param authHeader the contents of the Authorization header
   * @param activeRole the contents of the x-hasura-role header
   * @return a UserSession representing the current user
   * @throws JWTVerificationException if there's an error during validation
   */
  public UserSession validateAuthorization(String authHeader, String activeRole) throws JWTVerificationException {
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      throw new JWTVerificationException("Invalid Authorization header provided.");
    }
    final var token = authHeader.split(" ")[1];
    final DecodedJWT decodedJWT;

    decodedJWT = verifier.verify(token);
    final var username = decodedJWT.getClaim("username").asString();
    final var hasuraClaims = decodedJWT.getClaim("https://hasura.io/jwt/claims").asMap();

    // Validate the active role, if present
    if(activeRole != null && !activeRole.isBlank()) {
      // Confirmed via runtime inspection that this String Array in the token is stored as an ArrayList in the Map
      @SuppressWarnings("unchecked")
      final var allowedRoles = (ArrayList<String>) hasuraClaims.get("x-hasura-allowed-roles");
      if (!allowedRoles.contains(activeRole)) {
        throw new JWTVerificationException("Provided active role is not in the set of permitted roles.");
      }
      return new UserSession(username, activeRole);
    }
    // Use the default role, if absent
    final String defaultRole = (String) hasuraClaims.get("x-hasura-default-role");
    return new UserSession(username, defaultRole);
  }
}
