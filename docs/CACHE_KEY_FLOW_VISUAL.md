# Cache Key Flow: Visual Guide

Interactive walkthrough of how cache keys are generated and used for multi-configuration scenarios.

## Scenario: Single Template, Three Environments

### Setup

**Template File:** `enrollment-form.yaml`
```yaml
templateId: enrollment-form
namespace: tenant-a
sections:
  - templatePath: forms/form-${environment}.pdf
    condition: "$.env == '${environmentLevel}'"
```

**Configuration:** `application.yml`
```yaml
docgen.templates.prewarming.scenarios:
  - name: Production
    variables: { environment: production, environmentLevel: PROD }
  - name: Staging
    variables: { environment: staging, environmentLevel: STAGING }
  - name: Development
    variables: { environment: development, environmentLevel: DEV }
```

---

## Step 1: Application Startup (Prewarming)

```
┌─────────────────────────────────────────────────────────┐
│         APPLICATION STARTUP - PREWARMING PHASE          │
└─────────────────────────────────────────────────────────┘

┌─ For each scenario, TemplateCacheWarmer does:

  Scenario 1: "Production"
  ├─ Load: loadTemplate("tenant-a", "enrollment-form")
  │  Cache Key: {"tenant-a", "enrollment-form"}  ✓ Load once (structural)
  │
  ├─ Create Variables: { environment: production, environmentLevel: PROD }
  │
  ├─ Generate Cache Key for resolved:
  │  ├─ Serialize: "{\"environment\":\"production\",\"environmentLevel\":\"PROD\"}"
  │  ├─ SHA-256:   "7f4e2c9a1d5b8f3e6c2a9b7d4f1e8c5a"
  │  └─ Full Key:  "tenant-a:enrollment-form:7f4e2c9a1d5b8f3e6c2a9b7d4f1e8c5a"
  │
  ├─ Resolve placeholders:
  │  ├─ ${environment} → production
  │  └─ ${environmentLevel} → PROD
  │
  ├─ Cache resolved:
  │  Key: "tenant-a:enrollment-form:7f4e2c9a..."
  │  Data: Resolved template with forms/form-production.pdf, condition: $.env == 'PROD'
  │
  └─ Result: ✓ Prewarmed


  Scenario 2: "Staging"
  ├─ Load: loadTemplate("tenant-a", "enrollment-form")
  │  Cache Key: {"tenant-a", "enrollment-form"}  ✓ Already loaded (use same)
  │
  ├─ Create Variables: { environment: staging, environmentLevel: STAGING }
  │
  ├─ Generate Cache Key for resolved:
  │  ├─ Serialize: "{\"environment\":\"staging\",\"environmentLevel\":\"STAGING\"}"
  │  ├─ SHA-256:   "a3c6f2e8b1d9e4f7c2a6d9e1f3a4b5c6"
  │  └─ Full Key:  "tenant-a:enrollment-form:a3c6f2e8b1d9e4f7c2a6d9e1f3a4b5c6"
  │
  ├─ Resolve placeholders:
  │  ├─ ${environment} → staging
  │  └─ ${environmentLevel} → STAGING
  │
  ├─ Cache resolved:
  │  Key: "tenant-a:enrollment-form:a3c6f2e8..."
  │  Data: Resolved template with forms/form-staging.pdf, condition: $.env == 'STAGING'
  │
  └─ Result: ✓ Prewarmed


  Scenario 3: "Development"
  ├─ Load: loadTemplate("tenant-a", "enrollment-form")
  │  Cache Key: {"tenant-a", "enrollment-form"}  ✓ Already loaded (use same)
  │
  ├─ Create Variables: { environment: development, environmentLevel: DEV }
  │
  ├─ Generate Cache Key for resolved:
  │  ├─ Serialize: "{\"environment\":\"development\",\"environmentLevel\":\"DEV\"}"
  │  ├─ SHA-256:   "c9b4e2f7a1d3f8c5e2b9a6d1f3e4c7a2"
  │  └─ Full Key:  "tenant-a:enrollment-form:c9b4e2f7a1d3f8c5e2b9a6d1f3e4c7a2"
  │
  ├─ Resolve placeholders:
  │  ├─ ${environment} → development
  │  └─ ${environmentLevel} → DEV
  │
  ├─ Cache resolved:
  │  Key: "tenant-a:enrollment-form:c9b4e2f7..."
  │  Data: Resolved template with forms/form-development.pdf, condition: $.env == 'DEV'
  │
  └─ Result: ✓ Prewarmed
```

