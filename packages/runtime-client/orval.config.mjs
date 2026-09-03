import { defineConfig } from 'orval';

export default defineConfig({
  runtimeClient: {
    input: './openapi/runtime-client.openapi.json',
    output: {
      target: process.env.RUNTIME_CLIENT_GENERATED_TARGET ?? './src/generated/client.ts',
      client: 'fetch',
      mode: 'single',
      clean: false,
      override: {
        mutator: {
          path: './src/transport.ts',
          name: 'runtimeFetch',
        },
      },
    },
  },
});
