## 2025-02-18 - [Command Injection via execSync in id-lookups.ts]
**Vulnerability:** Found `execSync` being used with unsanitized inputs (`groupName` and `username`) interpolated into shell commands. This could lead to a severe command injection vulnerability if user-controllable input reaches this function.
**Learning:** Shell interpolation allows special characters to be interpreted maliciously. Relying on simple string concatenation for dynamic commands should be strictly avoided in Node.
**Prevention:** Use `execFileSync` or `spawn` instead, passing executable path and arguments as an array to avoid executing through a shell interpreter (`/bin/sh`).
