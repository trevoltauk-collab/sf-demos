# Template Prewarming Quick Reference

## Configuration in application.yml

```yaml
docgen:
  templates:
    prewarming:
      enabled: true                    # Enable/disable (default: true)
      scenarios:
        - name: "Display Name"         # Required: Used in logs
          templateId: "template-id"    # Required: Template to load
          namespace: "tenant-a"        # Optional: Namespace/tenant
          description: "..."           # Optional: Documentation
          interpolateFields: true      # Optional: Resolve fields (default: true)
          variables:                   # Required: Placeholder variables
            key1: value1               # String, number, boolean, nested objects
            key2: value2
            nested:
              key3: value3
```

## What Happens at Startup

For each scenario:
1. Load template from classpath or config server
2. Deep-copy to preserve cached original
3. Replace `${key}` placeholders with values from `variables`
4. Pre-load all referenced resources (PDFs, FTLs)
5. Both structural and resolved templates cached

## Common Patterns

### Environment Defaults
```yaml
scenarios:
  - name: "Production"
    templateId: "form"
    variables:
      environment: production
      version: 2024-prod
      region: us-east-1
      
  - name: "Development"
    templateId: "form"
    variables:
      environment: development
      version: dev
      region: local
```

### Multi-Tenant
```yaml
scenarios:
  - name: "Tenant A"
    templateId: "form-a"
    namespace: "tenant-a"
    variables:
      environment: production
      
  - name: "Tenant B"
    templateId: "form-b"
    namespace: "tenant-b"
    variables:
      environment: production
```

### Region-Specific
```yaml
scenarios:
  - name: "US East"
    templateId: "form"
    variables:
      region: us-east-1
      
  - name: "EU West"
    templateId: "form"
    variables:
      region: eu-west-1
```

## Variable Types

```yaml
variables:
  # Strings
  environment: "production"
  
  # Numbers
  version: 2024
  
  # Booleans
  isRequired: true
  
  # Nested objects
  config:
    level1:
      level2: "value"
  
  # Lists (for complex scenarios)
  regions:
    - us-east-1
    - us-west-2
```

Access nested variables like: `${config.level1.level2}`

## Placeholder Syntax in Templates

### In template YAML
```yaml
sections:
  - templatePath: "forms/${formType}-${version}.pdf"
    condition: "$.env == '${environment}'"
```

### Variables needed
```yaml
variables:
  formType: "enrollment"
  version: "v1"
  environment: "production"
```

### Result
```
templatePath becomes: "forms/enrollment-v1.pdf"
condition becomes: "$.env == 'production'"
```

## Startup Logs

```
[INFO] Starting template cache warming
[INFO] Preloading 3 scenario(s) with placeholder variables
[INFO]   Scenario 'Production': tenant-a/enrollment-form
[INFO]   Scenario 'Development': tenant-a/enrollment-form
[INFO]   Scenario 'Staging': tenant-a/enrollment-form
[INFO] Template cache warming completed in 234ms
```

## Troubleshooting

### Error: "Unresolved placeholder 'environment'"
**Cause**: Variable missing in scenario config
**Fix**: Add to variables map
```yaml
variables:
  environment: "production"  # ← Add this
  formType: "enrollment"
```

### Error: "Failed to warm scenario"
**Cause**: Template doesn't exist or other loading error
**Fix**: 
1. Verify `templateId` exists
2. Check `namespace` is correct (if provided)
3. Ensure template files are accessible

### Scenarios not warming
**Cause**: `enabled: false` or no scenarios defined
**Fix**: 
```yaml
docgen:
  templates:
    prewarming:
      enabled: true  # ← Must be true
      scenarios:     # ← Must have scenarios
        - name: "..."
```

## Performance

- **Startup overhead**: ~50-300ms (depends on scenario count and template size)
- **Memory**: ~10-50KB per scenario
- **Runtime benefit**: Eliminates 100-500ms first-request latency for prewarmed scenarios

## Example: Complete Setup

```yaml
docgen:
  templates:
    cache-enabled: true
    
    # Legacy strategy 1 (still works)
    preload-ids:
      - base-enrollment
    
    # Legacy strategy 2 (still works)
    preload:
      tenant-a:
        - shared-header
    
    # New strategy 3
    prewarming:
      enabled: true
      scenarios:
        - name: "Production"
          templateId: "enrollment-form"
          namespace: "tenant-a"
          variables:
            environment: production
            formType: enrollment
            version: 2024-prod
            region: us-east-1
```

All three strategies run at startup. Backwards compatible.

## Documentation

- **Full Guide**: See [PREWARMING_CONFIGURATION.md](PREWARMING_CONFIGURATION.md)
- **Implementation Details**: See [PREWARMING_CONFIGURATION_SUMMARY.md](PREWARMING_CONFIGURATION_SUMMARY.md)
- **Test Examples**: See [TemplateCacheWarmerWithVariablesTest.java](src/test/java/com/example/demo/docgen/service/TemplateCacheWarmerWithVariablesTest.java)
- **Example Config**: See [application-example.yml](application-example.yml)
