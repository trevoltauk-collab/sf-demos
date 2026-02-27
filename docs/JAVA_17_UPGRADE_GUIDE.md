# Java 11 to Java 17 Upgrade Guide

This guide provides step-by-step instructions for upgrading your Java development environment from Java 11 to Java 17.

## Table of Contents
1. [Prerequisites](#prerequisites)
2. [Installation Steps](#installation-steps)
3. [Project Configuration](#project-configuration)
4. [Environment Setup](#environment-setup)
5. [Testing & Verification](#testing--verification)
6. [Troubleshooting](#troubleshooting)

---

## Prerequisites

Before starting the upgrade, ensure you have:
- Administrative or sudo access to your system
- Currently installed Java 11 (can run parallel with Java 17)
- Maven or Gradle configured for your project
- All source code committed to version control (recommended)

### Check Current Java Version

```bash
java -version
javac -version
echo $JAVA_HOME
```

**Expected output for Java 11:**
```
openjdk version "11.0.14.1" 2022-02-08 LTS
...
```

---

## Installation Steps

### On Ubuntu/Debian Linux

#### Step 1: Update Package Lists
```bash
sudo apt update
```

#### Step 2: Install OpenJDK 17
```bash
sudo apt install -y openjdk-17-jdk openjdk-17-jre
```

#### Step 3: Verify Installation
```bash
/usr/lib/jvm/java-17-openjdk-amd64/bin/java -version
```

**Expected output:**
```
openjdk version "17.0.x" ...
OpenJDK Runtime Environment ...
```

### On macOS (using Homebrew)

```bash
brew install openjdk@17
```

Follow the post-install instructions provided by Homebrew.

### On Windows

1. Download from [Oracle JDK 17](https://www.oracle.com/java/technologies/downloads/#java17) or [OpenJDK builds](https://jdk.java.net/17/)
2. Run the installer
3. Follow the installation wizard
4. Note the installation path (e.g., `C:\Program Files\Java\jdk-17.x.x`)

---

## Project Configuration

### Step 1: Update pom.xml (Maven Projects)

Locate the `<properties>` section in your `pom.xml` and update the Java version:

```xml
<properties>
    <java.version>17</java.version>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
</properties>
```

**Complete example:**
```xml
<project>
  ...
  <properties>
    <java.version>17</java.version>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  ...
</project>
```

### Step 2: Update build.gradle (Gradle Projects)

```gradle
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
```

### Step 3: Update Spring Boot Parent (if applicable)

Ensure your Spring Boot version is compatible with Java 17. Spring Boot 3.0.0+ is recommended:

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.2</version>
    <relativePath/> <!-- lookup parent from repository -->
</parent>
```

---

## Environment Setup

### Linux/macOS: Configure JAVA_HOME

#### Option 1: Update Shell Profile (Persistent)

Edit your shell configuration file (`~/.bashrc`, `~/.zshrc`, or `~/.bash_profile`):

```bash
# Add this line
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

**For macOS with Homebrew:**
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

Reload the configuration:
```bash
source ~/.bashrc  # or ~/.zshrc
```

#### Option 2: Set Java as Default (Linux)

Using `update-alternatives`:

```bash
# For java
sudo update-alternatives --install /usr/bin/java java /usr/lib/jvm/java-17-openjdk-amd64/bin/java 1

# For javac
sudo update-alternatives --install /usr/bin/javac javac /usr/lib/jvm/java-17-openjdk-amd64/bin/javac 1

# Select Java 17 as default
sudo update-alternatives --set java /usr/lib/jvm/java-17-openjdk-amd64/bin/java
sudo update-alternatives --set javac /usr/lib/jvm/java-17-openjdk-amd64/bin/javac
```

### Windows: Configure JAVA_HOME

#### Via Environment Variables GUI:
1. Press `Win + X` and select "System"
2. Click "Advanced system settings"
3. Click "Environment Variables"
4. Under "System variables", click "New"
5. Variable name: `JAVA_HOME`
6. Variable value: `C:\Program Files\Java\jdk-17.x.x` (adjust path as needed)
7. Click "OK" and restart your terminal

#### Via Command Prompt (Admin):
```cmd
setx JAVA_HOME "C:\Program Files\Java\jdk-17.x.x"
```

### Update IDE Configuration

#### IntelliJ IDEA
1. File → Project Structure
2. Select "Project"
3. Under "SDK", select Java 17 or click "Add SDK" to download it
4. Apply changes

#### Eclipse
1. Window → Preferences
2. Java → Installed JREs
3. Click "Add..." and select the Java 17 installation directory
4. Set Java 17 as the default

#### VS Code
Edit `.vscode/settings.json`:
```json
{
  "java.jdt.ls.java.home": "/usr/lib/jvm/java-17-openjdk-amd64"
}
```

---

## Testing & Verification

### Step 1: Verify Java Version

```bash
java -version
javac -version
echo "JAVA_HOME=$JAVA_HOME"
```

Expected output:
```
openjdk version "17.0.x" ...
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

### Step 2: Verify Maven/Gradle

```bash
# Maven
mvn --version

# Gradle
gradle --version
```

Both should show Java 17 in their output.

### Step 3: Test Project Compilation

```bash
# Maven
mvn clean compile

# Gradle
gradle build --stacktrace
```

### Step 4: Run Test Suite

```bash
# Maven
mvn test

# Gradle
gradle test
```

### Step 5: Build Distribution Package

```bash
# Maven (skip tests if needed)
mvn clean package -DskipTests=true

# Gradle
gradle build
```

---

## Breaking Changes & Compatibility

### Java 11 → Java 17 Compatibility Notes

#### Generally Compatible:
- Most libraries and frameworks work without modification
- Bytecode compiled for Java 11 is compatible with Java 17

#### Areas Requiring Attention:

1. **Removed APIs:**
   - `` java.lang.Runtime.exec(String) `` with shell parsing
   - Outdated XML/CORBA modules

2. **Module System:**
   - If using modules, may need to update `module-info.java`
   - Most projects don't use modules yet

3. **Sealed Classes & Records:**
   - New language features available (optional to use)

4. **Preview Features:**
   - Be cautious with preview features across versions

5. **Dependency Updates:**
   - Update third-party libraries for full Java 17 support
   - Check Maven Central for newer versions

### Common Issues & Solutions

| Issue | Solution |
|-------|----------|
| `NoClassDefFoundError` or `ClassNotFoundException` | Update dependent libraries to Java 17 compatible versions |
| Reflection issues with `setAccessible()` | Update code or use JVM flags: `-XX:+IgnoreUnrecognizedVMOptions -XX:+UnlockDiagnosticVMOptions -XX:+-UnlockExperimentalVMOptions` |
| Illegal reflection warnings | Review code for `Unsafe` API usage or update libraries |
| Build tool not finding Java | Ensure `JAVA_HOME` is set and verify with `echo $JAVA_HOME` |

---

## Troubleshooting

### Problem: Multiple Java Versions Installed

**Check what's installed:**
```bash
# Linux
ls -la /usr/lib/jvm/ | grep java

# macOS
ls -la /Library/Java/JavaVirtualMachines/
```

**View alternatives (Linux):**
```bash
update-alternatives --list java
update-alternatives --list javac
```

### Problem: JAVA_HOME Not Set

**Verify:**
```bash
echo $JAVA_HOME
```

**If empty, set it:**
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
# Then add to ~/.bashrc for persistence
```

### Problem: Maven/Gradle Still Uses Java 11

**Verify Maven Java version:**
```bash
mvn --version
```

**If showing Java 11:**
1. Ensure `JAVA_HOME` is set correctly
2. Restart terminal after setting `JAVA_HOME`
3. Check IDE settings if using IDE-embedded Maven

### Problem: Build Fails with Compilation Errors

**Steps to debug:**
1. Run with verbose output: `mvn clean compile -X`
2. Check for deprecated API usage in logs
3. Update problematic dependencies:
   ```bash
   mvn dependency:tree | grep SNAPSHOT
   ```
4. Run clean build: `mvn clean compile`

### Problem: IDE Not Recognizing Java 17

1. **IntelliJ:** File → Invalidate Caches → Restart
2. **Eclipse:** Project → Clean → Rebuild All
3. **VS Code:** Reload Window (Ctrl+Shift+P → "Reload Window")

---

## Rollback Plan

If you need to revert to Java 11:

### Linux
```bash
sudo update-alternatives --set java /usr/lib/jvm/java-11-openjdk-amd64/bin/java
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
```

### macOS
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 11)
```

### Windows
Change `JAVA_HOME` environment variable back to Java 11 installation path in Environment Variables.

---

## Performance Considerations

Java 17 includes several performance improvements over Java 11:

- **Garbage Collection**: G1GC improvements for large heaps
- **String Compression**: Continues from Java 9
- **ZGC**: Low-latency GC option available

No action needed unless you want to explicitly configure GC. Default settings work well for most applications.

---

## Additional Resources

- [Java 17 Release Notes](https://www.oracle.com/java/technologies/javase/17-relnotes.html)
- [JDK 17 Migration Guide](https://docs.oracle.com/javase/17/migrate/index.html)
- [Spring Boot 3.x Documentation](https://spring.io/projects/spring-boot)
- [Maven Java Compiler Plugin](https://maven.apache.org/plugins/maven-compiler-plugin/)

---

## Summary Checklist

- [ ] Install OpenJDK 17
- [ ] Update `pom.xml` or `build.gradle` with Java 17 settings
- [ ] Set `JAVA_HOME` environment variable
- [ ] Configure IDE to use Java 17
- [ ] Run `mvn clean compile`
- [ ] Run test suite
- [ ] Verify all systems working correctly
- [ ] Update documentation and CI/CD pipelines
- [ ] Commit configuration changes to version control