---

## Step 2: Cache State After Startup

```
┌───────────────────────────────────────────────────────────┐
│        DOCUMENTTEMPLATES CACHE - POST STARTUP             │
└───────────────────────────────────────────────────────────┘

📦 Cache Contents:

├─ STRUCTURAL TEMPLATES (loaded once)
│  │
│  └─ Key: {"tenant-a", "enrollment-form"}
│     Data: DocumentTemplate {
│       templateId: "enrollment-form"
│       sections: [
│         { templatePath: "forms/form-${environment}.pdf"
│           condition: "$.env == '${environmentLevel}'"
│         }
│       ]
│     }
│
└─ RESOLVED TEMPLATES (prewarmed variants)
   │
   ├─ Key: "tenant-a:enrollment-form:7f4e2c9a1d5b8f3e..."
   │  Data: DocumentTemplate {
   │    sections: [
   │      { templatePath: "forms/form-production.pdf"
   │        condition: "$.env == 'PROD'"
   │      }
   │    ]
   │  }
   │
   ├─ Key: "tenant-a:enrollment-form:a3c6f2e8b1d9e4f7..."
   │  Data: DocumentTemplate {
   │    sections: [
   │      { templatePath: "forms/form-staging.pdf"
   │        condition: "$.env == 'STAGING'"
   │      }
   │    ]
   │  }
   │
   └─ Key: "tenant-a:enrollment-form:c9b4e2f7a1d3f8c5..."
      Data: DocumentTemplate {
        sections: [
          { templatePath: "forms/form-development.pdf"
            condition: "$.env == 'DEV'"
          }
        ]
      }

Total cache entries: 1 structural + 3 resolved = 4 entries
```

---

## Step 3: Request Processing at Runtime

### Request A: Production Environment

```
┌─────────────────────────────────────────────────────────┐
│  REQUEST A: User requests document for PRODUCTION       │
└─────────────────────────────────────────────────────────┘

Step 1: Extract request variables
  ├─ environment: "production"
  └─ environmentLevel: "PROD"

Step 2: Generate cache key
  ├─ Namespace: "tenant-a"
  ├─ Template ID: "enrollment-form"
  ├─ Variables JSON: {"environment":"production","environmentLevel":"PROD"}
  ├─ SHA-256 Hash: 7f4e2c9a1d5b8f3e...
  └─ Cache Key: "tenant-a:enrollment-form:7f4e2c9a1d5b8f3e..."

Step 3: Lookup in cache
  ├─ Query: cache.get("tenant-a:enrollment-form:7f4e2c9a1d5b8f3e...")
  ├─ Result: ✓ HIT (prewarmed)
  ├─ Returned: Resolved template with forms/form-production.pdf
  └─ Time: < 1ms

Step 4: Render document
  ├─ Load resource: forms/form-production.pdf
  ├─ Apply data
  └─ Generate PDF

✓ FAST PATH - Prewarmed variant hit the cache
```

### Request B: Staging Environment

