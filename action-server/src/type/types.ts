export type ActionResponse = {
  results: ActionResults, /** should not be any but a type defined by the API*/
  console: {
    log : string[],
    debug: string[],
    info: string[],
    error: string[]
    warn: string[],
  }
  errors : {
    stacktrace : string | undefined,
    message : string,
  } | null // TODO: should this be an error array
}

export type ActionResults = any

