import type DataLoader from 'dataloader';

export type ThenArg<T> = T extends Promise<infer U> ? U : T extends PromiseLike<infer U> ? U : T;

export type InferredParameters<T extends (opts: any) => (keys: readonly any[]) => Promise<(any | Error)[]>> =
  Parameters<ReturnType<T>>[number][number];
export type InferredReturnType<T extends (opts: any) => (keys: readonly any[]) => Promise<(any | Error)[]>> = Exclude<
  ThenArg<ReturnType<ReturnType<T>>>[number],
  Error
>;
export type InferredDataloader<T extends (opts: any) => (keys: readonly any[]) => Promise<(any | Error)[]>> =
  DataLoader<InferredParameters<T>, InferredReturnType<T>>;

export function objectCacheKeyFunction(keyObj: { [key: string]: string | number }): string {
  return <string>Object.values(keyObj).reduce((accum: string, prop: string | number) => `${accum}:${prop}`, '');
}

export type BatchLoader<K, V, Options extends { [key: string]: any } = {}> = (
  opts: Options,
) => (keys: readonly K[]) => Promise<(V | Error)[]>;

export function unwrapPromiseSettledResults<T>(promiseSettledResults: PromiseSettledResult<T>[]): (T | Error)[] {
  return promiseSettledResults.map(promiseSettledResult =>
    promiseSettledResult.status === 'fulfilled' ? promiseSettledResult.value : promiseSettledResult.reason,
  );
}
