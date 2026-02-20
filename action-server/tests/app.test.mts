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

test('cookie forwarding', async () => {
  const validToken = jwt.sign({ sub: 'user-123' }, key, { algorithm: 'HS256', expiresIn: '1h' });

  await test('should forward configured cookies as secrets', async () => {
    process.env.ACTION_COOKIE_NAMES = 'ssosession,other_cookie';
    called.length = 0; // reset array, can't re-assign since our mock has a static reference to this array

    const res = await request(app)
        .post('/secrets')
        .send({ action_run_id: 'test-run-1', secrets: { mySecret: 'value' } })
        .set('Authorization', `Bearer ${validToken}`)
        .set('Cookie', 'ssosession=token123; other_cookie=abc; unrelated=xyz');

    assert.equal(res.status, 200);
    assert.equal(called.length, 1);
    assert.equal(called[0].secrets.ssosession, 'token123');
    assert.equal(called[0].secrets.other_cookie, 'abc');
    assert.equal(called[0].secrets.unrelated, undefined);
    assert.equal(called[0].secrets.mySecret, 'value');
  });

  await test('should not forward cookies when ACTION_COOKIE_NAMES is unset', async () => {
    delete process.env.ACTION_COOKIE_NAMES;
    called.length = 0;

    const res = await request(app)
        .post('/secrets')
        .send({ action_run_id: 'test-run-2', secrets: {} })
        .set('Authorization', `Bearer ${validToken}`)
        .set('Cookie', 'ssosession=token123');

    assert.equal(res.status, 200);
    assert.equal(called.length, 1);
    assert.equal(called[0].secrets.ssosession, undefined);
  });

  await test('should handle cookies with equals signs in values', async () => {
    process.env.ACTION_COOKIE_NAMES = 'ssosession';
    called.length = 0;

    const res = await request(app)
        .post('/secrets')
        .send({ action_run_id: 'test-run-3', secrets: {} })
        .set('Authorization', `Bearer ${validToken}`)
        .set('Cookie', 'ssosession=base64value==;');

    assert.equal(res.status, 200);
    assert.equal(called.length, 1);
    assert.equal(called[0].secrets.ssosession, 'base64value==');
  });
});
