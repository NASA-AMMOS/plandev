import * as vm from 'node:vm';
import { ActionResponse, ActionResults } from "../type/types";

interface Console  { log : string[], debug: string[], info: string[], error: string[], warn: string[]}

export const jsExecute = async (code: string) : Promise<ActionResponse> => {

  /** Array to store console output. */
  const consoleOutput : Console = {  log : [], debug: [], info: [], error: [], warn: [] };

  /** Execute the JavaScript code here and capture the output. */
  const context = vm.createContext({
    console: {
      log: (...args : any[]) => {
        consoleOutput.log.push(args.join(' '));
      },
      debug: (...args: any[]) => {
        consoleOutput.debug.push(args.join(' '))
      },
      info: (...args: any[]) => {
        consoleOutput.info.push(args.join(' '))
      },
      warn: (...args: any[]) => {
        consoleOutput.warn.push(args.join(' '))
      },
      error: (...args: any[]) => {
        consoleOutput.error.push(args.join(' '))
      }
    },
    // actionSettings : { id: "seq1"}
  });

  try{
    vm.runInContext(code, context);
    const result = await context.main({seqID: "test1"},{ settings: ""});
    return Promise.resolve({ results: result , console: consoleOutput, errors: null});
  }catch(error : any){
    // wrap `throw 10` into a `new throw(10)`
    let errorResponse : Error;
    if( !(error !== null && typeof error === 'object') && !("message" in error  && "stack" in error) ){
      errorResponse =  new Error(String(error))
    }else{
      errorResponse = error;
    }
    return Promise.resolve( { results: null, console: consoleOutput, errors: {
        stacktrace : errorResponse.stack,
        message : errorResponse.message
      }
      }
    );
  }

  return Promise.resolve({ results: null, console: consoleOutput, errors : null });

}


