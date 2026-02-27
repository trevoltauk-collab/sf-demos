# Runtime Cache Lookup: How Variables Drive Cache Keys

## The Challenge

**Prewarmed cache keys include variable hashes:**
```
Cache has:  "tenant-a:enrollment-form:7f4e2c9a1d5b8f3e..." (prod variant)
But request gives only: namespace + templateId
```

**How do we hit the cache?** The request must provide variables to construct the matching cache key.

---

## Solution Overview

Cache keys are constructed in **three ways** from the request:

```
┌─ Option 1: Explicit Variables in Request
│  └─ GET /api/documents/generate?environment=production&region=us-east-1
│
├─ Option 2: Variables in Request Body
│  └─ POST /api/documents/generate
│     { "namespace": "...", "templateId": "...", "variables": {...}, "data": {...} }
│
└─ Option 3: Context-Derived Variables
   └─ From headers, principal, application config (defaults)
```

---

## Pattern 1: Query Parameters (Simplest)

### Request

```
POST /api/documents/generate?environment=production&region=us-east-1
Content-Type: application/json

{
  "namespace": "tenant-a",
  "templateId": "enrollment-form",
  "data": {
    "applicant": { "firstName": "John", "lastName": "Doe" }
  }
}
```

### Controller Enhancement

```java
@PostMapping("/generate")
public ResponseEntity<?> generateDocument(
        @RequestBody DocumentGenerationRequest request,
        @RequestParam(required = false) Map<String, String> queryParams) {
    
    // Extract variables from query parameters
    Map<String, Object> variables = new HashMap<>();
    if (queryParams != null && !queryParams.isEmpty()) {
        variables.putAll(queryParams);  // environment, region, etc.
    }
    
    // Pass default variables if not provided
    if (variables.isEmpty()) {
        variables = getDefaultVariables(request.getNamespace());
    }
    
    // Add variables to request for downstream processing
    request.setVariables(variables);
    
    // DocumentComposer now receives variables for cache lookup
    byte[] pdf = documentComposer.generateDocument(request);
    
    // Return response...
}
```

### Cache Lookup Flow

```
Request: ?environment=production&region=us-east-1

Step 1: Extract variables from query params
  variables = { "environment": "production", "region": "us-east-1" }

Step 2: Construct cache key
  cacheKey = "tenant-a:enrollment-form:7f4e2c9a..."  (hash of variables)

Step 3: Lookup in cache
  cache.get("tenant-a:enrollment-form:7f4e2c9a...")
  Result: HIT ✓ (matches prewarmed Production scenario)

Step 4: Return resolved template
  Time: < 1ms (cached, not loaded from filesystem)
```

---

## Pattern 2: Variables in Request Body

### Enhanced Request DTO

```java
@Data
@NoArgsConstructor
public class DocumentGenerationRequest {
    private String namespace;
    private String templateId;
    
    // NEW: Explicit variables for template resolution
    private Map<String, Object> variables;
    
    private Map<String, Object> data;
}
```

### Request

```
POST /api/documents/generate
Content-Type: application/json

{
  "namespace": "tenant-a",
  "templateId": "enrollment-form",
  "variables": {
    "environment": "production",
    "region": "us-east-1"
  },
  "data": {
    "applicant": { "firstName": "John", "lastName": "Doe" }
  }
}
```

### Cache Lookup Logic

```java
@PostMapping("/generate")
public ResponseEntity<?> generateDocument(@RequestBody DocumentGenerationRequest request) {
    
    // If variables not provided, use defaults
    if (request.getVariables() == null || request.getVariables().isEmpty()) {
        request.setVariables(getDefaultVariables(request.getNamespace()));
    }
    
    // DocumentComposer uses variables to construct cache key
    byte[] pdf = documentComposer.generateDocument(request);
    
    return ResponseEntity.ok(...)
}
```

**Cache Key Construction:**
```
variables: { "environment": "production", "region": "us-east-1" }
↓
SHA-256("{\"environment\":\"production\",\"region\":\"us-east-1\"}")
↓
7f4e2c9a1d5b8f3e...
↓
Cache Key: "tenant-a:enrollment-form:7f4e2c9a..."
↓
Cache Lookup Result: HIT ✓
```

---

## Pattern 3: Context-Derived Variables

### From Request Headers

