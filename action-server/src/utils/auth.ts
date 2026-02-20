import { Request } from 'express';
import { configuration } from "../config";
import jwt, {Algorithm} from "jsonwebtoken";

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
  key: string;
  type: string;
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
  const cookies: Record<string, string> = {};

  if (cookieNames.length > 0) {
    for (const pair of cookieHeader.split(';')) {
      const [name, ...rest] = pair.trim().split('=');
      if (cookieNames.includes(name)) {
        cookies[name] = rest.join('=');
      }
    }
  }

  return cookies;
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

export function decodeJwt(authorizationHeader: string | undefined): JwtDecode {
  try {
    const token = authorizationHeaderToToken(authorizationHeader);
    const { HASURA_GRAPHQL_JWT_SECRET } = configuration();
    const { key, type }: JwtSecret = JSON.parse(HASURA_GRAPHQL_JWT_SECRET);
    if(!type) {
      throw new Error(`HASURA_GRAPHQL_JWT_SECRET must specify a 'type' field that is a valid JWT algorithm`)
    }
    const options: jwt.VerifyOptions = { algorithms: [type as Algorithm] };
    const jwtPayload = jwt.verify(token, key, options) as JwtPayload;
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
