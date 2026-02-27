# How to Set Up: Single Template, Multiple Configurations

## Quick Start (5 Minutes)

### Step 1: Create One Template File

**File**: `src/main/resources/templates/my-form.yaml`

```yaml
templateId: my-form
namespace: common-templates

sections:
  - sectionId: header
    type: FREEMARKER
    templatePath: "headers/header-${region}.ftl"
    
  - sectionId: form
    type: ACROFORM
    templatePath: "forms/form-${environment}.pdf"
    condition: "$.status != 'deleted'"
    
  - sectionId: footer
    type: FREEMARKER
    templatePath: "footers/footer-${environment}.ftl"
    content: "Environment: ${environment}, Region: ${region}, Built: ${buildVersion}"
```

**Key**: Template has `${placeholder}` syntax instead of hardcoded values.

### Step 2: Define Scenarios in application.yml

```yaml
docgen:
  templates:
    cache-enabled: true
    
    prewarming:
      enabled: true
      scenarios:
        # Production - US
        - name: "Prod - US"
          templateId: my-form
          namespace: common-templates
          variables:
            region: us-east-1
            environment: production
            buildVersion: "2024-01-prod"
            
        # Production - EU
        - name: "Prod - EU"
          templateId: my-form
          namespace: common-templates
          variables:
            region: eu-west-1
            environment: production
            buildVersion: "2024-01-prod"
            
        # Development
        - name: "Dev"
          templateId: my-form
          namespace: common-templates
          variables:
            region: local
            environment: development
            buildVersion: "main-dev"
```

### Step 3: Ensure Resource Files Exist

The template references these files (they must exist):

```
resources/
└── templates/
    ├── headers/
    │   ├── header-us-east-1.ftl
    │   └── header-eu-west-1.ftl
    ├── forms/
    │   ├── form-production.pdf
    │   └── form-development.pdf
    ├── footers/
    │   ├── footer-production.ftl
    │   └── footer-development.ftl
    └── my-form.yaml
```

### Step 4: Start Application

On startup, the cache warmer will:

1. Load `my-form.yaml` (once)
2. Create 3 resolved variants:
   - Prod-US: `forms/form-production.pdf`, `headers/header-us-east-1.ftl`, etc.
   - Prod-EU: `forms/form-production.pdf`, `headers/header-eu-west-1.ftl`, etc.
   - Dev: `forms/form-development.pdf`, `headers/header-local.ftl`, etc.

3. Log output:
```
[INFO] Starting template cache warming
[INFO] Preloading 3 scenario(s) with placeholder variables
[INFO]   Scenario 'Prod - US': common-templates/my-form
[INFO]   Scenario 'Prod - EU': common-templates/my-form
[INFO]   Scenario 'Dev': common-templates/my-form
[INFO] Template cache warming completed in 45ms
```

### Step 5: Use in Your Application

```java
// Get the template
DocumentTemplate template = templateLoader.loadTemplate("my-form");

// Apply request-specific variables (if different from prewarmed scenarios)
Map<String, Object> requestVars = Map.of(
    "region", "us-east-1",
    "environment", "production",
    "buildVersion", "2024-01-prod"
);

// Resolve placeholders
DocumentTemplate resolved = deepCopy(template);
templateLoader.interpolateTemplateFields(resolved, requestVars);

// Use resolved template for rendering
documentComposer.generateDocument(resolved, data);
```

---

## Complete Working Example

### Template YAML: `enrollment-form.yaml`

```yaml
templateId: enrollment-form
namespace: tenant-a
description: "Multi-environment enrollment form with regional variants"

inheritance:
  baseTemplateId: base-form

sections:
  - sectionId: form-data
    type: ACROFORM
    templatePath: "forms/enrollment-${formType}-${environment}.pdf"
    metadata:
      region: ${region}
      locale: ${locale}
    
  - sectionId: personalized-header
    type: FREEMARKER
    templatePath: "headers/header-${region}-${environment}.ftl"
    order: 1
    
  - sectionId: personalized-footer
    type: FREEMARKER
    templatePath: "footers/${environment}/footer.ftl"
    order: 100
    content: |
      Generated for: ${locale}
      Region: ${region}
      Version: ${templateVersion}
```

### Configuration: `application.yml`

