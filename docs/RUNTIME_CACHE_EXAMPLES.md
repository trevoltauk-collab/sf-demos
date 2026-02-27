# Practical Examples: Cache Usage in Real Requests

## Example 1: Production Request with Prewarmed Variant

### Prewarming Configuration

```yaml
docgen:
  templates:
    prewarming:
      scenarios:
        - name: "Production US"
          templateId: enrollment-form
          namespace: tenant-a
          variables:
            environment: production
            region: us-east-1
            version: 2024-prod
```

### Request Method 1: Query Parameters

**HTTP Request:**
```
POST /api/documents/generate?environment=production&region=us-east-1&version=2024-prod
Content-Type: application/json

{
  "namespace": "tenant-a",
  "templateId": "enrollment-form",
  "data": {
    "applicant": {
      "firstName": "John",
      "lastName": "Doe",
      "email": "john@example.com"
    }
  }
}
```

**Processing:**
```
1. Controller receives request
2. Extracts variables from query params:
   variables = { 
     "environment": "production",
     "region": "us-east-1", 
     "version": "2024-prod"
   }
3. Generates cache key:
   SHA-256({"environment":"production","region":"us-east-1","version":"2024-prod"})
   → ca2f8b1d9e3c5f7a...
   → Full key: "tenant-a:enrollment-form:ca2f8b1d9e3c5f7a..."
4. Cache lookup: HIT ✓ (prewarmed)
5. Returns prewarmed resolved template
6. Renders document with request data
```

**Response (Success):**
```
HTTP/1.1 200 OK
Content-Type: application/pdf
Content-Disposition: attachment; filename=document.pdf
Content-Length: 245678

[PDF binary data]
```

**Logs:**
```
INFO: Received document generation request for template: enrollment-form from namespace: tenant-a
DEBUG: Using variables from query parameters: [environment, region, version]
DEBUG: Cache key for template resolution: tenant-a:enrollment-form:ca2f...
DEBUG: Cache HIT for key: tenant-a:enrollment-form:ca2f8b1d9e3c5f7a...
INFO: Document generated successfully
```

---

### Request Method 2: Variables in Request Body

**HTTP Request:**
```
POST /api/documents/generate
Content-Type: application/json

{
  "namespace": "tenant-a",
  "templateId": "enrollment-form",
  "variables": {
    "environment": "production",
    "region": "us-east-1",
    "version": "2024-prod"
  },
  "data": {
    "applicant": {
      "firstName": "Jane",
      "lastName": "Smith"
    }
  }
}
```

**Processing:**
```
1. Controller receives request
2. Extracts variables from request body:
   variables = request.getVariables()
   → { "environment": "production", "region": "us-east-1", "version": "2024-prod" }
3. Generates cache key:
   → "tenant-a:enrollment-form:ca2f8b1d9e3c5f7a..."
4. Cache lookup: HIT ✓ (same key as Method 1)
5. Returns prewarmed resolved template
```

**Key Insight:** Both methods produce the same cache key → same cache hit!

---

### Request Method 3: Default Variables

**HTTP Request:**
```
POST /api/documents/generate
Content-Type: application/json

{
  "namespace": "tenant-a",
  "templateId": "enrollment-form",
  "data": {
    "applicant": {
      "firstName": "Bob",
      "lastName": "Johnson"
    }
  }
}
```

**Configuration (application.yml):**
```yaml
docgen:
  default-environment: production
  default-region: us-east-1
  default-version: 2024-prod
```

**Processing:**
```
1. Controller receives request (no variables provided)
2. No query params, no body variables
3. Uses application defaults:
   variables = { 
     "environment": "production",
     "region": "us-east-1",
     "version": "2024-prod"
   }
4. Generates cache key:
   → "tenant-a:enrollment-form:ca2f8b1d9e3c5f7a..."
5. Cache lookup: HIT ✓ (defaults match prewarmed scenario)
6. Auto-cache hit without explicit variable passing!
```

