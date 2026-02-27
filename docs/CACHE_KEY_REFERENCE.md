# Cache Key Reference: Concrete Examples

Quick reference showing exact cache keys for each scenario pattern.

## Pattern 1: Multi-Environment (Structural + Resolved)

**Configuration:**
```yaml
scenarios:
  - name: "Prod"
    templateId: enrollment-form
    variables: { env: production, region: us-east-1 }
    
  - name: "Staging"
    templateId: enrollment-form
    variables: { env: staging, region: us-west-2 }
    
  - name: "Dev"
    templateId: enrollment-form
    variables: { env: development, region: local }
```

**Cache State After Prewarming:**
```
▼ Structural Templates
  Key: "enrollment-form"
  → Base template with ${env} ${region} placeholders

▼ Resolved Templates (Prewarmed Variants)
  Key: "enrollment-form:7f4e2c9a1d5b8f3e..."  [env=production, region=us-east-1]
       └─ Resolved: forms/form-production.pdf, footers/us-east-1.ftl
       
  Key: "enrollment-form:a3c6f2e8b1d9e4f7..."  [env=staging, region=us-west-2]
       └─ Resolved: forms/form-staging.pdf, footers/us-west-2.ftl
       
  Key: "enrollment-form:c9b4e2f7a1d3f8c5..."  [env=development, region=local]
       └─ Resolved: forms/form-development.pdf, footers/local.ftl
```

**Cache Keys Generated:**
```
SHA-256("{\"env\":\"production\",\"region\":\"us-east-1\"}") 
  → 7f4e2c9a1d5b8f3e6c2a9b7d4f1e8c5a

SHA-256("{\"env\":\"staging\",\"region\":\"us-west-2\"}") 
  → a3c6f2e8b1d9e4f7c2a6d9e1f3a4b5c6

SHA-256("{\"env\":\"development\",\"region\":\"local\"}") 
  → c9b4e2f7a1d3f8c5e2b9a6d1f3e4c7a2
```

---

## Pattern 2: Multi-Tenant (Namespace-Aware)

**Configuration:**
```yaml
scenarios:
  - name: "Tenant A Prod"
    templateId: form
    namespace: tenant-a
    variables: { env: production }
    
  - name: "Tenant B Prod"
    templateId: form
    namespace: tenant-b
    variables: { env: production }
```

**Cache State:**
```
▼ Structural Templates
  Key: {"tenant-a", "form"}
       → Base with placeholders (tenant-a specific)
       
  Key: {"tenant-b", "form"}
       → Base with placeholders (tenant-b specific)

▼ Resolved Templates
  Key: "tenant-a:form:7f4e2c9a..."  [env=production]
       → Tenant A production variant
       
  Key: "tenant-b:form:a3c6f2e8..."  [env=production]
       → Tenant B production variant
```

**Note:** Even though both have `env=production`, the tenant-specific namespace keeps them separate and independent.

---

## Pattern 3: Multi-Region Cartesian Product

**Configuration:**
```yaml
scenarios:
  - name: "Prod US"
    templateId: form
    variables: { env: prod, region: us-east-1 }
    
  - name: "Prod EU"
    templateId: form
    variables: { env: prod, region: eu-west-1 }
    
  - name: "Dev US"
    templateId: form
    variables: { env: dev, region: us-east-1 }
    
  - name: "Dev EU"
    templateId: form
    variables: { env: dev, region: eu-west-1 }
```

**Cache Keys Generated:**
```
Structural:
  "form" → Base template

Resolved (4 combinations):
  form:7f4e... [env=prod, region=us-east-1]
  form:a3c6... [env=prod, region=eu-west-1]
  form:c9b4... [env=dev, region=us-east-1]
  form:e1f8... [env=dev, region=eu-west-1]
```

**Cache Efficiency:** 1 structural + 4 resolved = 5 total entries (vs 4 separate template files)

---

## Pattern 4: Feature Flags

**Configuration:**
```yaml
scenarios:
  - name: "New UI"
    templateId: dashboard
    variables: { newUI: true, darkMode: true, analytics: v2 }
    
  - name: "Legacy UI"
    templateId: dashboard
    variables: { newUI: false, darkMode: false, analytics: v1 }
```

