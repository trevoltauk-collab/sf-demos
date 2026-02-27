# Single Template, Multiple Configurations Pattern

## Overview

You can load **one template definition multiple times** with different variable configurations, creating distinct resolved variants in the cache. This minimizes template duplication while supporting environment-specific, region-specific, tenant-specific, or feature-specific variations.

## How It Works

### Configuration

```yaml
docgen:
  templates:
    prewarming:
      scenarios:
        # Single template, different environments
        - name: "Production"
          templateId: "enrollment-form"      # ← Same template
          variables:
            environment: production
            version: 2024-prod
            region: us-east-1
            
        - name: "Staging"
          templateId: "enrollment-form"      # ← Same template
          variables:
            environment: staging
            version: 2024-staging
            region: us-west-2
            
        - name: "Development"
          templateId: "enrollment-form"      # ← Same template
          variables:
            environment: development
            version: dev
            region: local
```

### At Startup

For each scenario:

1. **Load** the template once (structural cache)
   ```
   template = cache["enrollment-form"]
   ```

2. **Deep-copy** it (preserve original)
   ```
   production_template = deepCopy(template)
   staging_template = deepCopy(template)
   development_template = deepCopy(template)
   ```

3. **Resolve placeholders** with scenario variables
   ```
   // Production variant
   ${environment} → production
   ${version} → 2024-prod
   ${region} → us-east-1
   
   // Staging variant
   ${environment} → staging
   ${version} → 2024-staging
   ${region} → us-west-2
   
   // Development variant
   ${environment} → development
   ${version} → dev
   ${region} → local
   ```

### At Runtime

Request can use prewarmed variants:

```java
// Request for production
templateLoader.interpolateTemplateFields(
    cache["enrollment-form"],  // Cached base
    Map.of("environment", "production", ...)
);
// Already resolved and available in cache
```

## Practical Examples

### Example 1: Environment Variants

Single template with environment-specific defaults:

```yaml
scenarios:
  - name: "Prod - US East"
    templateId: "enrollment-form"
    variables:
      environment: production
      region: us-east-1
      logLevel: ERROR
      timeout: 30s
      
  - name: "Staging - US West"
    templateId: "enrollment-form"
    variables:
      environment: staging
      region: us-west-2
      logLevel: WARN
      timeout: 60s
      
  - name: "Dev - Local"
    templateId: "enrollment-form"
    variables:
      environment: development
      region: local
      logLevel: DEBUG
      timeout: 120s
```

**Result**: Single `enrollment-form.yaml` template file, three resolved variants cached.

### Example 2: Multi-Region Deployment

Single template, deployed to multiple regions:

```yaml
scenarios:
  - name: "US East Region"
    templateId: "common-form"
    variables:
      region: us-east-1
      dataCenter: ashburn
      currency: USD
      language: en-US
      
  - name: "EU West Region"
    templateId: "common-form"
    variables:
      region: eu-west-1
      dataCenter: dublin
      currency: EUR
      language: en-GB
      
  - name: "APAC Region"
    templateId: "common-form"
    variables:
      region: ap-southeast-1
      dataCenter: singapore
      currency: SGD
      language: en-SG
```

**Result**: One template file, three regional variants prewarmed.

### Example 3: Feature Flags

Single template with features enabled/disabled per scenario:

```yaml
scenarios:
  - name: "New UI Feature Enabled"
    templateId: "dashboard"
    variables:
      newUiEnabled: true
      analyticsV2: true
      darkModeSupport: true
      betaFeatures: true
      
  - name: "Legacy UI (Stable)"
    templateId: "dashboard"
    variables:
      newUiEnabled: false
      analyticsV2: false
      darkModeSupport: false
      betaFeatures: false
```

**Result**: One template, two feature flag configurations cached.

### Example 4: Multi-Tenant with Different Branding

