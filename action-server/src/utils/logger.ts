import { createLogger, format, transports } from "winston";

const logger = createLogger({
  level: "info",
  transports: [
    new transports.Console({
      format: format.combine(
        format.timestamp(),
        format.printf(({ level, message, timestamp }) => {
          return `${timestamp} [${level.toUpperCase()}] ${message}`;
        }),
      ),
    }),
  ],
});

export default logger;
