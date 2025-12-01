import winston from 'winston';
import { getEnv } from '../env.js';

/**
 * Creates a Winston logger.
 *
 * The format is meant to match the default format of SLF4J's simple logger
 * as closely as possible to keep it consistent with the logs of other
 * PlanDev packages. For this reason it is ideal to create one logger per file,
 * so the logs can specify which file they came from (similar to SLF4J's format
 * which includes the class's full path in the message).
 *
 * The logger will print a full stack trace when an error object is logged.
 *
 * @param where path of the file creating the logger, i.e. "main" or "packages/db/db"
 * @returns a custom winston logger
 */
export default function getLogger(where: string): winston.Logger {
  const { LOG_LEVEL, LOG_FILE } = getEnv();

  const myFormat = winston.format.printf(({ level, message, label, stack }) => {
    if (stack === undefined) {
      return `${level.toUpperCase()} ${label} - ${message}`;
    } else {
      return `${level.toUpperCase()} ${label} - ${message}\n${stack}`;
    }
  });

  let transport;
  if (LOG_FILE == 'console') {
    transport = new winston.transports.Console();
  } else {
    transport = new winston.transports.File({ filename: LOG_FILE });
  }

  return winston.createLogger({
    format: winston.format.combine(
      winston.format.label({ label: where }),
      winston.format.errors({ stack: true }),
      myFormat,
    ),
    level: LOG_LEVEL.toLowerCase(),
    transports: [transport],
  });
}