```yaml
spring:
  application:
    name: document-service

docgen:
  templates:
    cache-enabled: true
    
    prewarming:
      enabled: true
      scenarios:
        # ==== PRODUCTION SCENARIOS ====
        - name: "Prod - NA (US/CA)"
          templateId: enrollment-form
          namespace: tenant-a
          description: "Production for North American users"
          variables:
            region: us-east-1
            environment: production
            locale: en_US
            formType: standard
            templateVersion: "2024-q1-prod"
            
        - name: "Prod - EU"
          templateId: enrollment-form
          namespace: tenant-a
          description: "Production for European users"
          variables:
            region: eu-west-1
            environment: production
            locale: en_GB
            formType: gdpr-compliant
            templateVersion: "2024-q1-prod"
            
        # ==== STAGING SCENARIOS ====
        - name: "Staging - US"
          templateId: enrollment-form
          namespace: tenant-a
          description: "Staging environment for testing"
          variables:
            region: us-west-2
            environment: staging
            locale: en_US
            formType: standard
            templateVersion: "2024-q1-staging"
            
        # ==== DEVELOPMENT SCENARIOS ====
        - name: "Dev - Local"
          templateId: enrollment-form
          namespace: tenant-a
          description: "Development/testing on local machine"
          variables:
            region: local
            environment: development
            locale: en_US
            formType: test
            templateVersion: "main-dev"
```

### Resource Files Needed

```
src/main/resources/templates/
├── enrollment-form.yaml
├── base-form.yaml
├── forms/
│   ├── enrollment-standard-production.pdf
│   ├── enrollment-gdpr-compliant-production.pdf
│   ├── enrollment-standard-staging.pdf
│   └── enrollment-test-development.pdf
├── headers/
│   ├── header-us-east-1-production.ftl
│   ├── header-eu-west-1-production.ftl
│   ├── header-us-west-2-staging.ftl
│   └── header-local-development.ftl
└── footers/
    ├── production/
    │   └── footer.ftl
    ├── staging/
    │   └── footer.ftl
    └── development/
        └── footer.ftl
```

### Startup Output

```
INFO: Loading Spring configuration
INFO: Starting DocumentServiceApplication
INFO: Starting template cache warming
INFO: Preloading 4 scenario(s) with placeholder variables
INFO:   Scenario 'Prod - NA (US/CA)': tenant-a/enrollment-form
DEBUG:    Description: Production for North American users
DEBUG:     Resource: forms/enrollment-standard-production.pdf
DEBUG:     Resource: headers/header-us-east-1-production.ftl
DEBUG:     Resource: footers/production/footer.ftl
INFO:   Scenario 'Prod - EU': tenant-a/enrollment-form
DEBUG:    Description: Production for European users
DEBUG:     Resource: forms/enrollment-gdpr-compliant-production.pdf
DEBUG:     Resource: headers/header-eu-west-1-production.ftl
DEBUG:     Resource: footers/production/footer.ftl
INFO:   Scenario 'Staging - US': tenant-a/enrollment-form
DEBUG:     Resource: forms/enrollment-standard-staging.pdf
DEBUG:     Resource: headers/header-us-west-2-staging.ftl
DEBUG:     Resource: footers/staging/footer.ftl
INFO:   Scenario 'Dev - Local': tenant-a/enrollment-form
DEBUG:     Resource: forms/enrollment-test-development.pdf
DEBUG:     Resource: headers/header-local-development.ftl
DEBUG:     Resource: footers/development/footer.ftl
INFO: Template cache warming completed in 187ms
INFO: DocumentServiceApplication started in 2.345s
```

### Using It at Runtime

```java
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    @PostMapping("/enrollment")
    public ResponseEntity<byte[]> generateEnrollment(
            @RequestBody EnrollmentRequest request) {
        
        // Load template (will get base with placeholders)
        DocumentTemplate template = templateLoader.loadTemplate("enrollment-form");
        
        // Determine variables based on request context
        Map<String, Object> variables = Map.of(
            "region", request.getRegion(),        // "us-east-1", "eu-west-1", "local"
            "environment", environment,             // from @Value or config
            "locale", request.getLocale(),
            "formType", determineFormType(request),
            "templateVersion", templateVersion
        );
        
        // Resolve placeholders
        DocumentTemplate resolved = deepCopy(template);
        templateLoader.interpolateTemplateFields(resolved, variables);
        
        // Generate document
        byte[] pdf = documentComposer.generateDocument(resolved, request.asMap());
        
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=enrollment.pdf")
            .body(pdf);
    }
    
    private String determineFormType(EnrollmentRequest request) {
        if ("production".equals(environment) && "eu-west-1".equals(request.getRegion())) {
            return "gdpr-compliant";
        }
        return "standard";
    }
}
```

