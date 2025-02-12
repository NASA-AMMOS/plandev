import express from 'express';
import { configuration } from './config'
import * as vm from 'vm'

const app = express();

// Middleware for parsing JSON bodies
app.use(express.json());

// Simple route to test the server
app.get('/', (req, res) => {
  res.send('Hello TypeScript Express Server!');
});

const port = configuration().PORT;

app.listen(port, () => {
  console.debug(`Server running on port ${port}`);
});
