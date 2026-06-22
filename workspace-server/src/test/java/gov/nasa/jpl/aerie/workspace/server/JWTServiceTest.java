package gov.nasa.jpl.aerie.workspace.server;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import javax.json.JsonObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link JWTService}. These exercise the offline (HMAC) verification path, which is
 * enough to cover identity resolution and claim validation; the JWKS/asymmetric path shares the same
 * downstream logic but requires a live key provider, so it is out of scope here.
 */
class JWTServiceTest {

  private static final String KEY = "test-secret-key-test-secret-key";
  private static final String CLAIMS_NS = "https://hasura.io/jwt/claims";

  // ---- config builders ----

  private static JsonObject hmacConfig() {
    return Json.createObjectBuilder().add("type", "HS256").add("key", KEY).build();
  }

  private static JsonObject hmacConfigWithIssuer(final String iss) {
    return Json.createObjectBuilder().add("type", "HS256").add("key", KEY).add("iss", iss).build();
  }

  private static JsonObject hmacConfigWithAudiences(final String... audiences) {
    final var arr = Json.createArrayBuilder();
    for (final var a : audiences) {
      arr.add(a);
    }
    return Json.createObjectBuilder().add("type", "HS256").add("key", KEY).add("audience", arr).build();
  }

  private static JsonObject hmacConfigWithSingleAudience(final String audience) {
    return Json.createObjectBuilder().add("type", "HS256").add("key", KEY).add("audience", audience).build();
  }

  // ---- token builders ----

  private static Map<String, Object> hasuraClaims(final String userId, final String defaultRole, final List<String> allowedRoles) {
    final var claims = new HashMap<String, Object>();
    if (userId != null) {
      claims.put("x-hasura-user-id", userId);
    }
    if (defaultRole != null) {
      claims.put("x-hasura-default-role", defaultRole);
    }
    if (allowedRoles != null) {
      claims.put("x-hasura-allowed-roles", allowedRoles);
    }
    return claims;
  }

  private static String token(final String username, final Map<String, Object> claims) {
    return token(username, claims, builder -> {});
  }

  private static String token(final String username, final Map<String, Object> claims, final Consumer<JWTCreator.Builder> customize) {
    final var builder = JWT.create();
    if (username != null) {
      builder.withClaim("username", username);
    }
    if (claims != null) {
      builder.withClaim(CLAIMS_NS, claims);
    }
    customize.accept(builder);
    return builder.sign(Algorithm.HMAC256(KEY));
  }

  private static String bearer(final String token) {
    return "Bearer " + token;
  }

  @Nested
  class IdentityResolution {

    @Test
    void resolvesTopLevelUsernameClaim() {
      final var service = new JWTService(hmacConfig());
      final var jwt = token("alice", hasuraClaims("ignored-id", "user", List.of("user")));

      final var session = service.validateAuthorization(bearer(jwt), null);

      assertEquals("alice", session.userId());
      assertEquals("user", session.activeRole());
    }

    @Test
    void fallsBackToHasuraUserIdWhenUsernameAbsent() {
      // OIDC tokens (e.g. Keycloak) carry identity as x-hasura-user-id inside the claims namespace
      // rather than as a top-level `username` claim.
      final var service = new JWTService(hmacConfig());
      final var jwt = token(null, hasuraClaims("keycloak-sub-123", "user", List.of("user")));

      final var session = service.validateAuthorization(bearer(jwt), null);

      assertEquals("keycloak-sub-123", session.userId());
    }

    @Test
    void throwsWhenNeitherUsernameNorHasuraUserIdPresent() {
      final var service = new JWTService(hmacConfig());
      final var jwt = token(null, hasuraClaims(null, "user", List.of("user")));

      assertThrows(JWTVerificationException.class, () -> service.validateAuthorization(bearer(jwt), null));
    }

    @Test
    void throwsWhenHasuraClaimsNamespaceMissing() {
      final var service = new JWTService(hmacConfig());
      final var jwt = token("alice", null);

      assertThrows(JWTVerificationException.class, () -> service.validateAuthorization(bearer(jwt), null));
    }
  }

  @Nested
  class RoleValidation {

    @Test
    void acceptsActiveRoleWhenInAllowedRoles() {
      final var service = new JWTService(hmacConfig());
      final var jwt = token("alice", hasuraClaims("alice", "user", List.of("user", "admin")));

      final var session = service.validateAuthorization(bearer(jwt), "admin");

      assertEquals("admin", session.activeRole());
    }

    @Test
    void rejectsActiveRoleNotInAllowedRoles() {
      final var service = new JWTService(hmacConfig());
      final var jwt = token("alice", hasuraClaims("alice", "user", List.of("user")));

      assertThrows(JWTVerificationException.class, () -> service.validateAuthorization(bearer(jwt), "admin"));
    }

    @Test
    void fallsBackToDefaultRoleWhenNoActiveRole() {
      final var service = new JWTService(hmacConfig());
      final var jwt = token("alice", hasuraClaims("alice", "viewer", List.of("viewer")));

      final var session = service.validateAuthorization(bearer(jwt), null);

      assertEquals("viewer", session.activeRole());
    }

    @Test
    void throwsWhenNoActiveRoleAndNoDefaultRole() {
      final var service = new JWTService(hmacConfig());
      final var jwt = token("alice", hasuraClaims("alice", null, null));

      assertThrows(JWTVerificationException.class, () -> service.validateAuthorization(bearer(jwt), null));
    }
  }

  @Nested
  class IssuerValidation {

    @Test
    void acceptsMatchingIssuer() {
      final var service = new JWTService(hmacConfigWithIssuer("https://idp.example.com"));
      final var jwt = token("alice", hasuraClaims("alice", "user", List.of("user")),
                            b -> b.withIssuer("https://idp.example.com"));

      assertEquals("alice", service.validateAuthorization(bearer(jwt), null).userId());
    }

    @Test
    void rejectsMismatchedIssuer() {
      final var service = new JWTService(hmacConfigWithIssuer("https://idp.example.com"));
      final var jwt = token("alice", hasuraClaims("alice", "user", List.of("user")),
                            b -> b.withIssuer("https://attacker.example.com"));

      assertThrows(JWTVerificationException.class, () -> service.validateAuthorization(bearer(jwt), null));
    }
  }

  @Nested
  class AudienceValidation {

    @Test
    void acceptsTokenMatchingAnyConfiguredAudience() {
      // Configured with two audiences; a token carrying only one of them must be accepted (ANY-of
      // semantics), matching the action-server/gateway (jsonwebtoken) behavior for an audience array.
      final var service = new JWTService(hmacConfigWithAudiences("aerie", "workspace"));
      final var jwt = token("alice", hasuraClaims("alice", "user", List.of("user")),
                            b -> b.withAudience("aerie"));

      assertEquals("alice", service.validateAuthorization(bearer(jwt), null).userId());
    }

    @Test
    void rejectsTokenMatchingNoConfiguredAudience() {
      final var service = new JWTService(hmacConfigWithAudiences("aerie", "workspace"));
      final var jwt = token("alice", hasuraClaims("alice", "user", List.of("user")),
                            b -> b.withAudience("someone-else"));

      assertThrows(JWTVerificationException.class, () -> service.validateAuthorization(bearer(jwt), null));
    }

    @Test
    void acceptsTokenForSingleConfiguredAudience() {
      final var service = new JWTService(hmacConfigWithSingleAudience("aerie"));
      final var jwt = token("alice", hasuraClaims("alice", "user", List.of("user")),
                            b -> b.withAudience("aerie"));

      assertEquals("alice", service.validateAuthorization(bearer(jwt), null).userId());
    }
  }
}
