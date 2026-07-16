const fs = require('fs');
const content = fs.readFileSync('apps/agor-daemon/src/register-hooks.ts', 'utf-8');
console.log(content.includes("import { inArray } from 'drizzle-orm'"));
