import test from "node:test";
import assert from "assert";
import { jsExecute } from "../src/utils/codeRunner";
import { PoolClient } from "pg";
import { ActionResponse } from "../src/type/types";

test("Code Runner jsRunner Tests", async () => {
  await test("capture console output", async () => {
    const code = `function main(actionParameters, actionSettings, ActionAPI) {
      console.log('Hello World');
      console.debug('Debug message');
      console.info('Info message');
      console.warn('Warning message');
      console.error('Error message');
    }
  `;

    const result: ActionResponse = await jsExecute(code, {}, {}, undefined, {} as PoolClient, 1);
    assert.strictEqual(result.console.length, 5);
    assert.ok(result.console[0].includes("[INFO] Hello World"));
    assert.ok(result.console[1].includes("[DEBUG] Debug message"));
    assert.ok(result.console[2].includes("[INFO] Info message"));
    assert.ok(result.console[3].includes("[WARN] Warning message"));
    assert.ok(result.console[4].includes("[ERROR] Error message"));
  });

  await test("run basic action", async () => {
    const code = `function main(actionParameters, actionSettings, ActionAPI) {
      let x = 10;
      x += 5;
      return x;
    }`;

    const result: ActionResponse = await jsExecute(code, {}, {}, undefined, {} as PoolClient, 1);

    assert.strictEqual(result.errors, null);
    assert.strictEqual(result.console.length, 0);

    assert.strictEqual(result.results, 15);
  });

  await test("run basic parameter", async () => {
    const code = `function main(actionParameters, actionSettings, ActionAPI) {
      return "sequenceId: "+actionParameters.sequenceId + ", boolean:" + actionParameters.myBool;
    }`;

    const parameters = {
      sequenceId: "test0001",
      myBool: false,
    };

    const result: ActionResponse = await jsExecute(code, parameters, {}, undefined, {} as PoolClient, 1);

    // Check for successful execution (no errors)
    assert.strictEqual(result.errors, null);
    assert.strictEqual(result.console.length, 0);

    assert.strictEqual(result.results, "sequenceId: test0001, boolean:false");
  });

  await test("run basic settings", async () => {
    const code = `function main(actionParameters, actionSettings, ActionAPI) {
      return "externalUrl: "+actionSettings.externalUrl + ", retries:" + actionSettings.retries;
    }`;

    const setting = {
      externalUrl: "https://www.google.com",
      retries: 5,
    };

    const result: ActionResponse = await jsExecute(code, {}, setting, undefined, {} as PoolClient, 1);

    // Check for successful execution (no errors)
    assert.strictEqual(result.errors, null);
    assert.strictEqual(result.console.length, 0);
    assert.strictEqual(result.results, "externalUrl: https://www.google.com, retries:5");
  });

  await test("run async JS code and function calling", async () => {
    const code = `async function main(actionParameters, actionSettings, ActionAPI) {
      return await delayAndReturn("hello world delay", 2000);
    }

    async function delayAndReturn(value, delay) {
      return new Promise((resolve) => {
        setTimeout(() => {
          resolve(value);
        }, delay);
      });
    }
    `;

    const result: ActionResponse = await jsExecute(code, {}, {}, undefined, {} as PoolClient, 1);
    assert.strictEqual(result.errors, null);
    assert.strictEqual(result.console.length, 0);

    assert.strictEqual(result.results, "hello world delay");
  });

  await test("run url fetching", async () => {
    const code = `async function main(actionParameters, actionSettings, ActionAPI) {
      const startTime = performance.now();
        const result = await fetch(actionSettings.externalUrl, {
          method: "get",
          headers: {
            "Content-Type": "application/json",
          },
        });
       const elapsedTime = performance.now() - startTime;
       console.log("request took "+elapsedTime+" ms");
    }`;

    const setting = {
      externalUrl: "https://www.google.com",
      retries: 5,
    };

    const result: ActionResponse = await jsExecute(code, {}, setting, undefined, {} as PoolClient, 1);

    // Check for successful execution (no errors)
    assert.strictEqual(result.errors, null);
    assert.strictEqual(result.console.length, 1);

    assert.strictEqual(result.results, undefined);
  });

  await test("syntax error reporting", async () => {
    const code = `async function main(actionParameters, actionSettings, ActionAPI) {
      let x = z;
    }`;

    const result: ActionResponse = await jsExecute(code, {}, {}, undefined, {} as PoolClient, 1);

    // Check for successful execution (no errors)
    assert.notStrictEqual(result.errors, null);
    if (result.errors) {
      assert.strictEqual(result.errors.cause, undefined);
      assert.strictEqual(result.errors.message, "z is not defined");
      assert.notStrictEqual(result.errors.stack, null);
      if (result.errors.stack) {
        assert.ok(result.errors.stack.includes("z is not defined"));
      }
    }
    // errors are also logged in console
    assert.strictEqual(result.console.length, 2);

    assert.strictEqual(result.results, null);
  });

  await test("throw errors object", async () => {
    const code = `async function main(actionParameters, actionSettings, ActionAPI) {
      throw new Error("this is a error");
    }`;

    const result: ActionResponse = await jsExecute(code, {}, {}, undefined, {} as PoolClient, 1);

    assert.notStrictEqual(result.errors, null);
    if (result.errors) {
      assert.strictEqual(result.errors.cause, undefined);
      assert.strictEqual(result.errors.message, "this is a error");
      assert.notStrictEqual(result.errors.stack, null);
      if (result.errors.stack) {
        assert.ok(result.errors.stack.includes("this is a error"));
      }
    }
  });

  await test("throw errors", async () => {
    const code = `async function main(actionParameters, actionSettings, ActionAPI) {
      throw "this is an error string";
    }`;

    const result: ActionResponse = await jsExecute(code, {}, {}, undefined, {} as PoolClient, 1);
    assert.notStrictEqual(result.errors, null);
    if (result.errors) {
      assert.strictEqual(result.errors.cause, undefined);
      assert.strictEqual(result.errors.message, "this is an error string");
      assert.notStrictEqual(result.errors.stack, null);
      if (result.errors.stack) {
        assert.ok(result.errors.stack.includes("this is an error string"));
      }
    }
  });
});