```
┌─────────────────────────────────────────────────────────┐
│  REQUEST B: User requests document for STAGING          │
└─────────────────────────────────────────────────────────┘

Step 1: Extract request variables
  ├─ environment: "staging"
  └─ environmentLevel: "STAGING"

Step 2: Generate cache key
  ├─ Namespace: "tenant-a"
  ├─ Template ID: "enrollment-form"
  ├─ Variables JSON: {"environment":"staging","environmentLevel":"STAGING"}
  ├─ SHA-256 Hash: a3c6f2e8b1d9e4f7...
  └─ Cache Key: "tenant-a:enrollment-form:a3c6f2e8b1d9e4f7..."

Step 3: Lookup in cache
  ├─ Query: cache.get("tenant-a:enrollment-form:a3c6f2e8b1d9e4f7...")
  ├─ Result: ✓ HIT (prewarmed)
  ├─ Returned: Resolved template with forms/form-staging.pdf
  └─ Time: < 1ms

Step 4: Render document
  ├─ Load resource: forms/form-staging.pdf
  ├─ Apply data
  └─ Generate PDF

✓ FAST PATH - Prewarmed variant hit the cache
```

### Request C: Development Environment

```
┌─────────────────────────────────────────────────────────┐
│  REQUEST C: User requests document for DEVELOPMENT      │
└─────────────────────────────────────────────────────────┘

Step 1: Extract request variables
  ├─ environment: "development"
  └─ environmentLevel: "DEV"

Step 2: Generate cache key
  ├─ Namespace: "tenant-a"
  ├─ Template ID: "enrollment-form"
  ├─ Variables JSON: {"environment":"development","environmentLevel":"DEV"}
  ├─ SHA-256 Hash: c9b4e2f7a1d3f8c5...
  └─ Cache Key: "tenant-a:enrollment-form:c9b4e2f7a1d3f8c5..."

Step 3: Lookup in cache
  ├─ Query: cache.get("tenant-a:enrollment-form:c9b4e2f7a1d3f8c5...")
  ├─ Result: ✓ HIT (prewarmed)
  ├─ Returned: Resolved template with forms/form-development.pdf
  └─ Time: < 1ms

Step 4: Render document
  ├─ Load resource: forms/form-development.pdf
  ├─ Apply data
  └─ Generate PDF

✓ FAST PATH - Prewarmed variant hit the cache
```

### Request D: Ad-Hoc Region (Not Prewarmed)

```
┌─────────────────────────────────────────────────────────┐
│  REQUEST D: User requests document for APAC region      │
│             (not in prewarming scenarios)                │
└─────────────────────────────────────────────────────────┘

Step 1: Extract request variables
  ├─ environment: "production"
  └─ environmentLevel: "PROD-APAC"  ← Different from scenarios

Step 2: Generate cache key
  ├─ Namespace: "tenant-a"
  ├─ Template ID: "enrollment-form"
  ├─ Variables JSON: {"environment":"production","environmentLevel":"PROD-APAC"}
  ├─ SHA-256 Hash: f1b9e3c7d2a8f5e1...
  └─ Cache Key: "tenant-a:enrollment-form:f1b9e3c7d2a8f5e1..."

Step 3: Lookup in cache
  ├─ Query: cache.get("tenant-a:enrollment-form:f1b9e3c7d2a8f5e1...")
  ├─ Result: ✗ MISS (not prewarmed)
  └─ Time: < 1ms (failed lookup)

Step 4: Load and resolve
  ├─ Load base: loadTemplate("tenant-a", "enrollment-form")
  │   → Get from cache {"tenant-a", "enrollment-form"}
  ├─ Deep copy: deepCopy(base)
  ├─ Resolve placeholders:
  │   ├─ ${environment} → production
  │   └─ ${environmentLevel} → PROD-APAC
  ├─ Cache result:
  │   Key: "tenant-a:enrollment-form:f1b9e3c7d2a8f5e1..."
  │   Data: Resolved template
  └─ Time: 50-150ms (first time)

Step 5: Render document
  └─ Generate PDF

⚠️  SLOWER PATH - First request to new variant
    └─ Subsequent requests for same variables will be fast (cache hit)
```

---

## Cache Key Uniqueness Examples

### Same Template, Different Variables = Different Keys

```
Template: enrollment-form
Namespace: tenant-a

Scenario 1 Variables:
  { environment: "production", region: "us-east-1" }
  SHA-256: 7f4e2c9a1d5b8f3e6c2a9b7d4f1e8c5a
  Key: tenant-a:enrollment-form:7f4e2c9a...

Scenario 2 Variables:
  { environment: "production", region: "eu-west-1" }  ← Different region
  SHA-256: a3c6f2e8b1d9e4f7c2a6d9e1f3a4b5c6
  Key: tenant-a:enrollment-form:a3c6f2e8...

Result: Different variables → Different keys → Different cache entries ✓
```