**Logs:**
```
DEBUG: Using default variables from configuration
DEBUG: Cache key for template resolution: tenant-a:enrollment-form:ca2f...
DEBUG: Cache HIT for key: tenant-a:enrollment-form:ca2f8b1d9e3c5f7a...
```

---

## Example 2: Non-Prewarmed Variant (First Request Slower)

### Configuration

```yaml
prewarming:
  scenarios:
    - name: "Production US"
      templateId: enrollment-form
      variables: { environment: production, region: us-east-1 }
    
    # NOTE: No scenario for APAC region
```

### Request: Asia-Pacific Region (Not Prewarmed)

**HTTP Request:**
```
POST /api/documents/generate?environment=production&region=ap-northeast-1
Content-Type: application/json

{
  "namespace": "tenant-a",
  "templateId": "enrollment-form",
  "data": { "applicant": { "firstName": "Alice", "lastName": "Chen" } }
}
```

### Request 1 (First Time - Slower)

**Processing:**
```
1. Extract variables:
   variables = { "environment": "production", "region": "ap-northeast-1" }

2. Generate cache key:
   SHA-256("{\"environment\":\"production\",\"region\":\"ap-northeast-1\"}")
   → f1a7d3b8c9e2f5...
   → Full key: "tenant-a:enrollment-form:f1a7d3b8c9e2f5..."

3. Cache lookup: MISS ✗
   (Not in prewarming scenarios, first request to this region)

4. Load base template:
   Load template from file: tenant-a/templates/enrollment-form.yaml

5. Deep copy and resolve:
   - Copy template
   - Interpolate ${environment} → production
   - Interpolate ${region} → ap-northeast-1
   - Time: 50-150ms

6. Cache new variant:
   cache.put("tenant-a:enrollment-form:f1a7d3b8c9e2f5...", resolvedTemplate)

7. Render document:
   Time: 500-2000ms

Total Time: ~550-2150ms (slower due to load + resolve)
```

**Response:**
```
HTTP/1.1 200 OK
Content-Type: application/pdf
[PDF data]
```

**Logs:**
```
DEBUG: Using variables from query parameters: [environment, region]
DEBUG: Cache key for template resolution: tenant-a:enrollment-form:f1a7d3b8c9e2f5...
DEBUG: Cache MISS for key: tenant-a:enrollment-form:f1a7d3b8c9e2f5...
DEBUG: Loading template from file: tenant-a/templates/enrollment-form.yaml
DEBUG: Resolving placeholders for region=ap-northeast-1
DEBUG: Caching new variant for future use
INFO: Document generated successfully
```

### Request 2 (Same Region - Same Day, Fast)

**HTTP Request:**
```
POST /api/documents/generate?environment=production&region=ap-northeast-1
Content-Type: application/json

{
  "namespace": "tenant-a",
  "templateId": "enrollment-form",
  "data": { "applicant": { "firstName": "Bob", "lastName": "Chen" } }
}
```

**Processing:**
```
1. Extract variables:
   variables = { "environment": "production", "region": "ap-northeast-1" }

2. Generate cache key:
   → "tenant-a:enrollment-form:f1a7d3b8c9e2f5..." (same as before)

3. Cache lookup: HIT ✓
   (Now in cache from request 1)

4. Return cached variant immediately:
   Time: < 1ms

5. Render document:
   Time: 500-2000ms

Total Time: ~500-2001ms (fast, no load/resolve needed)
```

**Logs:**
```
DEBUG: Cache HIT for key: tenant-a:enrollment-form:f1a7d3b8c9e2f5...
DEBUG: Using cached template variant
INFO: Document generated successfully
```

**Improvement:** 50-150ms faster due to cache (cached after first request)

---

## Example 3: Multi-Tenant Request

### Configuration

```yaml
prewarming:
  scenarios:
    - name: "Tenant A Production"
      templateId: branded-form
      namespace: tenant-a
      variables: { tenant: acme, theme: corporate }
      
    - name: "Tenant B Production"
      templateId: branded-form
      namespace: tenant-b
      variables: { tenant: widget, theme: modern }
```

