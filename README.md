
# Code R

A sophisticated pre-commit safety net for developers that provides conversational code reviews and deep impact analysis.

## Developer Setup

### Prerequisites
- **JDK 17 or higher**: Ensure `javac`, `jar`, and `java` are on your system PATH.
- **Git**: Required for change detection and branch identification.

### 1. Global Installation (Run Once)
The installation script builds the tool and sets it up in a permanent global location (`C:\dev-tools\code-reviewer`).
```powershell
.\install.bat
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

## Configuration
Customize the behavior in `.code-reviewer.properties`:
- `block.on.must.fix`: If true, critical issues will prevent the commit.
- `only.changed.lines`: Focus analysis only on your local modifications.
- `expand.changed.scope.to.method`: Broaden impact detection to entire methods.
