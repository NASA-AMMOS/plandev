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
  // symmetric key (HMAC); absent for JWKS/OIDC
  key?: string;
  type: string;
  // IdP JWKS endpoint (asymmetric/OIDC)
  jwk_url?: string;
  // `iss` is the legacy alias for issuer
  issuer?: string;
  iss?: string;
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

// Parsed + memoized view of HASURA_GRAPHQL_JWT_SECRET (static config, parsed once).
type VerificationConfig = {
  algorithm: Algorithm;
  audience?: string | string[];
  issuer?: string;
  jwkUrl?: string;
  key?: string;
};

let _config: VerificationConfig | undefined;
function getVerificationConfig(): VerificationConfig {
  if (_config) {
    return _config;
  }
  const { HASURA_GRAPHQL_JWT_SECRET } = configuration();
  const parsed: JwtSecret = JSON.parse(HASURA_GRAPHQL_JWT_SECRET);
  const { key, type, jwk_url } = parsed;
  // accept the legacy `iss` alias
  const issuer = parsed.issuer ?? parsed.iss;

  if (!type) {
    throw new Error("HASURA_GRAPHQL_JWT_SECRET must specify a 'type' field that is a valid JWT algorithm");
  }
  // exactly one of key (HMAC) or jwk_url (JWKS)
  if (!!key === !!jwk_url) {
    throw new Error(`HASURA_GRAPHQL_JWT_SECRET must specify exactly one of 'key' or 'jwk_url' (got ${key ? 'both' : 'neither'})`);
  }
  // algorithm family must match the mode (HS* = symmetric key, RS*/ES*/PS* = JWKS)
  const symmetric = type.startsWith('HS');
  if (symmetric && jwk_url) {
    throw new Error(`HMAC algorithm '${type}' requires 'key', not 'jwk_url'`);
  }
  if (!symmetric && key) {
    throw new Error(`Asymmetric algorithm '${type}' requires 'jwk_url', not 'key'`);
  }

  _config = { algorithm: type as Algorithm, audience: parsed.audience, issuer, jwkUrl: jwk_url, key };
  return _config;
}

// Memoized JWKS client (OIDC): cache signing keys and rate-limit fetches so unknown-`kid` floods
// can't hammer the IdP. Bounds mirror the workspace server's JwkProvider.
let _jwksClient: JwksClient | undefined;
function getJwksClient(): JwksClient {
  if (!_jwksClient) {
    _jwksClient = new JwksClient({
      cache: true,
      cacheMaxAge: 24 * 60 * 60 * 1000, // 24h
      cacheMaxEntries: 100,
      jwksRequestsPerMinute: 10,
      jwksUri: getVerificationConfig().jwkUrl as string,
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
function verifyWithJwks(token: string, options: jwt.VerifyOptions): Promise<JwtPayload> {
  const client = getJwksClient();
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
    const { algorithm, audience, issuer, jwkUrl, key } = getVerificationConfig();

    const options: jwt.VerifyOptions = { algorithms: [algorithm] };
    // Optional claim validation (used with JWKS/OIDC).
    if (issuer) {
      options.issuer = issuer;
    }
    if (audience) {
      options.audience = audience;
    }

    const jwtPayload: JwtPayload = jwkUrl
      ? await verifyWithJwks(token, options)
      : (jwt.verify(token, key as string, options) as JwtPayload);

    // Require the Hasura claims namespace (matches the workspace server).
    const hasuraClaims = jwtPayload['https://hasura.io/jwt/claims'] as Record<string, unknown> | undefined;
    if (!hasuraClaims || typeof hasuraClaims !== 'object') {
      throw new Error('JWT is missing the Hasura claims namespace');
    }

    // OIDC tokens carry identity as x-hasura-user-id in the namespace, not a top-level `username`.
    if (!jwtPayload.username) {
      const hasuraUserId = hasuraClaims['x-hasura-user-id'];
      if (hasuraUserId !== undefined && hasuraUserId !== null) {
        jwtPayload.username = String(hasuraUserId);
      }
    }

    return { jwtErrorMessage: '', jwtPayload };
  } catch (error) {
    console.error(error);

    if (error instanceof jwt.TokenExpiredError) {
      const jwtErrorMessage = `Token expired on ${error.expiredAt.toISOString()}`;
      return { jwtErrorMessage, jwtPayload: null };
    } else {
      // Curated message — don't echo raw library text (leaks expected issuer/audience). Logged above.
      const jwtErrorMessage = 'Invalid authorization token';
      return { jwtErrorMessage, jwtPayload: null };
    }
  }
}
