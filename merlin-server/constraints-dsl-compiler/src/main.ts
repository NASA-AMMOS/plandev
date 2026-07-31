import fs from 'fs';
import ts from 'typescript';
import { UserCodeRunner } from '@nasa-jpl/aerie-ts-user-code-runner';
import * as readline from 'readline';

const codeRunner = new UserCodeRunner();
const guestRuntimeModuleTypes = fs.readFileSync(
  `${process.env['CONSTRAINTS_DSL_COMPILER_ROOT']}/src/libs/guest-runtime-modules.d.ts`,
  'utf8',
);
const constraintsEDSL = fs.readFileSync(
  `${process.env['CONSTRAINTS_DSL_COMPILER_ROOT']}/src/libs/constraints-edsl-fluent-api.ts`,
  'utf8',
);
const constraintsAST = fs.readFileSync(
  `${process.env['CONSTRAINTS_DSL_COMPILER_ROOT']}/src/libs/constraints-ast.ts`,
  'utf8',
);
const temporalPolyfillTypes = fs.readFileSync(
  `${process.env['CONSTRAINTS_DSL_COMPILER_ROOT']}/src/libs/TemporalPolyfillTypes.d.ts`,
  'utf8',
);
const temporalPolyfillBundle = fs.readFileSync(
  // import the *built* version of the Temporal bundle, which has all imports resolved into a single file
  `${process.env['CONSTRAINTS_DSL_COMPILER_ROOT']}/build/libs/temporal-polyfill-bundle.js`,
  'utf8',
);
const temporalBootstrap = fs.readFileSync(
  `${process.env['CONSTRAINTS_DSL_COMPILER_ROOT']}/src/libs/temporal-bootstrap.ts`,
  'utf8',
);
const constraintResultSerializer = fs.readFileSync(
  `${process.env['CONSTRAINTS_DSL_COMPILER_ROOT']}/src/libs/constraint-result-serializer.ts`,
  'utf8',
);
const tsConfig = JSON.parse(fs.readFileSync(new URL('../tsconfig.json', import.meta.url).pathname, 'utf-8'));
const { options } = ts.parseJsonConfigFileContent(tsConfig, ts.sys, '');
const compilerTarget = options.target ?? ts.ScriptTarget.ES2021

process.on('uncaughtException', err => {
  console.error('uncaughtException');
  console.error(err && err.stack ? err.stack : err);
  process.stdout.write('panic\n' + (err.stack ?? err.message));
  process.exit(1);
});

const lineReader = readline.createInterface({
  input: process.stdin,
});
lineReader.once('line', handleRequest);

interface AstNode {
  __astNode: object
}

function toJson(unwrappedErr: any){
  var completeStackValue = "";
  if ('error' in unwrappedErr && 'stack' in unwrappedErr.error){
    completeStackValue = JSON.stringify(unwrappedErr.error.stack)
  }
  return {
    message: unwrappedErr.message,
    stack: unwrappedErr.stack,
    location: unwrappedErr.location,
    completeStack:  completeStackValue,
  }
}

async function handleRequest(data: Buffer) {
  try {
    // Test the health of the service by responding to "ping" with "pong".
    if (data.toString() === 'ping') {
      process.stdout.write('pong\n');
      lineReader.once('line', handleRequest);
      return;
    }
    const { constraintCode, missionModelGeneratedCode, expectedReturnType } = JSON.parse(data.toString()) as {
      constraintCode: string;
      missionModelGeneratedCode: string;
      expectedReturnType: string;
    };

    const additionalSourceFiles: {
      filename: string;
      contents: string;
    }[] = [
      { filename: 'guest-runtime-modules.d.ts', contents: guestRuntimeModuleTypes },
      { filename: 'temporal-polyfill-bundle.js', contents: temporalPolyfillBundle },
      { filename: 'temporal-bootstrap.ts', contents: temporalBootstrap },
      { filename: 'constraint-result-serializer.ts', contents: constraintResultSerializer },
      { filename: 'constraints-ast.ts', contents: constraintsAST },
      { filename: 'constraints-edsl-fluent-api.ts', contents: constraintsEDSL },
      { filename: 'mission-model-generated-code.ts', contents: missionModelGeneratedCode },
      { filename: 'TemporalPolyfillTypes.d.ts', contents: temporalPolyfillTypes },
    ];

    // we expect a JSON string back since the serializer runs within the guest VM
    const result = await codeRunner.executeUserCode<[], string>(
      constraintCode,
      [],
      expectedReturnType,
      [],
      10000,
      additionalSourceFiles.map(({ filename, contents }) => ts.createSourceFile(filename, contents, compilerTarget)),
      {
        memoryLimitMb: 1024,
        resultSerializer: {
          moduleName: 'constraint-result-serializer',
          outputType: 'string'
        },
      },
    );

    if (result.isErr()) {
      const secondLine = JSON.stringify(result.unwrapErr().map(err => toJson(err))) + '\n';
      process.stdout.write('error\n')
      process.stdout.write(secondLine);
      lineReader.once('line', handleRequest);
      return;
    }

    const stringResult: string = result.unwrap();
    if (stringResult === undefined) {
      throw new Error('constraint result was undefined');
    }
    process.stdout.write('success\n');
    process.stdout.write(stringResult + '\n');
  } catch (error: any) {
    process.stdout.write('panic\n');
    process.stdout.write(JSON.stringify(error.stack ?? error.message) + ' attempted to handle: ' + data.toString() + '\n');
  }
  lineReader.once('line', handleRequest);
}