```java
@PostMapping("/generate")
public ResponseEntity<?> generateDocument(
        @RequestBody DocumentGenerationRequest request,
        @RequestHeader(value = "X-Environment", required = false) String environment,
        @RequestHeader(value = "X-Region", required = false) String region) {
    
    // Build variables from headers
    Map<String, Object> variables = new HashMap<>();
    
    if (environment != null) {
        variables.put("environment", environment);
    }
    if (region != null) {
        variables.put("region", region);
    }
    
    // Use defaults for missing headers
    if (variables.isEmpty()) {
        variables = getDefaultVariables(request.getNamespace());
    }
    
    request.setVariables(variables);
    byte[] pdf = documentComposer.generateDocument(request);
    return ResponseEntity.ok(...);
}
```

**Request Example:**
```
POST /api/documents/generate
X-Environment: production
X-Region: us-east-1
X-Tenant: tenant-a

{
  "templateId": "enrollment-form",
  "data": {...}
}
```

### From Application Configuration

```java
@Configuration
public class CacheVariablesConfig {
    
    @Value("${docgen.default-environment:production}")
    private String defaultEnvironment;
    
    @Value("${docgen.default-region:us-east-1}")
    private String defaultRegion;
    
    public Map<String, Object> getDefaultVariables(String namespace) {
        return Map.of(
            "environment", defaultEnvironment,
            "region", defaultRegion,
            "namespace", namespace
        );
    }
}
```

**Configuration (application.yml):**
```yaml
docgen:
  default-environment: production
  default-region: us-east-1
  
  templates:
    prewarming:
      scenarios:
        - name: "Production"
          templateId: enrollment-form
          variables:
            environment: production
            region: us-east-1
```

---

## Pattern 4: From Authenticated User Context

### Extract from Security Principal

```java
@PostMapping("/generate")
public ResponseEntity<?> generateDocument(
        @RequestBody DocumentGenerationRequest request,
        Authentication authentication) {
    
    // Extract variables from authenticated user's context
    Map<String, Object> variables = extractVariablesFromPrincipal(authentication);
    
    // Fallback to defaults if not in principal
    if (variables.isEmpty()) {
        variables = getDefaultVariables(request.getNamespace());
    }
    
    request.setVariables(variables);
    byte[] pdf = documentComposer.generateDocument(request);
    return ResponseEntity.ok(...);
}

private Map<String, Object> extractVariablesFromPrincipal(Authentication auth) {
    Map<String, Object> variables = new HashMap<>();
    
    if (auth != null && auth.getPrincipal() instanceof UserDetails) {
        UserDetails user = (UserDetails) auth.getPrincipal();
        // Extract from user's attributes (e.g., from claims, custom objects)
        // This would typically come from JWT claims or user database
        // Example: user.getAttributes().get("environment")
    }
    
    return variables;
}
```

---

## Complete End-to-End Flow with Cache

### Request Arrives

```
POST /api/documents/generate?environment=production&region=us-east-1
{
  "namespace": "tenant-a",
  "templateId": "enrollment-form",
  "data": { "applicant": { "firstName": "John" } }
}
```

### DocumentController

```java
@PostMapping("/generate")
public ResponseEntity<?> generateDocument(
        @RequestBody DocumentGenerationRequest request,
        @RequestParam(required = false) Map<String, String> queryParams) {
    
    // 1. Extract variables from request
    Map<String, Object> variables = extractVariables(request, queryParams);
    request.setVariables(variables);
    
    // 2. Pass to composer (variables now included)
    byte[] pdf = documentComposer.generateDocument(request);
    
    // 3. Return response
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
        .body(pdf);
}

private Map<String, Object> extractVariables(
        DocumentGenerationRequest request,
        Map<String, String> queryParams) {
    
    Map<String, Object> variables = new HashMap<>();
    
    // Priority 1: Explicit variables in request body
    if (request.getVariables() != null && !request.getVariables().isEmpty()) {
        variables.putAll(request.getVariables());
    }
    
    // Priority 2: Query parameters
    if (queryParams != null && !queryParams.isEmpty()) {
        variables.putAll(queryParams);
    }
    
    // Priority 3: Defaults from config
    if (variables.isEmpty()) {
        variables.putAll(getDefaultVariables(request.getNamespace()));
    }
    
    return variables;
}
```

### DocumentComposer

