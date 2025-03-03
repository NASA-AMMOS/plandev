import {ErrorRequestHandler, RequestHandler} from "express";

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

// temporary CORS middleware to allow access from all origins
// TODO: set more strict CORS rules
export const corsMiddleware: RequestHandler = (req, res, next)  => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept");
  next();
};
