# Before & After: Single Template, Multiple Configurations

## The Problem (Before)

You had to create multiple YAML template files:

```
templates/
├── enrollment-form-prod.yaml      # Production version
├── enrollment-form-staging.yaml   # Staging version
└── enrollment-form-dev.yaml       # Development version
```

Each file duplicates the structure:

```yaml
# enrollment-form-prod.yaml
templateId: enrollment-form-prod
namespace: tenant-a
sections:
  - templatePath: forms/enrollment-production.pdf
    condition: "$.env == 'PROD'"

# enrollment-form-staging.yaml
templateId: enrollment-form-staging
namespace: tenant-a
sections:
  - templatePath: forms/enrollment-staging.pdf
    condition: "$.env == 'STAGING'"

# enrollment-form-dev.yaml
templateId: enrollment-form-dev
namespace: tenant-a
sections:
  - templatePath: forms/enrollment-development.pdf
    condition: "$.env == 'DEV'"
```

**Issues**:
- ❌ Template duplication
- ❌ Hard to maintain (update logic = update 3+ files)
- ❌ Inconsistent structures
- ❌ No clear relationship between variants

---

## The Solution (After)

**One template file** with variables:

```
templates/
└── enrollment-form.yaml           # Single, reusable template
```

**enrollment-form.yaml**:
```yaml
templateId: enrollment-form
namespace: tenant-a
sections:
  - templatePath: forms/enrollment-${environment}.pdf
    condition: "$.env == '${environmentLevel}'"
```

**application.yml** (configuration):
```yaml
docgen:
  templates:
    prewarming:
      scenarios:
        - name: "Production"
          templateId: enrollment-form
          variables:
            environment: production
            environmentLevel: PROD
            
        - name: "Staging"
          templateId: enrollment-form
          variables:
            environment: staging
            environmentLevel: STAGING
            
        - name: "Development"
          templateId: enrollment-form
          variables:
            environment: development
            environmentLevel: DEV
```

**At startup**, three variants are created from one template:

```
↓ Load enrollment-form.yaml once
↓ Create 3 deep-copies with different variables
├── Variant 1: forms/enrollment-production.pdf, $.env == 'PROD'
├── Variant 2: forms/enrollment-staging.pdf, $.env == 'STAGING'
└── Variant 3: forms/enrollment-development.pdf, $.env == 'DEV'
```

