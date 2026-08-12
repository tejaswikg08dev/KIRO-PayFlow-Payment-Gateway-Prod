# Phase 0 Part 2: Environment Setup — Install Everything

| Field | Details |
|-------|---------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 0 — Project Overview & Setup |
| **Part** | 2 of 2 |
| **Previous** | [Phase 0 Part 1: Project Overview](./phase0-part1-project-overview.md) |
| **Next** | [Phase 1: System Design](./phase1-system-design.md) |
| **Time to Complete** | 2-3 hours (downloading + installing + verifying) |
| **Difficulty** | Beginner |
| **Prerequisites** | Windows 10/11, internet connection, admin access |
| **What You'll Build** | A fully configured development environment |
| **Git Commit** | N/A (no project code yet) |

---

## Table of Contents
- [Overview — What We're Installing](#overview)
- [Step 1: Java 17 (JDK)](#java)
- [Step 2: Maven 3.9](#maven)
- [Step 3: Git](#git)
- [Step 4: Node.js 20](#nodejs)
- [Step 5: Docker Desktop](#docker)
- [Step 6: IntelliJ IDEA](#intellij)
- [Step 7: PostgreSQL Client (DBeaver)](#dbeaver)
- [Step 8: Postman](#postman)
- [Step 9: AWS CLI](#aws-cli)
- [Step 10: Verification Script](#verification)
- [Common Installation Errors](#common-errors)
- [Next Steps](#next-steps)

---

## What You'll Learn in This Part
- How to install and configure every tool needed for PayFlow
- How to verify each tool works correctly
- How to troubleshoot common installation problems
- How to set up IntelliJ IDEA for Spring Boot development

---

<a name="overview"></a>
## Overview — What We're Installing

| # | Tool | Why We Need It | Version |
|---|------|---------------|---------|
| 1 | Java 17 (JDK) | Compile and run all backend services | 17.x (LTS) |
| 2 | Maven 3.9 | Build multi-module Java project, manage dependencies | 3.9.x |
| 3 | Git | Version control, push code to GitHub | 2.x |
| 4 | Node.js 20 | Run React frontend (npm, Vite) | 20.x (LTS) |
| 5 | Docker Desktop | Run PostgreSQL, Redis, Kafka, all services in containers | Latest |
| 6 | IntelliJ IDEA | Java IDE (write, debug, run services) | Community (free) |
| 7 | DBeaver | Visual database client (inspect tables, run queries) | Latest |
| 8 | Postman | Test API endpoints manually | Latest |
| 9 | AWS CLI | Deploy to AWS from command line | 2.x |

**Estimated download size:** ~3-4 GB total. Make sure you have good internet.

---

<a name="java"></a>
## Step 1: Install Java 17 (JDK)

**What is JDK?** Java Development Kit — the compiler + runtime. We need JDK (not just JRE) because we compile Java code.

**Why Java 17?** It's the latest LTS (Long-Term Support) version. Spring Boot 3.x requires Java 17 minimum.

### Installation Steps

1. **Go to:** https://adoptium.net/temurin/releases/
2. **Select:**
   - Operating System: **Windows**
   - Architecture: **x64**
   - Package Type: **JDK**
   - Version: **17 — LTS**
3. **Download** the `.msi` installer (e.g., `OpenJDK17U-jdk_x64_windows_hotspot_17.0.11_9.msi`)
4. **Run the installer:**
   - Click Next
   - ✅ Check "Set JAVA_HOME variable" (IMPORTANT!)
   - ✅ Check "Add to PATH" (IMPORTANT!)
   - Click Install → Finish
5. **Restart your terminal** (close and reopen PowerShell)

### Verify Installation

Open a NEW PowerShell window and run:

```powershell
java -version
```

**Expected output:**
```
openjdk version "17.0.11" 2024-04-16
OpenJDK Runtime Environment Temurin-17.0.11+9 (build 17.0.11+9)
OpenJDK 64-Bit Server VM Temurin-17.0.11+9 (build 17.0.11+9, mixed mode, sharing)
```

Also verify the compiler:
```powershell
javac -version
```

**Expected:** `javac 17.0.11`

And verify JAVA_HOME:
```powershell
echo $env:JAVA_HOME
```

**Expected:** Something like `C:\Program Files\Eclipse Adoptium\jdk-17.0.11.9-hotspot`

### Troubleshooting

| Problem | Fix |
|---------|-----|
| `java` is not recognized | Restart PowerShell. If still fails, manually add to PATH (see below) |
| Shows Java 8 or Java 11 | You have an old Java. Uninstall it from Control Panel first |
| JAVA_HOME is empty | Set it manually: System Properties → Environment Variables → New → JAVA_HOME = `C:\Program Files\Eclipse Adoptium\jdk-17...` |

**Manual PATH fix (if auto-install didn't work):**
```powershell
# Run as Administrator
[System.Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Eclipse Adoptium\jdk-17.0.11.9-hotspot", "Machine")
[System.Environment]::SetEnvironmentVariable("Path", $env:Path + ";%JAVA_HOME%\bin", "Machine")
```

---

<a name="maven"></a>
## Step 2: Install Maven 3.9

**What is Maven?** A build tool for Java. It:
- Downloads libraries (dependencies) automatically
- Compiles your code
- Runs tests
- Packages into JAR files
- Manages multi-module projects (like our 12 modules)

**Why not Gradle?** Spring Boot supports both, but Maven is more common in enterprise Java (banks, fintech). 80% of Spring Boot projects use Maven.

### Installation Steps

1. **Go to:** https://maven.apache.org/download.cgi
2. **Download:** `apache-maven-3.9.7-bin.zip` (the Binary zip archive)
3. **Extract** to `C:\Program Files\Maven` (create the folder if needed)
   - You should have: `C:\Program Files\Maven\apache-maven-3.9.7\bin\mvn.cmd`
4. **Add to PATH:**

```powershell
# Run PowerShell as Administrator
[System.Environment]::SetEnvironmentVariable(
    "Path",
    $env:Path + ";C:\Program Files\Maven\apache-maven-3.9.7\bin",
    "Machine"
)
```

5. **Restart PowerShell** (close and reopen)

### Verify Installation

```powershell
mvn -version
```

**Expected output:**
```
Apache Maven 3.9.7 (...)
Maven home: C:\Program Files\Maven\apache-maven-3.9.7
Java version: 17.0.11, vendor: Eclipse Adoptium
Default locale: en_US, platform encoding: UTF-8
OS name: "windows 10", version: "10.0", arch: "amd64"
```

**Key check:** It should show Java version **17** (not 8 or 11). If it shows wrong Java, your JAVA_HOME is not set correctly.

### What Does `mvn clean install` Mean?

| Command | What It Does |
|---------|-------------|
| `mvn clean` | Delete the `target/` folder (fresh start) |
| `mvn compile` | Compile Java → bytecode (.class files) |
| `mvn test` | Run all unit tests |
| `mvn package` | Create a JAR file |
| `mvn install` | Package + put JAR in local Maven repository (~/.m2/) |
| `mvn clean install` | All of the above from scratch |
| `-DskipTests` | Skip running tests (faster builds during development) |
| `-pl identity-service` | Only build this specific module |
| `-am` | Also build Modules that this module depends on (e.g., common-lib) |

---

<a name="git"></a>
## Step 3: Install Git

**What is Git?** Version control system — saves snapshots (commits) of your code. If you break something, you can go back to a working version.

### Installation Steps

1. **Go to:** https://git-scm.com/download/win
2. **Download** the 64-bit installer
3. **Run installer:**
   - Use defaults for everything EXCEPT:
   - "Choosing the default editor" → select **Notepad** or **VS Code** (not Vim!)
   - "Adjusting your PATH" → select "Git from the command line and also from 3rd-party software"
   - Everything else: click Next → Install → Finish

### Configure Git (Required — Do This Now)

```powershell
# Set your name (appears in commits)
git config --global user.name "Your Full Name"

# Set your email (must match your GitHub email)
git config --global user.email "your.email@example.com"

# Set default branch name to 'main' (not 'master')
git config --global init.defaultBranch main

# Verify
git config --list
```

### Verify Installation

```powershell
git --version
```

**Expected:** `git version 2.45.2.windows.1` (or similar)

### Create a GitHub Account (If You Don't Have One)

1. Go to https://github.com
2. Sign up with your email
3. Verify email
4. Later we'll push our PayFlow code here

---

<a name="nodejs"></a>
## Step 4: Install Node.js 20

**What is Node.js?** JavaScript runtime that lets you run JS outside the browser. We need it for:
- React frontend development
- npm (package manager — like Maven but for JavaScript)
- Vite (frontend build tool)

### Installation Steps

1. **Go to:** https://nodejs.org/
2. **Download:** the **LTS** version (should be 20.x)
3. **Run installer:**
   - Accept license
   - Default install location is fine
   - ✅ "Automatically install necessary tools" — check this
   - Click Install → Finish

### Verify Installation

```powershell
node --version
```
**Expected:** `v20.14.0` (or any 20.x)

```powershell
npm --version
```
**Expected:** `10.7.0` (or similar)

### Troubleshooting

| Problem | Fix |
|---------|-----|
| `node` not recognized | Restart PowerShell |
| Old Node version (16, 18) | Uninstall old version first, then install 20 |
| npm permission errors | Run PowerShell as Administrator |

---

<a name="docker"></a>
## Step 5: Install Docker Desktop

**What is Docker?** Think of it as a "mini-VM" that runs apps in isolated containers. We use it to run:
- PostgreSQL (database) — without installing PostgreSQL natively
- Redis (cache)
- Kafka (message broker)
- All 11 services together

**Why Docker?** "Works on my machine" → "Works on EVERY machine." Same environment locally, in CI/CD, and in production.

### Prerequisites: Enable WSL 2

Docker Desktop on Windows requires WSL 2 (Windows Subsystem for Linux):

```powershell
# Run PowerShell as Administrator
wsl --install
```

**This will restart your computer.** After restart, you may see a Linux terminal pop up — close it.

Verify WSL 2:
```powershell
wsl --status
```
Should show "Default Version: 2"

### Installation Steps

1. **Go to:** https://www.docker.com/products/docker-desktop/
2. **Download** Docker Desktop for Windows
3. **Run installer:**
   - ✅ "Use WSL 2 instead of Hyper-V" — keep checked
   - Click OK → Install → Close
4. **Restart** your computer
5. **Open Docker Desktop** from Start Menu
   - Accept the terms
   - Skip the tutorial/sign-in (you don't need a Docker Hub account for this)
   - Wait until the Docker icon in system tray shows "Docker Desktop is running"

### Verify Installation

```powershell
docker --version
```
**Expected:** `Docker version 26.1.4, build ...`

```powershell
docker compose version
```
**Expected:** `Docker Compose version v2.27.1`

**Test with hello-world:**
```powershell
docker run hello-world
```
**Expected:** Should download a small image and print "Hello from Docker!"

### Troubleshooting

| Problem | Fix |
|---------|-----|
| "WSL 2 is not installed" | Run `wsl --install` as admin, restart |
| Docker Desktop won't start | Enable virtualization in BIOS (VT-x or AMD-V) |
| "Cannot connect to the Docker daemon" | Open Docker Desktop app first, wait for it to initialize |
| Very slow startup | Normal on first launch (2-3 minutes). Subsequent starts are faster |

---

<a name="intellij"></a>
## Step 6: Install IntelliJ IDEA

**What is IntelliJ?** The best Java IDE. It understands Spring Boot, auto-completes annotations, debugs microservices, and integrates with Maven/Docker/Git.

### Installation Steps

1. **Go to:** https://www.jetbrains.com/idea/download/
2. **Download:** IntelliJ IDEA **Community Edition** (free — scroll down past Ultimate)
3. **Run installer:**
   - ✅ "Add 'Open Folder as Project'" — check this
   - ✅ ".java" file association — check this
   - ✅ "Add launchers dir to PATH" — check this
   - Install → Finish
4. **First launch:**
   - Accept privacy policy
   - Choose "Dark" or "Light" theme
   - Click "Start using IntelliJ IDEA"

### Install Essential Plugins

1. Open IntelliJ → File → Settings (Ctrl+Alt+S)
2. Go to **Plugins** → **Marketplace**
3. Search and install:
   - **Lombok** (CRITICAL — without this, half your code shows errors)
   - **Spring Boot Assistant** (or "Spring" if available)
   - **Docker** (for Dockerfile syntax highlighting)
   - **MapStruct Support** (for code generation)
4. Restart IntelliJ when prompted

### Enable Annotation Processing (CRITICAL!)

1. File → Settings → Build, Execution, Deployment → Compiler → Annotation Processors
2. ✅ Check "Enable annotation processing"
3. Click Apply → OK

**Without this, Lombok and MapStruct won't work and you'll see hundreds of fake errors.**

### Import the PayFlow Project (Do This After Code Is Created)

1. File → Open
2. Navigate to `payflow-payment-gateway/backend/`
3. Select the `backend` folder → Click OK
4. When asked "Trust this project?" → Click "Trust Project"
5. Wait 2-5 minutes for Maven to download all dependencies (bottom-right progress bar)
6. When done, you should see no red errors in `common-lib` files

---

<a name="dbeaver"></a>
## Step 7: PostgreSQL Client — DBeaver

**What is DBeaver?** A visual database tool. You can browse tables, run SQL queries, see data — much easier than command-line `psql`.

### Installation Steps

1. **Go to:** https://dbeaver.io/download/
2. **Download:** Windows Installer (64-bit)
3. **Install:** click Next through everything, defaults are fine

### How to Connect (After Docker Is Running)

Once you start PayFlow's Docker infrastructure (in Phase 4), connect DBeaver to the database:

1. Open DBeaver → File → New → Database Connection
2. Select **PostgreSQL** → Next
3. Fill in:
   - Host: `localhost`
   - Port: `5432`
   - Database: `payflow_identity` (or `payflow_payment`, etc.)
   - Username: `payflow`
   - Password: `payflow123`
4. Click "Test Connection" → should show "Connected"
5. Click Finish

You'll have **4 database connections** (one per database: identity, merchant, payment, settlement).

---

<a name="postman"></a>
## Step 8: Install Postman

**What is Postman?** An API testing tool. You send HTTP requests (GET, POST, etc.) and see the response. Essential for testing your endpoints without writing frontend code.

### Installation Steps

1. **Go to:** https://www.postman.com/downloads/
2. **Download** the desktop app
3. **Install** — defaults are fine
4. **Skip sign-in** (click "Skip and go to the app" at the bottom)

### Import PayFlow Collections (Later)

In Phase 4, after services are running, you'll import our pre-built Postman collections from `infra/postman/`:
- File → Import → drag the `.json` files
- Sets up all endpoints with variables and auto-auth scripts

---

<a name="aws-cli"></a>
## Step 9: Install AWS CLI

**What is AWS CLI?** Command-line tool to interact with Amazon Web Services. We'll use it in Phase 8 for deployment.

### Installation Steps

1. **Go to:** https://aws.amazon.com/cli/
2. **Download:** "AWSCLIV2.msi" for Windows
3. **Run installer:** click Next through everything
4. **Restart PowerShell**

### Verify Installation

```powershell
aws --version
```
**Expected:** `aws-cli/2.15.x Python/3.11.x Windows/10 exe/AMD64`

### Configure (We'll Do This Later in Phase 8)

You'll need an AWS account first. For now, just verify it's installed. In Phase 8, you'll run:
```powershell
aws configure
# AWS Access Key ID: (your key)
# AWS Secret Access Key: (your secret)
# Default region name: ap-south-1
# Default output format: json
```

---

<a name="verification"></a>
## Step 10: Complete Verification Script

Save this as `verify-environment.ps1` and run it to check everything at once:

```powershell
# PayFlow Environment Verification Script
# Run: powershell -ExecutionPolicy Bypass -File verify-environment.ps1

Write-Host ""
Write-Host "═══════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  PayFlow Payment Gateway — Environment Check" -ForegroundColor Cyan
Write-Host "═══════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""

$passed = 0
$failed = 0

function Check-Tool {
    param([string]$Name, [string]$Command, [string]$MinVersion)
    
    try {
        $output = Invoke-Expression $Command 2>&1 | Out-String
        Write-Host "  ✅ $Name — $($output.Trim().Split("`n")[0])" -ForegroundColor Green
        $script:passed++
    } catch {
        Write-Host "  ❌ $Name — NOT FOUND" -ForegroundColor Red
        Write-Host "     → Install from the documentation above" -ForegroundColor Yellow
        $script:failed++
    }
}

Write-Host "Checking installed tools..." -ForegroundColor White
Write-Host ""

Check-Tool "Java (JDK 17)" "java -version 2>&1 | Select -First 1" ""
Check-Tool "Java Compiler" "javac -version" ""
Check-Tool "Maven" "mvn -version 2>&1 | Select -First 1" ""
Check-Tool "Git" "git --version" ""
Check-Tool "Node.js" "node --version" ""
Check-Tool "npm" "npm --version" ""
Check-Tool "Docker" "docker --version" ""
Check-Tool "Docker Compose" "docker compose version" ""
Check-Tool "AWS CLI" "aws --version 2>&1 | Select -First 1" ""

Write-Host ""
Write-Host "─────────────────────────────────────────────────────" -ForegroundColor Cyan

# Check JAVA_HOME
if ($env:JAVA_HOME) {
    Write-Host "  ✅ JAVA_HOME = $env:JAVA_HOME" -ForegroundColor Green
    $passed++
} else {
    Write-Host "  ❌ JAVA_HOME is not set!" -ForegroundColor Red
    Write-Host "     → Set it in System Environment Variables" -ForegroundColor Yellow
    $failed++
}

# Check Docker is running
try {
    docker info 2>&1 | Out-Null
    Write-Host "  ✅ Docker daemon is running" -ForegroundColor Green
    $passed++
} catch {
    Write-Host "  ❌ Docker daemon is NOT running" -ForegroundColor Red
    Write-Host "     → Open Docker Desktop app and wait for it to start" -ForegroundColor Yellow
    $failed++
}

Write-Host ""
Write-Host "═══════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  Results: $passed passed, $failed failed" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Yellow" })
Write-Host "═══════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""

if ($failed -eq 0) {
    Write-Host "  🎉 All tools installed! You're ready to build PayFlow." -ForegroundColor Green
} else {
    Write-Host "  ⚠️  Fix the items marked ❌ above before continuing." -ForegroundColor Yellow
}
Write-Host ""
```

### Run the Script

```powershell
# Save the script to a file, then run:
powershell -ExecutionPolicy Bypass -File verify-environment.ps1
```

**Expected output (all green):**
```
═══════════════════════════════════════════════════════
  PayFlow Payment Gateway — Environment Check
═══════════════════════════════════════════════════════

  ✅ Java (JDK 17) — openjdk version "17.0.11"
  ✅ Java Compiler — javac 17.0.11
  ✅ Maven — Apache Maven 3.9.7
  ✅ Git — git version 2.45.2.windows.1
  ✅ Node.js — v20.14.0
  ✅ npm — 10.7.0
  ✅ Docker — Docker version 26.1.4
  ✅ Docker Compose — Docker Compose version v2.27.1
  ✅ AWS CLI — aws-cli/2.15.x
  ✅ JAVA_HOME = C:\Program Files\Eclipse Adoptium\jdk-17...
  ✅ Docker daemon is running

═══════════════════════════════════════════════════════
  Results: 11 passed, 0 failed
═══════════════════════════════════════════════════════

  🎉 All tools installed! You're ready to build PayFlow.
```

---

<a name="common-errors"></a>
## Common Installation Errors & Fixes

| # | Error | Cause | Fix |
|---|-------|-------|-----|
| 1 | `'java' is not recognized` | Java not in PATH | Restart PowerShell. If still fails, add `%JAVA_HOME%\bin` to system PATH manually |
| 2 | `mvn -version` shows Java 8 | JAVA_HOME points to old Java | Update JAVA_HOME to Java 17 path |
| 3 | `'mvn' is not recognized` | Maven not in PATH | Add `C:\Program Files\Maven\apache-maven-3.9.7\bin` to system PATH |
| 4 | Docker "WSL 2 installation is incomplete" | WSL not installed | Run `wsl --install` as admin, restart PC |
| 5 | Docker "Hardware assisted virtualization disabled" | VT-x/AMD-V disabled in BIOS | Restart → Enter BIOS → Enable "Virtualization Technology" |
| 6 | `npm ERR! EACCES permission denied` | Permission issue | Run PowerShell as Administrator |
| 7 | `git: command not found` | Git not in PATH | Reinstall Git, check "Git from command line" option |
| 8 | IntelliJ shows "Cannot resolve symbol" everywhere | Annotation processing disabled | Settings → Compiler → Annotation Processors → ✅ Enable |
| 9 | IntelliJ shows "Module SDK is not defined" | JDK not configured in IDE | File → Project Structure → SDK → Add Java 17 |
| 10 | `docker compose up` fails with port conflict | Port already used | Run `netstat -ano | findstr :5432` to find the process, then kill it |
| 11 | Maven downloads take forever | Slow mirror | Add a mirror in `~/.m2/settings.xml` or just wait (first build is slow) |
| 12 | "No compiler is provided in this environment" | JRE installed instead of JDK | Uninstall JRE, install JDK from Adoptium |
| 13 | Docker pulls fail with timeout | Corporate firewall/proxy | Configure Docker proxy in Settings → Resources → Proxies |
| 14 | `java.lang.UnsupportedClassVersionError` | Running compiled code on wrong Java version | Ensure `java -version` shows 17, not older |
| 15 | IntelliJ stuck on "Indexing" forever | Large project, slow PC | Wait (can take 5-10 min first time). Close other apps. |
| 16 | `JAVA_HOME does not point to JDK` | JAVA_HOME points to JRE folder | Set JAVA_HOME to JDK folder (has `bin/javac.exe`) |
| 17 | Node installer won't run | Antivirus blocking | Temporarily disable antivirus, run installer, re-enable |
| 18 | Docker image pull "manifest unknown" | Wrong image name/tag | Double-check the image name in docker-compose.yml |
| 19 | Git clone "Permission denied (publickey)" | SSH key not set up | Use HTTPS URL instead, or set up SSH key on GitHub |
| 20 | `mvn clean install` fails on common-lib | Build order wrong | Always build from the root: `cd backend && mvn clean install` (builds all modules in order) |

---

## IntelliJ First-Time Setup (After Opening the Project)

### Opening PayFlow Backend

1. Open IntelliJ IDEA
2. File → Open
3. Navigate to: `payflow-payment-gateway/backend/`
4. Select the `backend` **folder** (not a file inside it) → Click OK
5. If asked "Open as Project?" → click "Open as Project"
6. If asked "Trust this project?" → click "Trust Project"

### Wait for Indexing

- Bottom-right corner shows a progress bar: "Indexing..."
- **Wait until it finishes** (3-10 minutes on first open)
- During indexing, auto-complete and error highlighting won't work properly

### Verify Project Loaded Correctly

1. In the Project panel (left side), expand `backend`
2. You should see all 12 modules (common-lib, service-registry, etc.)
3. Open `backend/common-lib/src/main/java/com/payflow/common/dto/ApiResponse.java`
4. If you see NO red errors → everything is working
5. If you see red errors → check "Enable Annotation Processing" (see Step 6 above)

### Create Run Configuration (for Service Registry)

1. Open `ServiceRegistryApplication.java`
2. Click the green ▶️ arrow next to `main()` → "Run ServiceRegistryApplication"
3. Console should show: `Started ServiceRegistryApplication in X seconds`
4. Open browser: http://localhost:8761 → should show Eureka Dashboard

---

## What You Learned

| # | Concept | What You Practiced |
|---|---------|-------------------|
| 1 | JDK vs JRE | Installed JDK 17 (compiler + runtime) |
| 2 | Maven | Installed build tool, understand PATH setup |
| 3 | Git configuration | Set global name/email, understand version control |
| 4 | Node.js + npm | Installed JavaScript runtime for frontend |
| 5 | Docker + WSL 2 | Installed container runtime on Windows |
| 6 | IntelliJ setup | IDE with Lombok, annotation processing enabled |
| 7 | Environment variables | JAVA_HOME, PATH modifications |
| 8 | Verification | Created a script to validate everything works |

---

## Next Steps

**What's coming in Phase 1 (System Design):**
- Define all functional and non-functional requirements
- Design the complete API (every endpoint)
- Design all databases (schemas, tables, indexes)
- Design event streaming (Kafka topics)
- Make 30+ design decisions (with alternatives and trade-offs)

**Before moving on, verify:**
- [ ] All tools show green in the verification script
- [ ] Docker Desktop is running (whale icon in system tray)
- [ ] IntelliJ opens without errors
- [ ] You can run `mvn -version` and see Java 17
- [ ] You understand what each tool does

---

## Document Index

| Phase | Part | Document | Status |
|-------|------|----------|--------|
| 0 | 1 | [Project Overview](./phase0-part1-project-overview.md) | ✅ |
| 0 | **2** | **[Environment Setup](./phase0-part2-environment-setup.md)** | ← You are here |
| 1 | - | [System Design](./phase1-system-design.md) | ⬜ Next |
| 2 | - | [High-Level Design](./phase2-high-level-design.md) | ⬜ |
| 3 | - | [Low-Level Design](./phase3-low-level-design.md) | ⬜ |
| 4 | 1 | [Parent POM & Maven](./phase4-part01-parent-pom-and-maven-setup.md) | ⬜ |
| ... | ... | ... | ... |

---
*End of Phase 0 Part 2 — Environment Setup*
*Next: [Phase 1 — System Design](./phase1-system-design.md)*
