# Spring Profiles Setup

## Architecture: Profile-Driven Config Server Import

The **key insight**: Only import config server in the `dev` profile, not in the default. This prevents any connection attempts for local development.

```
bootstrap.yml         ← Default: app name only, NO config import
├── bootstrap-local.yml   ← LOCAL: app name only, NO config import
└── bootstrap-dev.yml     ← DEV: app name + config import + server URI
```

**Result:**
- **LOCAL profile**: Zero config server connection attempts ✅
- **DEV profile**: Config server import with proper retry logic ✅
- **Default profile** (no args): Clean, optional fallback ✅

---

### 1. **LOCAL** (Default - No Config Server)
Use this for local development without Spring Cloud Config Server.

**Quick Start (use the script):**
```bash
chmod +x run-local.sh
./run-local.sh
```

**Or run directly:**
```bash
mvn clean spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local"
```

**Or with environment variable:**
```bash
export SPRING_PROFILES_ACTIVE=local
mvn clean spring-boot:run
```

**Configuration:**
- `bootstrap-local.yml`: Sets `fail-fast: false` (config server optional)
- `application-local.properties`: Local logging settings
- Templates loaded from classpath: `src/main/resources/common-templates/`

**When to use:** Local development, testing, CI/CD without config server

---

### 2. **DEV** (Config Server Enabled)
Use this when you have a Spring Cloud Config Server running on `http://localhost:8888`.

**Quick Start (use the script):**
```bash
chmod +x run-dev.sh
./run-dev.sh
```

**Or run directly:**
```bash
mvn clean spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=dev"
```

**Or with environment variable:**
```bash
export SPRING_PROFILES_ACTIVE=dev
mvn clean spring-boot:run
```

**Configuration:**
- `bootstrap-dev.yml`: Connects to `http://localhost:8888` with `fail-fast: true`
- Includes retry logic (6 attempts, exponential backoff)
- `application-dev.properties`: Dev logging with config server debug logs

**When to use:** 
- Team development with shared config server
- App requires external config properties
- Testing with centralized configuration

**Start Config Server first:**
```bash
cd config-server
mvn clean spring-boot:run
```

---

### 3. **PROD** (Default production)
For production deployments. Customize as needed.

```bash
mvn spring-boot:run -Dspring.profiles.active=prod
```

---

## Configuration Files Reference

### Bootstrap Configs (control config server import)
| File | Config Server Import | URI | Fail-Fast |
|------|---|---|---|
| `bootstrap.yml` | ❌ None | — | — |
| `bootstrap-local.yml` | ❌ None | — | — |
| `bootstrap-dev.yml` | ✅ `configserver:` | `http://localhost:8888` | `true` |

**Key:** Only `bootstrap-dev.yml` imports the config server. Local/default have zero connection attempts.

### Application Configs (logging and debug settings)
| File | Config Server Enabled | Debug Logging |
|------|---|---|
| `application.properties` | (default - N/A) | Standard |
| `application-local.properties` | N/A | `com.example=DEBUG` |
| `application-dev.properties` | N/A | `com.example=DEBUG` + `org.springframework.cloud.config=DEBUG` |

---

---

## Quick Reference

| Profile | Command | Config Server | Result |
|---------|---------|---|---|
| **LOCAL** | `./run-local.sh` | ❌ Disabled | ✅ Zero connection attempts |
| **DEV** | `./run-dev.sh` | ✅ Enabled | 🔌 Connects to `localhost:8888` |
| **Default** (none) | `mvn spring-boot:run` | ❌ Disabled | ✅ Fails gracefully, runs locally |

---

## Testing the Setup

### Test Local Profile (No Server)
**Terminal 1: Start app**
```bash
./run-local.sh
# or
mvn clean spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local"
```

**Terminal 2: Test endpoint**
```bash
curl -X POST http://localhost:8080/api/documents/generate \
  -H "Content-Type: application/json" \
  -d '{"namespace":"common", "templateId":"sample-pdf", "data":{}}'
```

**Expected Logs:** Should see `The following profiles are active: local` ✅

---

### Test Dev Profile (With Config Server)

**Terminal 1:** Start config server
```bash
cd config-server
mvn spring-boot:run
```

**Terminal 2:** Start main app
```bash
./run-dev.sh
# or
mvn clean spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=dev"
```

**Expected Logs:** Should see `The following profiles are active: dev` ✅

---

## Setting Default Profile

If you want to always use a specific profile without specifying it each time:

### Option 1: Environment Variable
```bash
export SPRING_PROFILES_ACTIVE=local
mvn spring-boot:run
```

### Option 2: System Property in pom.xml
Edit `pom.xml`:
```xml
<plugin>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-maven-plugin</artifactId>
  <configuration>
    <arguments>
      <argument>--spring.profiles.active=local</argument>
    </arguments>
  </configuration>
</plugin>
```

Then just run:
```bash
mvn spring-boot:run
```

### Option 3: application.yml
Add to `src/main/resources/application.yml`:
```yaml
spring:
  profiles:
    active: local  # Default profile
```

---

## Troubleshooting

### Still seeing "Fetching config from server at : http://localhost:8888" with local profile?
- **Root Cause:** Default `application.properties` or `bootstrap.yml` had `spring.config.import=optional:configserver:`
- **Fix:** Remove `spring.config.import=optional:configserver:` from DEFAULT configs. It should ONLY be in `bootstrap-dev.yml`
- **Check:** Run these and verify output:
  ```bash
  cat src/main/resources/bootstrap.yml        # Should be 3 lines: spring > application > name
  cat src/main/resources/application.properties  # Should have NO configserver references
  cat src/main/resources/bootstrap-dev.yml    # Should HAVE configserver import + config block
  ```

### "Could not locate PropertySource and fail fast is set" on dev profile
- **Cause:** Dev profile enabled but config server not running
- **Fix:** Start config server first before running with dev profile
  ```bash
  cd config-server && mvn spring-boot:run
  ```

### "Cannot find template location(s): [classpath:/templates/]"
- **Cause:** Spring Thymeleaf looking for templates in wrong place
- **Fix:** Normal warning, templates are in `classpath:/common-templates/` and `classpath:/tenant-a/`

### Config not loading from config server
- **Cause:** Config Server doesn't have config for `doc-gen-service` app
- **Fix:** Verify config server has `doc-gen-service.yml` or `doc-gen-service-dev.yml`
