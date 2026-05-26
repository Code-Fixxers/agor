## 2025-02-26 - [Command Injection via String Interpolation in Unix Commands]
**Vulnerability:** The `unixUserExists` function in `@agor/core/unix/user-manager.ts` used `execSync` with a string interpolated `username` variable directly passed to a shell (`/bin/sh`). This allowed a malicious or unexpected username input to execute arbitrary commands.
**Learning:** Functions that accept dynamic inputs like usernames, paths, or variables must never use shell-interpreted execution (`execSync`, `exec`) directly without proper argument splitting and parameterization.
**Prevention:** Always use `execFileSync` or `execFile` with an array of arguments, utilizing the `--` separator to signify the end of command options and the beginning of raw inputs.