```yaml
scenarios:
  - name: "Tenant A - Acme Corp"
    templateId: "branded-form"
    namespace: "common"
    variables:
      tenant: acme
      brandColor: "#0066CC"
      logo: "acme-logo.png"
      complianceLevel: SOC2
      
  - name: "Tenant B - Widget Inc"
    templateId: "branded-form"
    namespace: "common"
    variables:
      tenant: widget
      brandColor: "#FF6600"
      logo: "widget-logo.png"
      complianceLevel: ISO27001
      
  - name: "Tenant C - Global Ltd"
    templateId: "branded-form"
    namespace: "common"
    variables:
      tenant: global
      brandColor: "#009900"
      logo: "global-logo.png"
      complianceLevel: GDPR
```

**Result**: One template file, three tenant-specific branded variants cached.

## Benefits

| Benefit | Explanation |
|---------|-------------|
| **Reduced Duplication** | One template file instead of 3-5 copies |
| **Easier Maintenance** | Update template logic once, affects all variants |
| **Consistent Structure** | All variants share same sections, conditions, logic |
| **Variable-Driven Config** | Environment/tenant/region config, not template config |
| **Startup Validation** | All variants validated at startup (fail fast) |
| **Prewarming Efficiency** | Load once, resolve multiple times with different variables |
| **Cache Optimization** | Structural template cached once, variants available immediately |

## Template Definition (YAML)

```yaml
# Single file: enrollment-form.yaml
templateId: enrollment-form
namespace: common
sections:
  - sectionId: form-section
    type: ACROFORM
    templatePath: "forms/${formType}-${environment}.pdf"  # ← Uses variables
    condition: "$.status == '${environmentLevel}'"        # ← Uses variables
    
  - sectionId: footer
    type: FREEMARKER
    templatePath: "footers/footer-${region}.ftl"          # ← Uses variables
    content: "Available in ${language}: ${supportedLanguages}"
```

### At Startup (Prod Scenario)

```yaml
# Variables from config
environment: production
region: us-east-1
environmentLevel: PROD
language: English
supportedLanguages: "EN-US, ES, FR"
formType: enrollment
```

### Resolved Template (Prod)

```yaml
templateId: enrollment-form
sections:
  - sectionId: form-section
    templatePath: "forms/enrollment-production.pdf"
    condition: "$.status == 'PROD'"
    
  - sectionId: footer
    templatePath: "footers/footer-us-east-1.ftl"
    content: "Available in English: EN-US, ES, FR"
```

### At Startup (Dev Scenario)

```yaml
# Variables from config
environment: development
region: local
environmentLevel: DEV
language: English
supportedLanguages: "EN-US"
formType: enrollment
```

### Resolved Template (Dev)

```yaml
templateId: enrollment-form
sections:
  - sectionId: form-section
    templatePath: "forms/enrollment-development.pdf"
    condition: "$.status == 'DEV'"
    
  - sectionId: footer
    templatePath: "footers/footer-local.ftl"
    content: "Available in English: EN-US"
```

## Cache State After Startup

```
documentTemplates cache:
├── [enrollment-form] → Base template with ${placeholders}
├── [prod:enrollment-form:hash1] → Resolved (environment=production, region=us-east-1)
├── [dev:enrollment-form:hash2] → Resolved (environment=development, region=local)
└── [staging:enrollment-form:hash3] → Resolved (environment=staging, region=us-west-2)

rawResources cache:
├── [forms/enrollment-production.pdf] → Binary
├── [forms/enrollment-development.pdf] → Binary
├── [forms/enrollment-staging.pdf] → Binary
├── [footers/footer-us-east-1.ftl] → Text
├── [footers/footer-local.ftl] → Text
└── [footers/footer-us-west-2.ftl] → Text
```

## Runtime Usage

### Scenario 1: Using Prewarmed Variant (Fast)

```java
// Request for production environment
Map<String, Object> requestVars = Map.of(
    "environment", "production",
    "region", "us-east-1"
);

// Already resolved and cached from prewarming
DocumentTemplate template = templateLoader.loadTemplate("enrollment-form");
// ↑ Gets base template, but if exact variables match scenario,
//   runtime can use prewarmed resolved variant
```