### Tenant A Request

**HTTP Request:**
```
POST /api/documents/generate?tenant=acme&theme=corporate
Content-Type: application/json

{
  "namespace": "tenant-a",
  "templateId": "branded-form",
  "data": { "company": "Acme Corp", "branch": "NYC" }
}
```

**Cache Lookup:**
```
Cache Key: "tenant-a:branded-form:a3f7e2d1...
Result: HIT ✓ (Tenant A prewarmed variant)
```

### Tenant B Request

**HTTP Request:**
```
POST /api/documents/generate?tenant=widget&theme=modern
Content-Type: application/json

{
  "namespace": "tenant-b",
  "templateId": "branded-form",
  "data": { "company": "Widget Inc", "branch": "LA" }
}
```

**Cache Lookup:**
```
Cache Key: "tenant-b:branded-form:c7f9b4a2...
Result: HIT ✓ (Tenant B prewarmed variant)
```

**Isolation:** Different namespaces → different cache keys → no tenant data leakage ✓

---

## Example 4: Excel Generation (Same Cache Pattern)

**HTTP Request:**
```
POST /api/documents/generate/excel?environment=production&region=us-east-1
Content-Type: application/json

{
  "namespace": "tenant-a",
  "templateId": "enrollment-sheet",
  "data": {
    "applicants": [
      { "firstName": "John", "lastName": "Doe" },
      { "firstName": "Jane", "lastName": "Smith" }
    ]
  }
}
```

**Processing:**
```
1. Extract variables from query params:
   variables = { "environment": "production", "region": "us-east-1" }

2. Generate cache key:
   → "tenant-a:enrollment-sheet:ca2f8b1d..."

3. Cache lookup: HIT ✓ (if prewarmed)
   or MISS → Load → Cache (if not prewarmed)

4. Generate Excel file
5. Return as XLSX attachment
```

**Response:**
```
HTTP/1.1 200 OK
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
Content-Disposition: attachment; filename=document.xlsx
Content-Length: 124567

[XLSX binary data]
```

---

## Cache Performance Summary

| Scenario | Variables | Cache State | Time | Notes |
|----------|-----------|-------------|------|-------|
| Prewarmed + Matching Vars | Query params | HIT | < 1ms lookup | Fastest path |
| Prewarmed + Default Vars | None | HIT | < 1ms lookup | Auto cache hit |
| Non-prewarmed 1st request | Custom vars | MISS | 50-150ms | Load + resolve |
| Non-prewarmed 2nd+ request | Same vars | HIT | < 1ms lookup | Now cached |
| Multi-tenant same template | Different NS | Separate keys | < 1ms each | Isolated caches |

---

## Error Cases

### Missing Variable (Unresolved Placeholder)

**Request:**
```
POST /api/documents/generate?region=us-east-1
(Missing 'environment' variable)
{
  "namespace": "tenant-a",
  "templateId": "enrollment-form",
  "data": {...}
}
```

**Template:**
```yaml
templatePath: forms/enrollment-${environment}.pdf
condition: "$.status == '${environment}'"
```

**Processing:**
```
Cache lookup: MISS (different variables)
Load base template
Try to interpolate: ${environment} → ??? (not in variables)
Throw: TemplateLoadingException with UNRESOLVED_PLACEHOLDER code
```

**Response (400 Bad Request):**
```json
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "code": "UNRESOLVED_PLACEHOLDER",
  "description": "Unresolved placeholder 'environment' in section 'form-section'"
}
```

**Logs:**
```
DEBUG: Using variables from query parameters: [region]
DEBUG: Cache key for template resolution: tenant-a:enrollment-form:...
DEBUG: Cache MISS for key
ERROR: Unresolved placeholder 'environment' - variable not provided
ERROR: TemplateLoadingException: UNRESOLVED_PLACEHOLDER
```

---

### Template Not Found

**Request:**
```
POST /api/documents/generate
{
  "namespace": "tenant-a",
  "templateId": "nonexistent-form",
  "data": {...}
}
```