```java
@Component
public class DocumentComposer {
    
    public byte[] generateDocument(DocumentGenerationRequest request) {
        // Extract variables
        Map<String, Object> variables = request.getVariables();
        
        // 1. Load or get from cache (structural)
        DocumentTemplate baseTemplate = templateLoader.loadTemplate(
            request.getNamespace(),
            request.getTemplateId()
        );
        
        // 2. If variables provided, resolve to prewarmed variant
        if (variables != null && !variables.isEmpty()) {
            // THIS IS KEY: Use variables to construct cache key
            DocumentTemplate resolvedTemplate = getOrResolveTemplate(
                request.getNamespace(),
                request.getTemplateId(),
                variables
            );
            
            return renderDocument(resolvedTemplate, request.getData());
        }
        
        // 3. Otherwise use base template as-is
        return renderDocument(baseTemplate, request.getData());
    }
    
    private DocumentTemplate getOrResolveTemplate(
            String namespace,
            String templateId,
            Map<String, Object> variables) {
        
        // Generate cache key from variables
        String cacheKey = generateCacheKey(namespace, templateId, variables);
        
        // Check if resolved variant is cached
        DocumentTemplate cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            log.info("Cache HIT for {}", cacheKey);
            return cached;  // ← FAST PATH (prewarmed)
        }
        
        log.info("Cache MISS for {}", cacheKey);
        
        // Load base template
        DocumentTemplate baseTemplate = templateLoader.loadTemplate(namespace, templateId);
        
        // Resolve placeholders
        DocumentTemplate resolved = deepCopy(baseTemplate);
        templateLoader.interpolateTemplateFields(resolved, variables);
        
        // Cache for next request
        cache.put(cacheKey, resolved);
        
        return resolved;  // ← SLOWER PATH (first occurrence of this variable combo)
    }
    
    private String generateCacheKey(String namespace, String templateId, Map<String, Object> variables) {
        try {
            String varsJson = objectMapper.writeValueAsString(variables);
            String hash = sha256(varsJson);
            return namespace + ":" + templateId + ":" + hash;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate cache key", e);
        }
    }
}
```

### Cache Lookup Result

```
Cache Key: "tenant-a:enrollment-form:7f4e2c9a1d5b8f3e..."
Lookup: HIT ✓

Return: Prewarmed resolved template
├─ templatePath: forms/form-production.pdf  (resolved)
├─ condition: $.env == 'PROD'              (resolved)
└─ All resources cached

Time: < 1ms
```

---

## Cache Hit/Miss Scenarios

### Scenario 1: Prewarmed Environment (FAST)

```
Configuration has:
  - name: "Production"
    templateId: enrollment-form
    variables: { environment: production, region: us-east-1 }

Request arrives:
  POST /api/documents/generate?environment=production&region=us-east-1
  ├─ Extract variables: { environment: production, region: us-east-1 }
  ├─ Generate cache key: "tenant-a:enrollment-form:7f4e..."
  ├─ Cache lookup: HIT ✓
  └─ Time: < 1ms

Result: Immediate hit, use prewarmed variant
```

### Scenario 2: Non-Prewarmed Environment (SLOWER FIRST TIME)

```
Configuration does NOT have:
  environment: staging, region: ap-northeast-1

Request 1 (first time):
  POST /api/documents/generate?environment=staging&region=ap-northeast-1
  ├─ Extract variables: { environment: staging, region: ap-northeast-1 }
  ├─ Generate cache key: "tenant-a:enrollment-form:f1b9..."
  ├─ Cache lookup: MISS ✗
  ├─ Load → Copy → Interpolate: 50-150ms
  ├─ Cache new variant
  └─ Time: 50-150ms

Request 2 (same variables):
  POST /api/documents/generate?environment=staging&region=ap-northeast-1
  ├─ Extract variables: { environment: staging, region: ap-northeast-1 }
  ├─ Generate cache key: "tenant-a:enrollment-form:f1b9..."
  ├─ Cache lookup: HIT ✓
  └─ Time: < 1ms

Result: First occurrence slower, subsequent requests fast
```

### Scenario 3: No Variables Provided (USES DEFAULTS)

