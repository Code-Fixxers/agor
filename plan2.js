const fs = require('fs');
const content = fs.readFileSync(
  'packages/core/src/db/repositories/user-mcp-oauth-tokens.ts',
  'utf-8'
);
console.log(content.includes('inArray'));