---

## Variations & Patterns

### Pattern 1: Environment Grid

```yaml
scenarios:
  - { name: prod-us,  templateId: form, vars: {env: prod,  region: us} }
  - { name: prod-eu,  templateId: form, vars: {env: prod,  region: eu} }
  - { name: staging,  templateId: form, vars: {env: stage, region: us} }
  - { name: dev,      templateId: form, vars: {env: dev,   region: local} }
```

### Pattern 2: Tenant Matrix

```yaml
scenarios:
  - { name: tenant-a-prod, namespace: tenant-a, templateId: form, vars: {env: prod} }
  - { name: tenant-a-dev,  namespace: tenant-a, templateId: form, vars: {env: dev} }
  - { name: tenant-b-prod, namespace: tenant-b, templateId: form, vars: {env: prod} }
  - { name: tenant-b-dev,  namespace: tenant-b, templateId: form, vars: {env: dev} }
```

### Pattern 3: Feature Combinations

```yaml
scenarios:
  - { name: new-ui-enabled,   templateId: form, vars: {newUI: true,  analytics: v2} }
  - { name: new-ui-disabled,  templateId: form, vars: {newUI: false, analytics: v1} }
  - { name: beta-features,    templateId: form, vars: {newUI: true,  analytics: v2, beta: true} }
```

---

## Testing Your Setup

### Verify Prewarming

```bash
# Look for cache warming logs
mvn spring-boot:run 2>&1 | grep -i "preload\|scenario\|warming"
```

### Test Template Resolution

```java
@Test
void testEnrollmentFormPrewarmed() {
    // Should not throw because variables are prewarmed
    DocumentTemplate template = templateLoader.loadTemplate("enrollment-form");
    assertNotNull(template);
    
    // Check that resources were preloaded
    assertTrue(cache.getIfPresent("forms/enrollment-standard-production.pdf") != null);
    assertTrue(cache.getIfPresent("forms/enrollment-gdpr-compliant-production.pdf") != null);
}
```

### Generate Document with Different Variables

```java
@Test
void testGenerateWithPrewarmedVariables() {
    Map<String, Object> vars = Map.of(
        "region", "us-east-1",
        "environment", "production",
        "locale", "en_US"
    );
    
    DocumentTemplate template = templateLoader.loadTemplate("enrollment-form");
    DocumentTemplate resolved = deepCopy(template);
    templateLoader.interpolateTemplateFields(resolved, vars);
    
    byte[] pdf = documentComposer.generateDocument(resolved, testData);
    assertTrue(pdf.length > 0);
}
```

---

## Troubleshooting

### "Unresolved placeholder" at Startup

**Problem**: `TemplateLoadingException: UNRESOLVED_PLACEHOLDER: Unresolved placeholder 'region'`

**Solution**: Add missing variable to scenario:
```yaml
- name: MyScenario
  templateId: my-form
  variables:
    region: us-east-1  # ← Add this if template uses ${region}
```

### Resources Not Found

**Problem**: Logs show `Failed to warm resource: forms/form-production.pdf`

**Solution**: Verify files exist:
```bash
find src/main/resources/templates -name "*.pdf" -o -name "*.ftl"
```

### Scenario Won't Warm

**Problem**: Scenario in config but not appearing in startup logs

**Solution**: Check that `prewarming.enabled: true` and scenarios are properly indented in YAML.

---

## Next Steps

1. **Identify your template variants** - How many versions do you currently have?
2. **Extract common structure** - What's the same across all versions?
3. **Identify variables** - What changes between versions?
4. **Create one template** - With `${placeholder}` syntax
5. **Add scenarios to config** - Define each variant via variables
6. **Test startup** - Verify prewarming logs
7. **Measure improvement** - Track first-request latency

**Result**: One maintainable template, multiple configurations, less duplication!
