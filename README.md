# Code Reviewer & Impact Analyzer

A sophisticated pre-commit safety net for developers that provides conversational code reviews and deep impact analysis.

## Developer Setup

### Prerequisites
- **JDK 17 or higher**: Ensure `javac`, `jar`, and `java` are on your system PATH.
- **Git**: Required for change detection and branch identification.

### 1. Global Installation (Run Once)
Runs the build, packages the reviewer, and copies runtime assets to `C:\dev-tools\code-reviewer`.

   1.Navigate to the *tool* repository's root (the folder containing this README and `install.bat`). This script must be run from here, not from your application project.
```2.Execute:
   ```powershell
   .\install.bat
   ```

During installation the script will:
- Compile all Java sources and include any jars from `lib/` on the classpath.
- Assemble `code-reviewer.jar` and copy it, along with helper scripts/configs, to `C:\dev-tools\code-reviewer`.
- Copy `config/pmd` and bundled dependencies so runtime checks work outside the repo.
- Download PMD 7.20.0 automatically if it is missing.

After completion you can run the reviewer globally via:
```powershell
C:\dev-tools\code-reviewer\review.bat --install-hook
```

### 2. Enable in Your Project
To enable the reviewer for any repository on your machine, run the hook installation command from the repository root:

**If you are in the tool's source directory:**
```powershell
.\run-reviewer.bat --install-hook
```
### Recommended:
**If you are in another project repository (after running install.bat):**
```powershell
C:\dev-tools\code-reviewer\review.bat --install-hook
```
This installs a Git pre-commit hook that automatically analyzes your staged changes before every commit.

### 3. Manual Execution
To analyze specific files manually:
```powershell
.\run-reviewer.bat src/com/myapp/MyService.java
```
Or to check all staged files without committing:
```powershell
.\run-reviewer.bat
```

## Git Pre-commit Hook Details
- **Scope**: By default, the hook is installed locally to the current repository.
- **Hook in your repo** : C:\dev-tools\code-reviewer\review.bat --install-hook
- **Behavior**: If critical (`[!!]`) issues are found, the commit will be blocked until they are resolved.

### Unlocking or Temporarily Disabling the Hook
1. **One-off bypass (not recommended for regular use):**
   ```powershell
   git commit --no-verify
   ```
   This skips every pre-commit check for that commit only.
2. **Allow commits while still running checks:** set `block.on.must.fix=false` in your `.code-reviewer.properties` file to keep the analysis but avoid hard blocks.
3. **Remove the hook file:** delete `.git\hooks\pre-commit`. You can reinstall later with `C:\dev-tools\code-reviewer\review.bat --install-hook` (or `.
un-reviewer.bat --install-hook` inside the tool repo).


## Configuration
Customize the behavior in `.code-reviewer.properties`:
- `block.on.must.fix`: If true, critical issues will prevent the commit.
- `only.changed.lines`: Focus analysis only on your local modifications.
- `expand.changed.scope.to.method`: Broaden impact detection to entire methods.