**Benefits**:
- ✅ Single template file to maintain
- ✅ Configuration (variables) separate from template
- ✅ Consistent structure across variants
- ✅ DRY principle (Don't Repeat Yourself)
- ✅ Easy to add new variants (just add scenario)

---

## Practical Code Comparison

### Before: Hardcoded Template Selection

```java
// Controller or Service
String templateId;
if ("PROD".equals(environment)) {
    templateId = "enrollment-form-prod";
} else if ("STAGING".equals(environment)) {
    templateId = "enrollment-form-staging";
} else {
    templateId = "enrollment-form-dev";
}

DocumentTemplate template = templateLoader.loadTemplate(templateId);
```

### After: Variable-Based Resolution

```java
// Controller or Service
DocumentTemplate template = templateLoader.loadTemplate("enrollment-form");

// Variables automatically selected based on context
Map<String, Object> variables = Map.of(
    "environment", environment,  // from request/config
    "environmentLevel", environmentLevel
);

// Resolve placeholders (likely already prewarmed)
DocumentTemplate resolved = deepCopy(template);
templateLoader.interpolateTemplateFields(resolved, variables);
```

---

## Real-World Scenario: Multi-Region Deployment

### Before: Multiple Files Per Region

```
templates/
├── form-us-east.yaml
├── form-us-west.yaml
├── form-eu-west.yaml
├── form-ap-southeast.yaml
└── form-ap-northeast.yaml
```

Each file might contain:
```yaml
templateId: enrollment-form-us-east
sections:
  - templatePath: forms/form-us-east.pdf
    metadata:
      currency: USD
      language: en-US
      timezone: America/New_York
```

**Maintenance burden**: Update template logic = 5 files to change

### After: Single File + Configuration

**Single file**: `templates/enrollment-form.yaml`
```yaml
templateId: enrollment-form
sections:
  - templatePath: forms/form-${region}.pdf
    metadata:
      currency: ${currency}
      language: ${language}
      timezone: ${timezone}
```

**Configuration**: `application.yml`
```yaml
docgen:
  templates:
    prewarming:
      scenarios:
        - name: US-East
          templateId: enrollment-form
          variables:
            region: us-east-1
            currency: USD
            language: en-US
            timezone: America/New_York
            
        - name: US-West
          templateId: enrollment-form
          variables:
            region: us-west-2
            currency: USD
            language: en-US
            timezone: America/Los_Angeles
            
        - name: EU-West
          templateId: enrollment-form
          variables:
            region: eu-west-1
            currency: EUR
            language: en-GB
            timezone: Europe/London
            
        - name: APAC-SE
          templateId: enrollment-form
          variables:
            region: ap-southeast-1
            currency: SGD
            language: en-SG
            timezone: Asia/Singapore
            
        - name: APAC-NE
          templateId: enrollment-form
          variables:
            region: ap-northeast-1
            currency: JPY
            language: ja-JP
            timezone: Asia/Tokyo
```

**Maintenance**: Update template logic = 1 file. Add new region = 1 scenario config.

---

## Cache State Comparison

### Before (Multiple Files)

```
documentTemplates cache:
├── enrollment-form-prod → { template 1 }
├── enrollment-form-staging → { template 2 }
└── enrollment-form-dev → { template 3 }

Problem: Each file is loaded and cached independently
```

### After (Single File + Variables)

```
documentTemplates cache:
├── enrollment-form → { base template with placeholders }
├── enrollment-form[env=prod,level=PROD] → { resolved }
├── enrollment-form[env=staging,level=STAGING] → { resolved }
└── enrollment-form[env=dev,level=DEV] → { resolved }

Benefit: 1 structural template, multiple resolved variants
```

---

## Adding a New Variant

### Before: Create New Template File

1. Create new file `enrollment-form-qa.yaml`
2. Copy content from `enrollment-form-prod.yaml`
3. Change `qa` references throughout
4. Deploy and test
5. Update template ID references in code

### After: Add Configuration

1. Add scenario to `application.yml`:
```yaml
- name: "QA"
  templateId: enrollment-form  # ← Same template
  variables:
    environment: qa
    environmentLevel: QA
```
2. Restart application
3. Done! Prewarmed automatically.

**Difference**: Configuration only, no new files or code changes.

---

## Performance Comparison

### Before

- 3+ template files to load
- Each has own structural parsing
- 3+ resource cache entries

### After

- 1 template file loaded
- 1 structural parse
- Multiple resolved variants (fast, shallow copies)
- Resources loaded once per variant

**Result**: Faster startup (load and parse once), same prewarming coverage.

---

## Real Example from Test

The [TemplateCacheWarmerWithVariablesTest.java](src/test/java/com/example/demo/docgen/service/TemplateCacheWarmerWithVariablesTest.java) demonstrates this:

```java
@Test
void testPrewarmMultipleVariantScenarios() {
    // Three scenarios, same template
    Map<String, Object> prodVars = Map.of(
        "formType", "enrollment",
        "version", "2024-prod",  // ← Different
        "environment", "production"
    );
    
    Map<String, Object> devVars = Map.of(
        "formType", "enrollment",
        "version", "2024-dev",   // ← Different
        "environment", "development"
    );
    
    Map<String, Object> stagingVars = Map.of(
        "formType", "enrollment",
        "version", "2024-staging", // ← Different
        "environment", "staging"
    );
    
    // All use same template ID
    DocumentTemplate base = templateLoader.loadTemplate("test-placeholder-template.yaml");
    
    // But different copies with different variables
    DocumentTemplate prod = deepCopy(base);
    templateLoader.interpolateTemplateFields(prod, prodVars);
    
    DocumentTemplate dev = deepCopy(base);
    templateLoader.interpolateTemplateFields(dev, devVars);
    
    DocumentTemplate staging = deepCopy(base);
    templateLoader.interpolateTemplateFields(staging, stagingVars);
    
    // All three are now prewarmed and cached
    assertTrue(resolvedCache.containsKey("enrollment:production"));
    assertTrue(resolvedCache.containsKey("enrollment:development"));
    assertTrue(resolvedCache.containsKey("enrollment:staging"));
}
```

**Key insight**: One `loadTemplate()` call, three variants created by varying `variables` map.

---

## Summary: The Power of This Pattern

| Aspect | Before | After |
|--------|--------|-------|
| **Files** | 3-5 template files | 1 template file |
| **Configuration** | Code logic | YAML scenarios |
| **Consistency** | Manual (error-prone) | Automatic |
| **Adding Variant** | New file + code changes | New scenario config |
| **Maintenance** | Update multiple files | Update one file |
| **Scaling** | Difficult (N variants = N files) | Easy (N variants = N scenarios) |
| **Testing** | Test each variant separately | Test one, apply to all |

This pattern is especially powerful for:
- Multi-environment deployments (prod/staging/dev)
- Multi-region systems (US/EU/APAC)
- Multi-tenant applications
- Feature flag combinations
- A/B testing variants

**All from a single, well-maintained template file!**