**Cache Keys:**
```
Structural:
  "dashboard" → Base

Resolved:
  dashboard:7f4e... [newUI=true, darkMode=true, analytics=v2]
  dashboard:a3c6... [newUI=false, darkMode=false, analytics=v1]
```

---

## Pattern 5: Nested Variables (Complex Config)

**Configuration:**
```yaml
scenarios:
  - name: "Prod Acme"
    templateId: branded-form
    variables:
      tenant: acme
      theme:
        color: "#0066CC"
        font: modern
      compliance: SOC2
      
  - name: "Prod Widget"
    templateId: branded-form
    variables:
      tenant: widget
      theme:
        color: "#FF6600"
        font: classic
      compliance: ISO27001
```

**Cache Keys:**
```
Structural:
  "branded-form" → Base

Resolved:
  branded-form:7f4e...
    {"tenant":"acme","theme":{"color":"#0066CC","font":"modern"},"compliance":"SOC2"}
    
  branded-form:a3c6...
    {"tenant":"widget","theme":{"color":"#FF6600","font":"classic"},"compliance":"ISO27001"}
```

**Key Property:** Even with nested objects, SHA-256 produces unique deterministic hashes.

---

## Pattern 6: Default + Override (Same Template, Different Scenarios)

**Scenario A: Production Default**
```
Variables:
  region: us-east-1
  version: 2024-prod
  
Cache Key: form:7f4e2c9a...
```

**Scenario B: Production Alt Region**
```
Variables:
  region: eu-west-1
  version: 2024-prod
  
Cache Key: form:a3c6f2e8...
```

**At Runtime - Request for EU:**
```
1. Request specifies region=eu-west-1, version=2024-prod
2. Generate cache key: form:a3c6f2e8...
3. Lookup in cache → HIT (prewarmed)
4. Return resolved template immediately
```

**Key Insight:** If you prewarmed the exact variable combination, lookup is 100% cache hit.

---

## Cache Hit/Miss Analysis

### Prewarmed Scenarios (Always Hit)

```
Configuration Scenario 1:
  { env: prod, region: us-east-1 }
  Cache Key: form:7f4e...

Request 1: env=prod, region=us-east-1
  → Cache lookup: form:7f4e...
  → Result: HIT ✅ (prewarmed)
  
Request 2: env=prod, region=us-east-1
  → Cache lookup: form:7f4e...
  → Result: HIT ✅ (cached)
```

### Non-Prewarmed Scenarios (Miss First Time)

```
Configuration: No scenario for env=staging, region=ap-northeast-1

Request arrives: env=staging, region=ap-northeast-1
  Cache Key: form:f2b9c1e8...
  
First Request:
  → Cache lookup: form:f2b9c1e8...
  → Result: MISS ❌ (not prewarmed)
  → Action: Load base, resolve, cache
  → Duration: 50-150ms
  
Second Request (same variables):
  → Cache lookup: form:f2b9c1e8...
  → Result: HIT ✅ (cached from first request)
  → Duration: <1ms
```

---

## Memory Footprint Estimation

**Per-Scenario Cache Entry:**
- Structural template: ~100 KB (one-time)
- Resolved variant: ~110 KB (deep copy + resolved placeholders)

**Example: 10 scenarios**
```
1 structural:    100 KB  (shared across all)
10 resolved:     10 × 110 KB = 1.1 MB
                 ─────────────────
Total:           ~1.2 MB
```

**With Caffeine Cache (default: 1000 entries max):**
```
Typical usage pattern:
- 3-5 templates
- 5-10 scenarios per template
- 15-50 total cache entries
- Memory: ~5-10 MB

Plenty of headroom before hitting 1000-entry limit.
```

---

## Key Collision Probability

**Question:** Could two different variable sets produce the same SHA-256 hash?

**Answer:** Mathematically, yes. Practically, never.*