```
Request (no variables specified):
  POST /api/documents/generate
  {
    "namespace": "tenant-a",
    "templateId": "enrollment-form",
    "data": {...}
  }

Processing:
  ├─ No query params, no body variables
  ├─ Use default config: { environment: production, region: us-east-1 }
  ├─ Generate cache key: "tenant-a:enrollment-form:7f4e..."
  ├─ Cache lookup: HIT ✓ (matches default scenario)
  └─ Time: < 1ms

Result: Falls back to prewarmed default variant
```

---

## Request Patterns Comparison

| Pattern | Request | Variable Source | Cache Key | Hit Rate |
|---------|---------|-----------------|-----------|----------|
| **Query Params** | `?env=prod` | URL query string | Composed from params | High (if prewarmed) |
| **Body Variables** | `{"variables":{...}}` | Request JSON body | Composed from body | High (if prewarmed) |
| **Headers** | `X-Environment: prod` | HTTP headers | Composed from headers | Medium (fewer pre-configs) |
| **Config Defaults** | None provided | application.yml | Composed from defaults | Very High (always matches default) |
| **No Variables** | None provided | None | Structure only | Uses base template |

---

## Practical Implementation

### Best Practice Request Handler

```java
@PostMapping("/generate")
public ResponseEntity<?> generateDocument(
        @RequestBody DocumentGenerationRequest request,
        @RequestParam(required = false) Map<String, String> queryParams,
        @RequestHeader(value = "X-Environment", required = false) String envHeader,
        @RequestHeader(value = "X-Region", required = false) String regionHeader) {
    
    try {
        // Build variables with priority order
        Map<String, Object> variables = new LinkedHashMap<>();
        
        // 1. Request body variables (highest priority)
        if (request.getVariables() != null) {
            variables.putAll(request.getVariables());
        }
        
        // 2. Query parameters
        if (queryParams != null) {
            variables.putAll(queryParams);
        }
        
        // 3. Request headers
        if (envHeader != null) variables.put("environment", envHeader);
        if (regionHeader != null) variables.put("region", regionHeader);
        
        // 4. Application defaults
        if (variables.isEmpty()) {
            variables.putAll(getDefaultVariables(request.getNamespace()));
        }
        
        // Add to request
        request.setVariables(variables);
        
        // Log cache key for debugging
        String cacheKey = generateCacheKey(request.getNamespace(), request.getTemplateId(), variables);
        log.info("Using cache key: {}", cacheKey);
        
        // Generate document (will use cache)
        byte[] pdf = documentComposer.generateDocument(request);
        
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=document.pdf")
            .body(pdf);
            
    } catch (TemplateLoadingException e) {
        return handleTemplateError(e);
    }
}
```

---

## Key Insights

| Point | Implication |
|-------|-------------|
| **Cache key = variables hash** | Must pass same variables to hit prewarmed cache |
| **Multiple variable sources** | Can come from query params, body, headers, or config |
| **Priority matters** | Body > Query > Headers > Defaults |
| **Default scenario** | No variables = automatic cache hit (if defaults prewarmed) |
| **Deterministic** | Same variables always generate same cache key |
| **First request slower** | New variable combinations require load + interpolate |
| **Subsequent fast** | Same variables = cache hit < 1ms |

---

## Example: Complete Request Journey

```yaml
# Configuration
prewarming.scenarios:
  - name: "Production"
    templateId: enrollment-form
    variables:
      environment: production
      region: us-east-1

# Request 1: Query params (hits cache)
GET /api/documents/generate?environment=production&region=us-east-1
→ Cache Key: ...7f4e2c9a...
→ HIT ✓ (prewarmed)

# Request 2: Headers (hits cache)
POST /api/documents/generate (X-Environment: production, X-Region: us-east-1)
→ Cache Key: ...7f4e2c9a...
→ HIT ✓ (same key as above)

# Request 3: No variables (uses defaults from config)
POST /api/documents/generate {}
→ Cache Key: ...7f4e2c9a... (defaults match prewarmed)
→ HIT ✓ (auto-cache hit)

# Request 4: Different region (not prewarmed, first time)
GET /api/documents/generate?environment=production&region=ap-northeast-1
→ Cache Key: ...f1b9e3c7...
→ MISS ✗ (not prewarmed, but now cached for next request)
→ Time: 50-150ms

# Request 5: Same region as #4 (cache now has it)
GET /api/documents/generate?environment=production&region=ap-northeast-1
→ Cache Key: ...f1b9e3c7...
→ HIT ✓ (cached from request #4)
```

This is how the variables in the request enable cache hits! 🎯
