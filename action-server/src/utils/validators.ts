import { ActionRunRequest } from "../type/types";

const isObject = (val: any): val is Record<string, any> => val instanceof Object && !Array.isArray(val);

export const validateActionRunRequest = (body: any) => {
  if (!isObject(body)) {
    return "Invalid ActionRunRequest: request body is not an object";
  }
  if (typeof body.actionJS !== "string" || !body.actionJS.length) {
    return "Invalid ActionRunRequest: body.actionJS must be a JS string with non-zero length";
  }
  if (!isObject(body.settings)) {
    return "Invalid ActionRunRequest: body.settings must be an object";
  }
  if (!isObject(body.parameters)) {
    return "Invalid ActionRunRequest: body.parameters must be an object";
  }
  return null; // All fields are valid
};

// Type Guard
export const isActionRunRequest = (body: any): body is ActionRunRequest => {
  return validateActionRunRequest(body) === null;
};
