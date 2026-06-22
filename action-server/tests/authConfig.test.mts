import { test, mock } from "node:test";
import assert from "assert";
import jwt from "jsonwebtoken";

// Verifies the action server honors the legacy `iss` field as an issuer alias. Own file (separate
// process) because decodeJwt memoizes the parsed secret, so it must be set before the module loads.
const KEY = "test-secret-key";
const ISSUER = "https://idp.example.com";
const CLAIMS_NS = "https://hasura.io/jwt/claims";

process.env.HASURA_GRAPHQL_JWT_SECRET = JSON.stringify({ type: "HS256", key: KEY, iss: ISSUER });

const { decodeJwt } = await import("../src/utils/auth.ts");

const claims = {
  [CLAIMS_NS]: {
    "x-hasura-user-id": "u1",
    "x-hasura-default-role": "user",
    "x-hasura-allowed-roles": ["user"],
  },
};

test("issuer validation via the legacy `iss` field", async () => {
  await test("accepts a token whose issuer matches the configured iss", async () => {
    const token = jwt.sign(claims, KEY, { algorithm: "HS256", issuer: ISSUER, expiresIn: "1h" });

    const { jwtPayload, jwtErrorMessage } = await decodeJwt(`Bearer ${token}`);

    assert.equal(jwtErrorMessage, "");
    assert.ok(jwtPayload);
    assert.equal(jwtPayload.username, "u1");
  });

  await test("rejects a token whose issuer does not match (proves iss is honored)", async () => {
    // mock console.error so we don't log the confusing-but-expected verification error
    const spy = mock.method(console, "error", () => {});
    const token = jwt.sign(claims, KEY, { algorithm: "HS256", issuer: "https://attacker.example.com", expiresIn: "1h" });

    const { jwtPayload } = await decodeJwt(`Bearer ${token}`);

    spy.mock.restore();
    assert.equal(jwtPayload, null);
  });
});
