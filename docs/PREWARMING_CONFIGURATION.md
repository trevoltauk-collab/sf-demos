# Template Prewarming with Placeholder Variables - Configuration Guide

## Overview

The template prewarming system now supports three strategies for warming the cache at startup:

1. **Simple Template IDs** - Load templates from default namespace
2. **Namespace-Specific** - Load templates from specific tenants/namespaces
3. **Scenarios with Variables** - Load templates and resolve placeholders with specific variable scenarios

This guide covers **Strategy 3: Scenarios with Variables**.

## Benefits

- **Eliminates First-Request Latency**: Common scenarios are prewarmed and ready
- **Fail-Fast Validation**: Template loading and placeholder resolution errors detected at startup
- **Caches Multiple Resolved Variants**: E.g., production, staging, and development versions all cached
- **Environment-Specific Defaults**: Tenant-specific or region-specific presets loaded automatically
- **Reduces Runtime Interpolation**: Frequently-used resolutions already cached and available

## Configuration

### Basic Example

In `application.yml`:

```yaml
docgen:
  templates:
    prewarming:
      enabled: true
      scenarios:
        - name: "Production Enrollment"
          templateId: "enrollment-form"
          namespace: "tenant-a"
          variables:
            environment: production
            formType: enrollment
            version: 2024-prod
            region: us-east-1
```

### What Happens During Startup

For each scenario configured, the TemplateCacheWarmer will:

1. **Load the base template** (structural cache)
   ```
   DocumentTemplate baseTemplate = templateLoader.loadTemplate(namespace, templateId)
   ```

2. **Deep-copy** the template to preserve the cached original
   ```
   DocumentTemplate resolvedTemplate = deepCopy(baseTemplate)
   ```

3. **Interpolate placeholders** with scenario variables
   ```
   templateLoader.interpolateTemplateFields(resolvedTemplate, variables)
   ```
   - Replaces `${formType}` → `"enrollment"`
   - Replaces `${environment}` → `"production"`
   - Replaces `${region}` → `"us-east-1"`

4. **Pre-load resources** (PDFs, FTLs) from resolved paths
   ```
   warmResources(resolvedTemplate)
   ```

### Log Output

Startup logs will show:

```
[INFO] Starting template cache warming
[INFO] Preloading 3 scenario(s) with placeholder variables
[INFO]   Scenario 'Production Enrollment': tenant-a/enrollment-form
[DEBUG]   Description: Production defaults for enrollment form
[DEBUG]     Resource: forms/enrollment-2024-prod.pdf
[DEBUG]     Resource: styles/prod-colors.ftl
[INFO]   Scenario 'Development Enrollment': tenant-a/enrollment-form
[INFO]   Scenario 'Staging Enrollment': tenant-a/enrollment-form
[INFO] Template cache warming completed in 234ms
```

## Configuration Reference

### PrewarmingConfiguration (YAML)

```yaml
docgen:
  templates:
    prewarming:
      enabled: true                    # Enable/disable scenarios (default: true)
      scenarios:
        - name: String                 # Display name for logs (required)
          templateId: String           # Template ID to load (required)
          namespace: String            # Namespace/tenant (optional, defaults to common-templates)
          description: String          # Description for documentation (optional)
          interpolateFields: boolean   # Resolve fields with variables (default: true)
          variables:                   # Map of variables to substitute
            key: value
```

### Supported Variable Types

Variables can be any type that Jackson can serialize/deserialize:

```yaml
variables:
  # Strings
  environment: "production"
  region: "us-east-1"
  
  # Numbers
  version: 2024
  formType: 1
  
  # Booleans
  isFormRequired: true
  isPdfRequired: false
  
  # Nested objects (arbitrary depth)
  config:
    formVersion: "2.0"
    colors:
      primary: "#0066CC"
      secondary: "#CCCCCC"
```

Nested variables can be accessed in templates like:
- `${config.formVersion}` → `"2.0"`
- `${config.colors.primary}` → `"#0066CC"`

## Configuration Patterns

### 1. Environment Defaults

Prewarm with production, staging, and development settings:

```yaml
docgen:
  templates:
    prewarming:
      scenarios:
        - name: "Production"
          templateId: "enrollment"
          variables:
            environment: production
            version: 2024-prod
            region: us-east-1
            
        - name: "Staging"
          templateId: "enrollment"
          variables:
            environment: staging
            version: 2024-staging
            region: us-west-2
            
        - name: "Development"
          templateId: "enrollment"
          variables:
            environment: development
            version: dev
            region: local
```

### 2. Multi-Tenant Defaults

Different configurations per tenant:

```yaml
docgen:
  templates:
    prewarming:
      scenarios:
        - name: "Tenant A - Production"
          templateId: "enrollment-form"
          namespace: "tenant-a"
          variables:
            environment: production
            region: us-east-1
            
        - name: "Tenant B - Production"
          templateId: "simple-form"
          namespace: "tenant-b"
          variables:
            environment: production
            region: eu-west-1
```