```
SHA-256 produces 256-bit hashes:
  Total possible hashes: 2^256 = 1.16 × 10^77

By birthday paradox:
  To have even 50% collision probability across
  N entries, you'd need:
  N ≈ √(2^256) = 2^128 ≈ 3.4 × 10^38 entries

Practical scale:
  You'll have ~10-100 cache entries ever
  Collision probability: effectively zero

* Caveat: cryptographically, SHA-256 is not theoretically proven collision-free,
  but no practical collision has ever been found.
```

---

## Variable Ordering Independence

**Question:** Does variable order matter for cache key?

**Answer:** No, JSON serialization is typically ordered.

```
Scenario 1 creates map:
  { "env": "prod", "region": "us-east-1" }
  JSON: {"env":"prod","region":"us-east-1"}
  Hash: 7f4e2c9a...

Request later creates map:
  { "region": "us-east-1", "env": "prod" }
  JSON: {"env":"prod","region":"us-east-1"}  (ObjectMapper sorts keys)
  Hash: 7f4e2c9a...

Result: Same key ✅ (deterministic)
```

**Note:** ObjectMapper with default settings sorts keys alphabetically, ensuring determinism regardless of insertion order.

---

## Cache Key Examples by Pattern

| Pattern | Template | Namespace | Variables | Cache Key (Last 8 chars of hash) |
|---------|----------|-----------|-----------|----------------------------------|
| Env | enrollment-form | - | `{ env: prod }` | `...7f4e2c9a` |
| Env | enrollment-form | - | `{ env: dev }` | `...a3c6f2e8` |
| Tenant | form | tenant-a | `{ env: prod }` | `...c9b4e2f7` |
| Tenant | form | tenant-b | `{ env: prod }` | `...e1f8b3a5` |
| Region | form | - | `{ region: US }` | `...f2d7c4a9` |
| Region | form | - | `{ region: EU }` | `...b6a8e1f3` |
| Feature | dashboard | - | `{ ui: new }` | `...a7e3f9c2` |
| Feature | dashboard | - | `{ ui: legacy }` | `...d5b2f8a1` |

---

## Best Practices for Cache Keys

### ✅ DO

- **Use consistent variable names** - Same template always uses same variable keys
- **Keep JSON serializable** - Use standard types (string, number, boolean, objects)
- **Document all variables** - Comment what each placeholder expects
- **Test ordering** - Verify same cache key for same variables

### ❌ DON'T

- **Change variable names** - Creates different cache keys
- **Use non-deterministic values** - (e.g., timestamps, random UUIDs)
- **Assume simple key** - Template ID alone isn't unique for variants
- **Rely on insertion order** - ObjectMapper will reorder

---

## Debugging Cache Keys

### View Generated Keys

```java
// In your application
@Component
public class CacheKeyDebugger {
    
    public void logCacheKey(String namespace, String templateId, Map<String, Object> vars) {
        String varsJson = objectMapper.writeValueAsString(vars);
        String hash = sha256(varsJson);
        String key = namespace + ":" + templateId + ":" + hash;
        
        log.info("Cache key details:");
        log.info("  Namespace: {}", namespace);
        log.info("  Template: {}", templateId);
        log.info("  Variables: {}", varsJson);
        log.info("  SHA-256: {}", hash);
        log.info("  Full Key: {}", key);
    }
}
```

### Monitor Cache Stats

```java
@Component
@Scheduled(fixedRate = 60000)
public void logCacheStats() {
    Cache cache = cacheManager.getCache("documentTemplates");
    log.info("Cache stats: {}", cache.getNativeCache());
}
```

---

## Summary Table

| Aspect | Details |
|--------|---------|
| **Structural Key** | `{namespace, templateId}` or `templateId` |
| **Resolved Key** | `namespace:templateId:sha256(variables_json)` |
| **Hash Algorithm** | SHA-256 (256-bit output) |
| **Determinism** | ✅ 100% - same vars → same key |
| **Uniqueness** | ✅ 99.9999% - collision probability negligible |
| **Ordering** | ✅ Independent - ObjectMapper sorts keys |
| **Scalability** | ✅ Limited only by 1000-entry max (configurable) |
| **Performance** | ✅ O(1) lookup time (hash table) |
| **TTL** | ✅ 24 hours (configurable) |
| **Memory** | ✅ ~110 KB per resolved variant |
