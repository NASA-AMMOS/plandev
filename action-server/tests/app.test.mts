import assert from "assert";
import {test, mock} from "node:test";
import request from 'supertest';
import jwt from "jsonwebtoken";

mock.module('../src/listeners/dbListeners', {
  namedExports: { setupListeners: async () => {} }
})

mock.module('../src/threads/workerPool', {
  namedExports: { ActionWorkerPool: { setup: () => {} } }
});

const called: any[] = [];
mock.module('../src/type/actionRunner', {
  namedExports: {
    ActionRunner: {
      addActionSecret: async (id: string, secrets: Record<string, string>) => {
        console.log('mocked action runner');
        called.push({ id, secrets });
      },
    },
  },
});

const {configuration} = await import("../src/config.ts");
const { app } = await import('../src/app.ts');

test('Health check', async () => {
  const res = await request(app).get('/health');
  assert(res.status === 200);
});

const {key} = JSON.parse(configuration().HASURA_GRAPHQL_JWT_SECRET);

test('auth middleware', async () => {
  await test('should allow access with valid jwt', async () => {
    const validToken = jwt.sign({ sub: 'user-123' }, key, { algorithm: 'HS256', expiresIn: '1h' });
    const res = await request(app)
        .post('/secrets')
        .send({action_run_id: 1, secrets: {}})
        .set('Authorization', `Bearer ${validToken}`);

    assert.equal(res.status, 200);
  });

  await test('should reject request with invalid jwt', async () => {
    // mock console.error so we don't log confusing-but-expected error message
    const spy = mock.method(console, 'error', () => {});
    const res = await request(app)
        .post('/secrets')
        .send({action_run_id: 1, secrets: {}})
        .set('Authorization', 'Bearer not-a-token');

    assert.equal(res.status, 401);
    spy.mock.restore();
  });

  await test('should reject expired token', async () => {
    const expired = jwt.sign({ sub: 'user-123', exp: Math.floor(Date.now() / 1000) - 10 }, key, { algorithm: 'HS256' });

    // mock console.error so we don't log confusing-but-expected error message
    const spy = mock.method(console, 'error', () => {});
    const res = await request(app)
        .post('/secrets')
        .send({action_run_id: 1, secrets: {}})
        .set('Authorization', `Bearer ${expired}`);
    spy.mock.restore();

    assert.equal(res.status, 401);
  });
});
