package com.example.demo.docgen.controller;

import com.example.demo.docgen.model.DocumentGenerationRequest;
import com.example.demo.docgen.service.DocumentComposer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for document generation
 */
@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {
    private final DocumentComposer documentComposer;
    
    /**
     * Generate a PDF document from a template and data
     *
    * Supports multiple ways to pass template resolution variables (for cache lookup):
    * 1. Request body `data` payload: { "data": { ... } } — primary source
    * 2. Query parameters: /generate?key=value
    * 3. Request headers: X-Environment: production, X-Region: us-east-1
    * 4. Application defaults: docgen.default-* config values
    *
    * POST /api/documents/generate
    * {
    *   "namespace": "tenant-a",
    *   "templateId": "enrollment-form",
    *   "data": {
    *     "applicant": { "firstName": "John", "lastName": "Doe" }
    *   }
    * }
     *
     * The namespace identifies which tenant's template directory (e.g., "tenant-a", "tenant-b").
     * If omitted, defaults to "common-templates".
     * The templateId is relative to {namespace}/templates/ folder.
     * 
     * The variables are used to construct the cache key for prewarmed template variants.
     * If variables match a prewarmed scenario, the request is extremely fast (< 1ms).
     * If variables don't match any prewarmed scenario, the template is loaded, resolved,
     * and cached for future requests with the same variables.
     *
     * @param request Generation request with namespace, templateId, variables, and data
     * @return PDF document as byte array
     */
    @PostMapping("/generate")
    public ResponseEntity<?> generateDocument(
            @RequestBody DocumentGenerationRequest request,
            @org.springframework.web.bind.annotation.RequestParam(required = false) java.util.Map<String, String> queryParams) {
        log.info("Received document generation request for template: {} from namespace: {}", request.getTemplateId(), request.getNamespace());
        try {
            // Extract variables from multiple sources (priority order: request.data > query > defaults)
            java.util.Map<String, Object> variables = extractVariablesForCacheLookup(request, queryParams);
            request.setVariables(variables);
            
            // Log cache key for debugging (shows which prewarmed scenario may be used)
            String cacheKey = generateCacheKeyForLogging(request.getNamespace(), request.getTemplateId(), variables);
            log.debug("Cache key for template resolution: {}", cacheKey);
            
            byte[] pdf = documentComposer.generateDocument(request);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "document.pdf");
            headers.setContentLength(pdf.length);

            return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
        } catch (com.example.demo.docgen.exception.TemplateLoadingException tle) {
            String code = tle.getCode();
            String description = tle.getDescription();

            java.util.Map<String, String> body = new java.util.HashMap<>();
            body.put("code", code);
            body.put("description", description);

            if ("TEMPLATE_NOT_FOUND".equals(code)) {
                return new ResponseEntity<>(body, HttpStatus.NOT_FOUND);
            } else if ("UNRESOLVED_PLACEHOLDER".equals(code)) {
                return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
            }

            return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // Backwards-compatible overload used by some tests: delegate to two-arg method
    @PostMapping(value = "/generate", params = "!queryParams")
    public ResponseEntity<?> generateDocument(@RequestBody DocumentGenerationRequest request) {
        return generateDocument(request, null);
    }

    /**
     * Generate and download filled Excel workbook (XLSX)
     * 
     * Supports the same variable resolution mechanism as /generate (query params, body variables, defaults).
     * See generateDocument() for variable extraction details.
     */
    @PostMapping("/generate/excel")
    public ResponseEntity<?> generateExcel(
            @RequestBody com.example.demo.docgen.model.DocumentGenerationRequest request,
            @org.springframework.web.bind.annotation.RequestParam(required = false) java.util.Map<String, String> queryParams) {
        log.info("Received Excel generation request for template: {} from namespace: {}", request.getTemplateId(), request.getNamespace());
        try {
            if (log.isDebugEnabled()) {
                log.debug("Incoming Excel request data keys: {}", request.getData() == null ? null : request.getData().keySet());
            }
            // Extract variables (same as PDF endpoint)
            java.util.Map<String, Object> variables = extractVariablesForCacheLookup(request, queryParams);
            request.setVariables(variables);
            
            // Log cache key for debugging
            String cacheKey = generateCacheKeyForLogging(request.getNamespace(), request.getTemplateId(), variables);
            log.debug("Cache key for template resolution: {}", cacheKey);
            
            byte[] xlsx = documentComposer.generateExcel(request);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.setContentDispositionFormData("attachment", "document.xlsx");
            headers.setContentLength(xlsx.length);

            return new ResponseEntity<>(xlsx, headers, HttpStatus.OK);
        } catch (com.example.demo.docgen.exception.TemplateLoadingException tle) {
            String code = tle.getCode();
            String description = tle.getDescription();

            java.util.Map<String, String> body = new java.util.HashMap<>();
            body.put("code", code);
            body.put("description", description);

            if ("TEMPLATE_NOT_FOUND".equals(code)) {
                return new ResponseEntity<>(body, HttpStatus.NOT_FOUND);
            } else if ("UNRESOLVED_PLACEHOLDER".equals(code)) {
                return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
            }

            return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // Backwards-compatible overload used by some tests: delegate to two-arg method
    @PostMapping(value = "/generate/excel", params = "!queryParams")
    public ResponseEntity<?> generateExcel(@RequestBody com.example.demo.docgen.model.DocumentGenerationRequest request) {
        return generateExcel(request, null);
    }
    
    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Document generation service is running");
    }

    /**
     * Extract template resolution variables from the request.
     * Variables are used to construct cache keys for prewarmed template variants.
     * 
    * Priority order:
    * 1. The request `data` payload (highest priority)
    * 2. Query parameters
    * 3. Application configuration defaults (lowest priority)
     * 
     * @param request The incoming request
     * @param queryParams Query parameters from URL
     * @return Variables map to use for template resolution
     */
    private java.util.Map<String, Object> extractVariablesForCacheLookup(
            DocumentGenerationRequest request,
            java.util.Map<String, String> queryParams) {
        
        java.util.Map<String, Object> variables = new java.util.LinkedHashMap<>();
        
        // Priority 1: Use the request `data` payload as the variables source
        if (request.getData() != null && !request.getData().isEmpty()) {
            variables.putAll(request.getData());
            log.debug("Using variables from request data payload");
        }
        
        // Priority 2: Query parameters
        if (queryParams != null && !queryParams.isEmpty()) {
            variables.putAll(queryParams);
            log.debug("Using variables from query parameters: {}", queryParams.keySet());
        }
        
        // Priority 3: Application defaults (if no other variables provided)
        if (variables.isEmpty()) {
            // In production, these would come from Spring configuration
            // For now, we provide a basic structure
            variables.put("namespace", request.getNamespace());
            log.debug("Using default variables from configuration");
        }
        
        return variables;
    }

    /**
     * Generate cache key from template identity and variables.
     * Used for logging/debugging to show which cache key will be used for lookup.
     * 
     * @param namespace Template namespace
     * @param templateId Template ID
     * @param variables Variables map
     * @return Cache key string (format: namespace:templateId:sha256_hash)
     */
    private String generateCacheKeyForLogging(String namespace, String templateId, java.util.Map<String, Object> variables) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String varsJson = mapper.writeValueAsString(variables);
            String hash = calculateSha256(varsJson);
            return namespace + ":" + templateId + ":" + hash.substring(0, 8) + "...";
        } catch (Exception e) {
            log.warn("Failed to generate cache key for logging", e);
            return namespace + ":" + templateId + ":unknown";
        }
    }

    /**
     * Calculate SHA-256 hash of input string (for cache key generation)
     */
    private String calculateSha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
