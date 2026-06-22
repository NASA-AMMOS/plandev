package gov.nasa.jpl.aerie.workspace.server;

import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.JwkProviderBuilder;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.RSAKeyProvider;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.net.MalformedURLException;
import java.net.URI;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
    final var typeString = jwtInfo.getString("type");
    // `issuer` is canonical; `iss` is the legacy alias. Prefer `issuer` when both are present.
    final var issuer = jwtInfo.containsKey("issuer") ? jwtInfo.getString("issuer")
        : jwtInfo.containsKey("iss") ? jwtInfo.getString("iss") : null;
    final var audiences = parseAudience(jwtInfo);
    final var jwkUrl = jwtInfo.containsKey("jwk_url") ? jwtInfo.getString("jwk_url") : null;

    final Algorithm algorithm;
    if (jwkUrl != null && !jwkUrl.isBlank()) {
      // OIDC: verify against the IdP's JWKS (key resolved per-token by `kid`).
      final var keyProvider = buildJwksKeyProvider(jwkUrl);
      algorithm = switch (typeString) {
        case "RS256" -> Algorithm.RSA256(keyProvider);
        case "RS384" -> Algorithm.RSA384(keyProvider);
        case "RS512" -> Algorithm.RSA512(keyProvider);
        default -> throw new IllegalArgumentException("Unsupported JWKS/asymmetric algorithm: " + typeString);
      };
    } else {
      // JWT/SSO: verify against the shared symmetric (HMAC) key.
      final var key = jwtInfo.getString("key");
      algorithm = switch (typeString) {
        case "HS256" -> Algorithm.HMAC256(key);
        case "HS384" -> Algorithm.HMAC384(key);
        case "HS512" -> Algorithm.HMAC512(key);
        default -> throw new IllegalArgumentException("Unsupported JWT algorithm: " + typeString);
      };
    }

    final var vbuilder = JWT.require(algorithm);
    // add any specific claim validations
    if(issuer != null && !issuer.isBlank()) {
      vbuilder.withIssuer(issuer);
    }
    if(audiences != null && audiences.length > 0) {
      // withAnyOfAudience matches ANY (java-jwt's withAudience requires ALL; the jsonwebtoken side matches any).
      vbuilder.withAnyOfAudience(audiences);
    }

    verifier = vbuilder.build();
  }

  /**
   * Read the optional `audience` claim-validation config. Per the JWT spec, `aud` may be a
   * single string or an array of strings, so accept either (the gateway/jsonwebtoken side
   * does too). Returns null when unset.
   */
  private static String[] parseAudience(final JsonObject jwtInfo) {
    if (!jwtInfo.containsKey("audience")) {
      return null;
    }
    final var value = jwtInfo.get("audience");
    return switch (value.getValueType()) {
      case STRING -> new String[] { ((JsonString) value).getString() };
      case ARRAY -> ((JsonArray) value).stream().map(element -> {
        // explicit element check -> clear error instead of a raw ClassCastException
        if (element.getValueType() != JsonValue.ValueType.STRING) {
          throw new IllegalArgumentException("OIDC 'audience' array must contain only strings");
        }
        return ((JsonString) element).getString();
      }).toArray(String[]::new);
      default -> throw new IllegalArgumentException("OIDC 'audience' must be a string or an array of strings");
    };
  }

  /**
   * Build an RSA key provider backed by a remote JWKS endpoint, with bounded caching
   * and rate limiting so signing keys aren't refetched on every verification.
   */
  private static RSAKeyProvider buildJwksKeyProvider(final String jwkUrl) {
    final JwkProvider provider;
    try {
      provider = new JwkProviderBuilder(URI.create(jwkUrl).toURL())
          // Cache well above any realistic active-key count (rotation overlap / multi-realm) so
          // legitimate keys aren't evicted; rate-limit fetches so unknown-`kid` floods can't hammer
          // the IdP. A `kid` maps to one immutable key, so long caching never goes stale.
          .cached(100, 24, TimeUnit.HOURS)
          .rateLimited(10, 1, TimeUnit.MINUTES)
          .build();
    } catch (final MalformedURLException | IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid jwk_url for JWT verification: " + jwkUrl, e);
    }
    return new RSAKeyProvider() {
      @Override
      public RSAPublicKey getPublicKeyById(final String keyId) {
        try {
          // Guard the cast: a non-RSA key (e.g. EC) would otherwise throw an uncaught ClassCastException (500, not 401).
          final var publicKey = provider.get(keyId).getPublicKey();
          if (publicKey instanceof RSAPublicKey rsaPublicKey) {
            return rsaPublicKey;
          }
          throw new JWTVerificationException("JWKS signing key '" + keyId + "' is not an RSA key, cannot verify with an RSA algorithm.");
        } catch (final JwkException e) {
          throw new JWTVerificationException("Unable to fetch JWKS signing key '" + keyId + "': " + e.getMessage(), e);
        }
      }

      @Override
      public RSAPrivateKey getPrivateKey() {
        return null;
      }

      @Override
      public String getPrivateKeyId() {
        return null;
      }
    };
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
    final var hasuraClaims = decodedJWT.getClaim("https://hasura.io/jwt/claims").asMap();

    if (hasuraClaims == null) {
      throw new JWTVerificationException("Missing hasura claims in JWT.");
    }

    // Resolve the user's identity. Gateway-minted tokens (JWT/SSO modes) carry a top-level
    // `username` claim; OIDC tokens (e.g. Keycloak) instead carry it as `x-hasura-user-id`
    // within the Hasura claims namespace. Accept either, mirroring how the gateway derives
    // identity (see aerie-gateway session(): namespace[x-hasura-user-id]).
    var username = decodedJWT.getClaim("username").asString();
    if (username == null || username.isBlank()) {
      // Coerce defensively — Hasura requires string session vars, but an IdP could emit a non-string.
      final var hasuraUserId = hasuraClaims.get("x-hasura-user-id");
      if (hasuraUserId != null) {
        username = String.valueOf(hasuraUserId);
      }
    }

    if (username == null || username.isBlank()) {
      throw new JWTVerificationException("Missing or invalid username in JWT.");
    }

    // Validate the active role, if present
    if(activeRole != null && !activeRole.isBlank()) {
      // Confirmed via runtime inspection that this String Array in the token is stored as an ArrayList in the Map
      @SuppressWarnings("unchecked")
      final var allowedRoles = (List<String>) hasuraClaims.get("x-hasura-allowed-roles");
      if (allowedRoles == null || !allowedRoles.contains(activeRole)) {
        throw new JWTVerificationException("Provided active role is not in the set of permitted roles.");
      }
      return new UserSession(username, activeRole);
    }
    // Use the default role, if absent
    final String defaultRole = (String) hasuraClaims.get("x-hasura-default-role");
    if (defaultRole == null || defaultRole.isBlank()) {
      throw new JWTVerificationException("No default role found in JWT claims.");
    }
    return new UserSession(username, defaultRole);
  }
}