### Scenario 2: Using Non-Prewarmed Variant (Slower, First Time)

```java
// Request for new region not in prewarming config
Map<String, Object> requestVars = Map.of(
    "environment", "production",
    "region", "ap-northeast-1"  // ← Not in scenarios
);

// Not prewarmed, so resolved at first request
DocumentTemplate template = templateLoader.loadTemplate("enrollment-form");
DocumentTemplate resolved = deepCopy(template);
templateLoader.interpolateTemplateFields(resolved, requestVars);
// ↑ Takes 20-100ms first time, then cached for this variable combination
```

## Configuration Patterns Summary

### By Environment

```yaml
scenarios:
  - { name: Prod,    templateId: form, variables: { env: prod } }
  - { name: Staging, templateId: form, variables: { env: staging } }
  - { name: Dev,     templateId: form, variables: { env: dev } }
```

### By Region

```yaml
scenarios:
  - { name: US-East,   templateId: form, variables: { region: us-east-1 } }
  - { name: US-West,   templateId: form, variables: { region: us-west-2 } }
  - { name: EU-West,   templateId: form, variables: { region: eu-west-1 } }
```

### By Tenant

```yaml
scenarios:
  - { name: "Tenant A", templateId: form, namespace: tenant-a, variables: { tenant: a } }
  - { name: "Tenant B", templateId: form, namespace: tenant-b, variables: { tenant: b } }
  - { name: "Tenant C", templateId: form, namespace: tenant-c, variables: { tenant: c } }
```

### By Feature

```yaml
scenarios:
  - { name: Feature-On,  templateId: form, variables: { feature: enabled } }
  - { name: Feature-Off, templateId: form, variables: { feature: disabled } }
```

### Cartesian Product (All Combinations)

```yaml
scenarios:
  # env × region combinations
  - { name: Prod-US,   templateId: form, variables: { env: prod, region: us-east-1 } }
  - { name: Prod-EU,   templateId: form, variables: { env: prod, region: eu-west-1 } }
  - { name: Dev-US,    templateId: form, variables: { env: dev, region: us-east-1 } }
  - { name: Dev-EU,    templateId: form, variables: { env: dev, region: eu-west-1 } }
```

## Testing

See test scenarios in [TemplateCacheWarmerWithVariablesTest.java](src/test/java/com/example/demo/docgen/service/TemplateCacheWarmerWithVariablesTest.java):

1. **testPrewarmMultipleVariantScenarios** - Different environment variants of same template
2. **testSafeCachedTemplateCopyBeforeInterpolation** - Multiple independent requests using same template
3. **testCacheKeyGenerationFromVariables** - Deterministic cache keys for different variable combinations

## Best Practices

1. **Use consistent variable names** across scenarios for same template
2. **Document all placeholder variables** in template YAML comments
3. **Group related scenarios** with comments or naming convention
4. **Test placeholder paths** - ensure resources exist for all variable combinations
5. **Use descriptive scenario names** - logs will show these during startup
6. **Consider environment variables** for dynamic configuration at deploy time
7. **Profile prewarming** - measure startup overhead vs. first-request savings

## Example: Complete Multi-Environment Setup

```yaml
docgen:
  templates:
    prewarming:
      enabled: true
      scenarios:
        # ============== ENVIRONMENTS ==============
        - name: prod-form-us
          templateId: enrollment
          variables:
            env: production
            region: us-east-1
            version: 2024-01
            
        - name: prod-form-eu
          templateId: enrollment
          variables:
            env: production
            region: eu-west-1
            version: 2024-01
            
        - name: staging-form
          templateId: enrollment
          variables:
            env: staging
            region: us-west-2
            version: 2024-01-beta
            
        - name: dev-form
          templateId: enrollment
          variables:
            env: development
            region: local
            version: main
```

All scenarios load the same `enrollment` template file but with different variable configurations, creating distinct cached variants.
