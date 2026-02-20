import { ErrorRequestHandler, RequestHandler, NextFunction, Request, Response } from "express";
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
  const allowedOrigin = process.env.ACTION_CORS_ALLOWED_ORIGIN || req.headers.origin || "*";
  res.setHeader("Access-Control-Allow-Origin", allowedOrigin);
  res.setHeader("Access-Control-Allow-Credentials", "true");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, authorization, x-hasura-role");
  next();
};


export const authMiddleware: RequestHandler = async (req, res, next) => {
  const authorizationHeader = req.get('authorization');
  const userRoleHeader = req.get('x-hasura-role');
  const { jwtErrorMessage, jwtPayload } = decodeJwt(authorizationHeader);
  if (jwtPayload) {
    // token is valid
    // set jwt payload on `user` local, so other things can access it
    res.locals.authorization = authorizationHeader;
    res.locals.user = jwtPayload;
    res.locals.userRole = userRoleHeader;
    next();
  } else {
    res.status(401).send({ message: `Unauthorized: ${jwtErrorMessage}`, success: false });
  }
};
