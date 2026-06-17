import { Request } from 'express';
import { configuration } from "../config";
import jwt, {Algorithm, JwtHeader} from "jsonwebtoken";
import { JwksClient } from "jwks-rsa";
import { parseCookie } from "cookie";

export type JsonWebToken = string;

export type JwtDecode = {
  jwtErrorMessage: string;
  jwtPayload: JwtPayload | null;
};

export type JwtPayload = {
  'https://hasura.io/jwt/claims': Record<string, string | string[]>;
  username: string;
};

export type JwtSecret = {
  // Symmetric key (JWT/SSO modes). Absent when verifying OIDC tokens via JWKS.
  key?: string;
  type: string;
  // OIDC: URL of the IdP's published signing keys. When set (and `key` is absent),
  // tokens are verified against the JWKS instead of a shared secret.
  jwk_url?: string;
  // Optional claim validation, used with JWKS/OIDC.
  issuer?: string;
  audience?: string | string[];
};

export type AuthResponse = {
  message: string;
  success: boolean;
  token: JsonWebToken | null;
};

export type SessionResponse = {
  message: string;
  success: boolean;
};

export type UserResponse = {
  message: string;
  success: boolean;
  user: User | null;
};

export type UserId = string;

export type User = {
  id: UserId;
};

export type ValidateResponse = {
  success: boolean;
  message: string;
  userId?: string;
  token?: string;
  redirectURL?: string;
};

export interface AuthAdapter {
  validate(req: Request): Promise<ValidateResponse>;
  logout(req: Request): Promise<boolean>;
}

export type GroupRoleMapping = { [key: string]: string[] };

export type UserRoles = {
  default_role: string;
  allowed_roles: string[];
};


/**
 * Extracts specified cookies from a cookie header string.
 * Returns a record of cookie name to cookie value for matching cookies found in the header.
 */
export function extractCookies(cookieHeader: string, cookieNames: string[]): Record<string, string> {
  if (!cookieHeader || cookieNames.length === 0) return {};

  const parsedCookies = parseCookie(cookieHeader);
  const selectedCookies: Record<string,string> = {};

  for (const name of cookieNames) {
    if (parsedCookies[name] !== undefined) {
      selectedCookies[name] = parsedCookies[name];
    }
  }
  return selectedCookies;
}

export function authorizationHeaderToToken(authorizationHeader: string | undefined | null): JsonWebToken | never {
  if (authorizationHeader !== null && authorizationHeader !== undefined) {
    if (authorizationHeader.startsWith('Bearer ')) {
      const [, token] = authorizationHeader.split(' '); // Split out 'Bearer' prefix.
      return token;
    } else {
      throw new Error(`Authorization header does not include 'Bearer' prefix`);
    }
  } else {
    throw new Error(`Authorization header not found`);
  }
}

// Lazily created JWKS client (OIDC). Cached across requests so signing keys are
// fetched from the IdP once and reused, rather than on every token verification.
let _jwksClient: JwksClient | undefined;
function getJwksClient(jwkUrl: string): JwksClient {
  if (!_jwksClient) {
    _jwksClient = new JwksClient({
      jwksUri: jwkUrl,
      // Cache fetched signing keys (a `kid` maps to one immutable key, so long caching never
      // goes stale) and rate-limit fetches so a flood of tokens carrying unknown `kid`s can't
      // hammer the IdP's JWKS endpoint. Not configurable by design — these bounds suit any
      // OIDC deployment and mirror the workspace server's JwkProvider settings.
      cache: true,
      cacheMaxAge: 24 * 60 * 60 * 1000, // 24h
      cacheMaxEntries: 10,
      jwksRequestsPerMinute: 10,
      rateLimit: true,
      timeout: 30000,
    });
  }
  return _jwksClient;
}

/**
 * Verify a token against an IdP's JWKS (asymmetric, e.g. RS256). The signing key
 * is resolved by the token's `kid` header and cached by the JWKS client.
 */
function verifyWithJwks(token: string, jwkUrl: string, options: jwt.VerifyOptions): Promise<JwtPayload> {
  const client = getJwksClient(jwkUrl);
  const getKey = (header: JwtHeader, callback: (err: Error | null, key?: string) => void) => {
    client.getSigningKey(header.kid, (err, signingKey) => {
      if (err) {
        callback(err);
      } else {
        callback(null, signingKey?.getPublicKey());
      }
    });
  };
  return new Promise((resolve, reject) => {
    jwt.verify(token, getKey, options, (err, decoded) => {
      if (err) {
        reject(err);
      } else {
        resolve(decoded as JwtPayload);
      }
    });
  });
}

export async function decodeJwt(authorizationHeader: string | undefined): Promise<JwtDecode> {
  try {
    const token = authorizationHeaderToToken(authorizationHeader);
    const { HASURA_GRAPHQL_JWT_SECRET } = configuration();
    const { key, type, jwk_url, issuer, audience }: JwtSecret = JSON.parse(HASURA_GRAPHQL_JWT_SECRET);
    if(!type) {
      throw new Error(`HASURA_GRAPHQL_JWT_SECRET must specify a 'type' field that is a valid JWT algorithm`)
    }
    const options: jwt.VerifyOptions = { algorithms: [type as Algorithm] };
    // Optional claim validation (used with JWKS/OIDC).
    if (issuer) {
      options.issuer = issuer;
    }
    if (audience) {
      options.audience = audience;
    }

    let jwtPayload: JwtPayload;
    if (!key && jwk_url) {
      // OIDC: verify against the IdP's published signing keys.
      jwtPayload = await verifyWithJwks(token, jwk_url, options);
    } else {
      // JWT/SSO: verify against the shared symmetric key.
      jwtPayload = jwt.verify(token, key as string, options) as JwtPayload;
    }
    return { jwtErrorMessage: '', jwtPayload };
  } catch (e) {
    console.error(e);

    if (e instanceof jwt.TokenExpiredError) {
      const tokenExpiredError = e as jwt.TokenExpiredError;
      const jwtErrorMessage = `Token expired on ${tokenExpiredError.expiredAt}`;
      return { jwtErrorMessage, jwtPayload: null };
    } else {
      const error = e as Error;
      const jwtErrorMessage = error?.message ?? 'Token could not be verified';
      return { jwtErrorMessage, jwtPayload: null };
    }
  }
}