### Order Independence

```
Variables Set 1:
  { "env": "prod", "region": "us-east-1" }
  ObjectMapper sorts → {"env":"prod","region":"us-east-1"}
  SHA-256: 7f4e2c9a...

Variables Set 2 (different insertion order):
  { "region": "us-east-1", "env": "prod" }
  ObjectMapper sorts → {"env":"prod","region":"us-east-1"}
  SHA-256: 7f4e2c9a...

Result: Same key despite different insertion order ✓ (deterministic)
```

---

## Performance Characteristics

```
┌─────────────────────────────────────────────────────────┐
│              OPERATION TIMING                           │
└─────────────────────────────────────────────────────────┘

Startup (One-time):
  Load template:              50-200ms (per template file)
  JSON serialization:         < 1ms
  SHA-256 hash:               < 1ms per entry
  Deep copy + interpolation:  20-50ms per scenario
  Total for 3 scenarios:      ~150-300ms (added to startup)

Runtime - Cache Hit:
  Generate cache key:         < 1ms
  Hash table lookup:          O(1) = < 1ms
  Render document:            500-2000ms (depends on complexity)
  Total:                      ~501-2001ms (dominated by rendering)

Runtime - Cache Miss:
  Generate cache key:         < 1ms
  Hash table lookup:          < 1ms (failed)
  Load base template:         < 1ms (already cached)
  Deep copy:                  5-20ms
  Interpolation:              15-80ms
  Hash + cache store:         2-5ms
  Render document:            500-2000ms
  Total:                      ~530-2100ms (first occurrence)

Second request same variant:  ~501-2001ms (cache hit)
```

---

## Visual: Complete Cache Lifecycle

```
┌──────────────────────────────────────────────────────────────┐
│                  COMPLETE CACHE LIFECYCLE                     │
└──────────────────────────────────────────────────────────────┘

01 APPLICATION STARTUP
   ├─ Load prewarming.scenarios from configuration
   ├─ For each scenario:
   │  ├─ Load template (once per template ID)
   │  ├─ Generate cache key with variables
   │  ├─ Deep copy + interpolate
   │  └─ Cache resolved variant
   └─ Startup adds ~150-300ms

02 CACHE WARMUP COMPLETE
   │
   ├─ Structural template cached
   ├─ N resolved variants cached
   └─ All resources preloaded

03 FIRST USER REQUEST (Prewarmed Scenario)
   ├─ Check cache key
   ├─ HIT ✓
   ├─ Return resolved template
   └─ Fast path: ~1-2 seconds

04 FIRST USER REQUEST (Non-Prewarmed Scenario)
   ├─ Check cache key
   ├─ MISS ✗
   ├─ Load → Copy → Interpolate
   ├─ Cache new variant
   └─ Slower path: ~2-3 seconds

05 SECOND REQUEST (Same Variables, Any Scenario)
   ├─ Check cache key
   ├─ HIT ✓ (from step 3 or 4)
   ├─ Return resolved template
   └─ Fast path: ~1-2 seconds

06 CACHE EXPIRATION (After 24 hours)
   ├─ Entry expires from cache
   └─ Next request triggers reload
```

---

## Key Takeaways

| Point | Implication |
|-------|-------------|
| **One template, N resolved variants** | 1 file to maintain, N different configurations |
| **Unique cache key per variant** | No overwriting, independent caching |
| **Deterministic SHA-256 hash** | Same variables always produce same key |
| **Prewarming eliminates first-request latency** | All scenarios immediately fast |
| **Non-prewarmed still cached** | Subsequent requests to new variants are fast |
| **Memory efficient** | Structural template shared, only differences cached |
| **O(1) lookup** | Cache hit time independent of cache size |

All achieved through intelligent cache key generation! 🎯
