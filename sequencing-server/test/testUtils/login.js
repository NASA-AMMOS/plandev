import fetch from "node-fetch";

export async function loginTestUser() {
  const response = await fetch(`${process.env['MERLIN_GATEWAY_URL']}/auth/login`, {
    method: 'POST',
    body: `{"username": "AerieE2ESequencingTests", "password": "password"}`,
    headers: {'Content-Type': 'application/json'},
  });
  if (!response.ok) {
    throw new Error(`Failed to login: ${response.statusText}`);
  }
  return (await response.json()).token;
}
