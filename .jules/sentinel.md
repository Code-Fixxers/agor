## 2024-05-20 - [Command Injection via execSync String Interpolation]
**Vulnerability:** Found `execSync` used with string interpolation for dynamic arguments like usernames (`execSync(\`id -u "${username}"\`)`), allowing arbitrary shell execution if the username contained shell metacharacters.
**Learning:** Node's `execSync` executes within `/bin/sh` by default, making any string interpolation inherently unsafe unless inputs are strictly sanitized.
**Prevention:** Replace `execSync(commandString)` with `execFileSync(binary, [args])` to bypass the shell entirely. Additionally, prefix arguments with `--` (e.g., `['--', username]`) to prevent argument injection where user input could be parsed as a command flag.
