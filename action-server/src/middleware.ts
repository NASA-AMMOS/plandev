import { ErrorRequestHandler, RequestHandler, NextFunction, Request, Response } from "express";
import { configuration } from "./config";
import {decodeJwt} from "./utils/auth";

// custom error handling middleware so we always return a JSON object for errors
export const jsonErrorMiddleware: ErrorRequestHandler = (err, req, res, next) => {
  res.status(err.status || 500).json({
    error: {
      message: err.message,
      stack: err.stack,
      cause: err.cause,
    },
  });
};

export const corsMiddleware: RequestHandler = (req, res, next) => {
  const { ACTION_CORS_ALLOWED_ORIGIN } = configuration();

  if (ACTION_CORS_ALLOWED_ORIGIN) {
    // Explicit origin configured: strict CORS with credentials support
    res.setHeader("Access-Control-Allow-Origin", ACTION_CORS_ALLOWED_ORIGIN);
    res.setHeader("Access-Control-Allow-Credentials", "true");
  } else {
    // No origin configured: allow access from all origins but without credentials
    res.setHeader("Access-Control-Allow-Origin", "*");
  }
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, authorization, x-hasura-role");
  next();
};


export const authMiddleware: RequestHandler = async (req, res, next) => {
  const authorizationHeader = req.get('authorization');
  const userRoleHeader = req.get('x-hasura-role');
  const { jwtErrorMessage, jwtPayload } = await decodeJwt(authorizationHeader);
  if (jwtPayload) {
    // token is valid
    // set jwt payload on `user` local, so other things can access it
    res.locals.authorization = authorizationHeader;
    res.locals.user = jwtPayload;
    res.locals.userRole = userRoleHeader;
    next();
  } else {
    // decodeJwt returns a curated message (no internals) and logs the detail server-side.
    res.status(401).send({ message: jwtErrorMessage, success: false });
  }
};