### 3. Region-Specific Variants

Prewarm for multi-region deployments:

```yaml
docgen:
  templates:
    prewarming:
      scenarios:
        - name: "US East"
          templateId: "enrollment-form"
          variables:
            region: us-east-1
            locales: "en_US"
            
        - name: "EU West"
          templateId: "enrollment-form"
          variables:
            region: eu-west-1
            locales: "en_GB,de_DE,fr_FR"
            
        - name: "APAC"
          templateId: "enrollment-form"
          variables:
            region: ap-southeast-1
            locales: "en_AU,ja_JP,zh_CN"
```

### 4. Feature Flags

Different templates based on feature availability:

```yaml
docgen:
  templates:
    prewarming:
      scenarios:
        - name: "New Form Design"
          templateId: "enrollment-form-v2"
          variables:
            featureFlag: new-design
            formStyle: modern
            
        - name: "Legacy Form Design"
          templateId: "enrollment-form-v1"
          variables:
            featureFlag: legacy-design
            formStyle: classic
```

## Implementation Details

### Deep-Copy Pattern

Scenarios use a safe deep-copy pattern to avoid mutating the cached structural template:

```java
// Pseudocode
DocumentTemplate baseTemplate = cache.get(templateId);  // Get structural template
DocumentTemplate resolvedTemplate = deepCopy(baseTemplate);  // Deep copy
templateLoader.interpolateTemplateFields(resolvedTemplate, variables);  // Modify copy
// baseTemplate remains unchanged with placeholders intact
```

This ensures:
- The cached structural template stays clean
- Each scenario gets an independent copy
- Per-request interpolation doesn't affect cache
- No threading or mutation issues

### Performance Impact

Prewarming scenarios adds minimal overhead at startup:

- **Typical startup time increase**: 50-200ms (depends on template complexity)
- **Memory overhead**: Proportional to number of scenarios × template size
- **Cache savings**: Eliminates first-request latency for prewarmed scenarios (~100-500ms saved per request)

### Backwards Compatibility

The new `prewarming.scenarios` configuration is **optional**:

- Existing `preload-ids` and `preload` configurations continue to work
- No breaking changes to the API
- Can mix all three strategies in same configuration

## Testing

### Unit Test Example

```java
@Test
void testMultipleEnvironmentScenarios() {
    Map<String, Object> prodVars = Map.of(
        "environment", "production",
        "region", "us-east-1"
    );
    
    DocumentTemplate template = templateLoader.loadTemplate("enrollment-form");
    DocumentTemplate resolved = deepCopy(template);
    templateLoader.interpolateTemplateFields(resolved, prodVars);
    
    // Verify resolved template has no placeholders
    boolean hasPlaceholders = resolved.getSections().stream()
        .anyMatch(s -> s.getTemplatePath().contains("${"));
    assertFalse(hasPlaceholders);
}
```

See [TemplateCacheWarmerWithVariablesTest.java](src/test/java/com/example/demo/docgen/service/TemplateCacheWarmerWithVariablesTest.java) for full example.

## Troubleshooting

### Unresolved Placeholder Error at Startup

**Error**: `TemplateLoadingException: UNRESOLVED_PLACEHOLDER: Unresolved placeholder 'environment'`

**Cause**: A scenario's variables map is missing a required placeholder variable.

**Solution**: Ensure the `variables` map includes all placeholders in your template:

```yaml
# WRONG - missing 'environment'
variables:
  region: "us-east-1"

# CORRECT - includes all required placeholders
variables:
  region: "us-east-1"
  environment: "production"
```

### Scenario Warnings in Logs

**Warning**: `Failed to warm scenario 'Production': ...`

**Cause**: Either the template doesn't exist or there's an issue with placeholder resolution.

**Solution**:
1. Verify `templateId` and `namespace` exist
2. Check that all placeholder variables are present in `variables` map
3. Review template definition for unsupported placeholder syntax

### No Cached Resources After Prewarming

**Cause**: Resources might not be resolved if placeholders in `templatePath` aren't in the variables map.

**Solution**: Ensure scenario variables include all placeholders used in `templatePath`:

```yaml
# In template YAML
sections:
  - templatePath: "forms/${formType}-${version}.pdf"

# In scenario config - must include both formType and version
variables:
  formType: "enrollment"
  version: "v1"
```

## Further Reading

- [TemplateLoader Documentation](TEMPLATE_LOADER.md) - How placeholder resolution works
- [Caching Architecture](CACHING_ARCHITECTURE.md) - Cache strategy details
- [Field Mapping Guide](MAPPING_STRATEGIES_README.md) - Variable interpolation patterns