**Response (404 Not Found):**
```json
HTTP/1.1 404 Not Found
Content-Type: application/json

{
  "code": "TEMPLATE_NOT_FOUND",
  "description": "Template 'nonexistent-form' not found in namespace 'tenant-a'"
}
```

---

## Performance Comparison: With vs Without Caching

### Without Caching (Each Request Loads File)

```
Request 1 (Prod):     2500ms  (Load + Interpolate + Render)
Request 2 (Prod):     2500ms  (Load + Interpolate + Render) ← Duplicate work
Request 3 (Staging):  2500ms  (Load + Interpolate + Render) ← Duplicate work
Request 4 (Prod):     2500ms  (Load + Interpolate + Render) ← Duplicate work
─────────────────────────────
Total for 4 requests: 10,000ms (100% file I/O + interpolation overhead)
```

### With Prewarming + Caching (Optimal)

```
Startup (Prewarming):        300ms  (Load + Interpolate 3 scenarios)
Request 1 (Prod prewarmed):  500ms  (Cache hit + Render)
Request 2 (Prod prewarmed):  500ms  (Cache hit + Render)
Request 3 (Staging unknown): 2000ms (Load + Interpolate + Render + Cache)
Request 4 (Staging cached):  500ms  (Cache hit + Render)
─────────────────────────────
Total for 4 requests:        3800ms
Startup overhead:              300ms
─────────────────────────────
Overall:                     4100ms

Savings vs. no-cache: 5900ms (59% faster!)
```

### Breakeven Point

Without caching needs: **~5-6 unique scenarios** before prewarming pays off in first hour.

With prewarming: Break-even achieved immediately for prewarmed scenarios.

---

## Testing Your Cache Implementation

### Manual cURL Tests

**Test 1: Query Parameters (Prewarmed)**
```bash
curl -X POST \
  "http://localhost:8080/api/documents/generate?environment=production&region=us-east-1" \
  -H "Content-Type: application/json" \
  -d '{
    "namespace": "tenant-a",
    "templateId": "enrollment-form",
    "data": {"applicant": {"firstName": "John", "lastName": "Doe"}}
  }' \
  --output document.pdf
```

**Test 2: Body Variables (Non-Prewarmed)**
```bash
curl -X POST \
  "http://localhost:8080/api/documents/generate" \
  -H "Content-Type: application/json" \
  -d '{
    "namespace": "tenant-a",
    "templateId": "enrollment-form",
    "variables": {
      "environment": "staging",
      "region": "ap-northeast-1"
    },
    "data": {"applicant": {"firstName": "Jane", "lastName": "Smith"}}
  }' \
  --output document.pdf
```

**Test 3: No Variables (Uses Defaults)**
```bash
curl -X POST \
  "http://localhost:8080/api/documents/generate" \
  -H "Content-Type: application/json" \
  -d '{
    "namespace": "tenant-a",
    "templateId": "enrollment-form",
    "data": {"applicant": {"firstName": "Bob", "lastName": "Jones"}}
  }' \
  --output document.pdf
```

### Check Logs for Cache Hits

Look for:
```
DEBUG: Cache key for template resolution: tenant-a:enrollment-form:ca2f...
DEBUG: Cache HIT for key: tenant-a:enrollment-form:ca2f8b1d...
```

Or cache misses:
```
DEBUG: Cache MISS for key: tenant-a:enrollment-form:f1a7d...
DEBUG: Loading template from file: tenant-a/templates/enrollment-form.yaml
```

---

## Key Takeaways

1. **Variables drive cache keys** - Same variables from different sources (query/body/headers) = same cache key
2. **Prewarming eliminates first-request latency** - < 1ms lookup for prewarmed scenarios
3. **Non-prewarmed variants self-cache** - After first request, subsequent requests are fast
4. **Multi-tenant isolation** - Different namespaces = different cache keys = secure
5. **Defaults provide safety** - Missing variables use app config, preventing crashes
6. **Error handling** - Unresolved placeholders caught at load time (fail-fast)
