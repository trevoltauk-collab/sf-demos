package com.example.demo.docgen.service;

import com.example.demo.docgen.aspect.LogExecutionTime;
import com.example.demo.docgen.core.RenderContext;
import com.example.demo.docgen.exception.ResourceLoadingException;
import com.example.demo.docgen.exception.TemplateLoadingException;
import com.example.demo.docgen.mapper.FieldMappingStrategy;
import com.example.demo.docgen.mapper.JsonPathMappingStrategy;
import com.example.demo.docgen.mapper.MappingType;
import com.example.demo.docgen.model.DocumentGenerationRequest;
import com.example.demo.docgen.model.DocumentTemplate;
import com.example.demo.docgen.model.OverflowConfig;
import com.example.demo.docgen.model.PageSection;
import com.example.demo.docgen.model.SectionType;
import com.example.demo.docgen.renderer.SectionRenderer;
import com.example.demo.docgen.util.PlanComparisonTransformer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main orchestrator for document generation
 * Coordinates template loading, section rendering, and PDF assembly
 *
 * Notes:
 * - This class is intentionally thin: it coordinates the template loader,
 *   renderers and post-processing (header/footer, merges) but delegates the
 *   heavy work to `TemplateLoader` and `SectionRenderer` implementations.
 * - Keep this orchestration logic deterministic and side-effect free when
 *   possible to make unit testing simpler (renderers can be mocked).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentComposer {
    private final List<SectionRenderer> renderers;
    private final List<FieldMappingStrategy> mappingStrategies;
    private final TemplateLoader templateLoader;
    private final com.example.demo.docgen.processor.HeaderFooterProcessor headerFooterProcessor;
    private final com.example.demo.docgen.service.ExcelOutputService excelOutputService;
    
    /**
     * Generate a PDF document from a template and data
     *
     * @param request Generation request with template ID and data
     * @return PDF document as byte array
     */
    @LogExecutionTime("Total Document Generation")
    public byte[] generateDocument(DocumentGenerationRequest request) {
        log.info("Generating document with template: {} from namespace: {}", request.getTemplateId(), request.getNamespace());
        
        try {
            // Load template structure with namespace support. TemplateLoader is
            // responsible for inheritance, fragment resolution and placeholder
            // interpolation. Errors at this stage are TemplateLoadingException.
            DocumentTemplate template;
            if (request.getNamespace() != null) {
                template = templateLoader.loadTemplate(request.getNamespace(), request.getTemplateId(), request.getData());
            } else {
                // Backwards-compatible call used by unit tests and legacy callers
                template = templateLoader.loadTemplate(request.getTemplateId(), request.getData());
            }

            // Build a RenderContext that carries the template and the runtime data
            // to the SectionRenderers. The context is the single source of truth
            // for renderer evaluation (mapping, conditions, data lookups).
            RenderContext context = new RenderContext(template, request.getData());
            String ns = request.getNamespace() != null ? request.getNamespace() : "common-templates";
            context.setNamespace(ns);
            
            // Render each section into an in-memory PDDocument. Sections are
            // processed in order; each SectionRenderer returns a PDDocument
            // representing that section's PDF content.
            List<PDDocument> sectionDocuments = new ArrayList<>();
            List<PageSection> sections = new ArrayList<>(template.getSections());
            sections.sort(Comparator.comparingInt(PageSection::getOrder));
            
            for (PageSection section : sections) {
                // Evaluate condition if present
                if (section.getCondition() != null && !section.getCondition().isEmpty()) {
                    FieldMappingStrategy strategy = findMappingStrategy(section.getMappingType());
                    Object result = strategy.evaluatePath(context.getData(), section.getCondition());
                    
                    boolean shouldRender = false;
                    if (result instanceof Boolean) {
                        shouldRender = (Boolean) result;
                    } else if (result != null) {
                        // If it's not a boolean, we consider it true if it's not null/empty
                        shouldRender = !result.toString().isEmpty() && !result.toString().equalsIgnoreCase("false");
                    }
                    
                    if (!shouldRender) {
                        log.info("Skipping section {} due to condition: {}", section.getSectionId(), section.getCondition());
                        continue;
                    }
                }

                log.info("Rendering section: {} (type: {})", section.getSectionId(), section.getType());
                context.setCurrentSectionId(section.getSectionId());
                
                SectionRenderer renderer = findRenderer(section.getType());
                PDDocument sectionDoc = renderer.render(section, context);
                sectionDocuments.add(sectionDoc);

                // Handle overflows if configured
                if (section.getOverflowConfigs() != null && !section.getOverflowConfigs().isEmpty()) {
                    for (OverflowConfig config : section.getOverflowConfigs()) {
                        List<PDDocument> overflowDocs = handleOverflow(section, config, context);
                        sectionDocuments.addAll(overflowDocs);
                    }
                }
            }
            
            // Merge all generated section PDFs into a single PDDocument. Note:
            // - When there is exactly one section the merger returns that document
            //   directly (no copy), so closing behavior must avoid double-closing.
            // - For merged outputs we create a new PDDocument and append all
            //   sections into it which should then be saved and closed.
            PDDocument mergedDocument = mergeSections(sectionDocuments);
            
            // Apply headers and footers using the dedicated processor which will
            // iterate pages and stamp content. This mutates `mergedDocument`.
            headerFooterProcessor.apply(
                mergedDocument,
                template.getHeaderFooterConfig(),
                request.getData()
            );
            
            // Serialize to bytes for transport/storage
            byte[] pdfBytes = convertToBytes(mergedDocument);
            
            // Cleanup - close documents and handle merged document separately to avoid double-closing
            // Close all section documents first. Section renderers may return the
            // same PDDocument instance that `mergedDocument` points to in the
            // single-section case; hence the explicit size check below.
            for (PDDocument doc : sectionDocuments) {
                try {
                    if (doc != null) {
                        doc.close();
                    }
                } catch (IOException e) {
                    log.warn("Error closing section document", e);
                }
            }
            
            // Only close merged document if it's not one of the section documents (would be double-closed)
            if (sectionDocuments.size() != 1) {
                try {
                    if (mergedDocument != null) {
                        mergedDocument.close();
                    }
                } catch (IOException e) {
                    log.warn("Error closing merged document", e);
                }
            }
            
            log.info("Document generation complete. Size: {} bytes", pdfBytes.length);
            return pdfBytes;
            
        } catch (TemplateLoadingException tle) {
            // Propagate template loading exceptions so controller can return user-friendly response
            log.error("Template resolution error", tle);
            throw tle;
        } catch (ResourceLoadingException rle) {
            // Propagate resource loading exceptions so controller can return user-friendly response
            log.error("Resource loading error", rle);
            throw rle;
        } catch (Exception e) {
            log.error("Document generation failed", e);
            throw new RuntimeException("Failed to generate document", e);
        }
    }

    /**
     * Generate an Excel workbook from a template and data and return XLSX bytes.
     * The ExcelSectionRenderer stores the filled workbook in the RenderContext metadata under "excelWorkbook".
     * 
     * Auto-transformation: If the data contains a "plans" field and the template is "plan-comparison",
     * automatically transforms the nested plan data into a 2D comparison matrix for rendering.
     */
    @LogExecutionTime("Total Excel Generation")
    public byte[] generateExcel(DocumentGenerationRequest request) {
        log.info("Generating EXCEL with template: {} from namespace: {}", request.getTemplateId(), request.getNamespace());

        try {
            // Load template first to extract configuration (needed for auto-transformation)
            DocumentTemplate template;
            if (request.getNamespace() != null) {
                template = templateLoader.loadTemplate(request.getNamespace(), request.getTemplateId(), request.getData());
            } else {
                template = templateLoader.loadTemplate(request.getTemplateId(), request.getData());
            }

            // preserve original data so each section can start from a clean copy
            Map<String,Object> originalData = request.getData() != null ? request.getData() : new HashMap<>();

            List<PageSection> sections = new ArrayList<>(template.getSections());
            sections.sort(Comparator.comparingInt(PageSection::getOrder));

            // create a single context that will be reused for all sections; its data map
            // will be replaced on each iteration so that metadata (workbook, template
            // path, etc.) persists across sections of the same template.
            RenderContext context = new RenderContext(template, new HashMap<>(originalData));
            String ns = request.getNamespace() != null ? request.getNamespace() : "common-templates";
            context.setNamespace(ns);

            org.apache.poi.ss.usermodel.Workbook workbook = null;

            for (PageSection section : sections) {
                // determine spacing for this section (section overrides template)
                int columnSpacing = getColumnSpacing(section, template);

                // compute section-specific data and then overwrite the context map
                Map<String,Object> sectionData = applySectionTransform(originalData, template, section, columnSpacing);
                context.getData().clear();
                context.getData().putAll(sectionData);

                if (section.getCondition() != null && !section.getCondition().isEmpty()) {
                    FieldMappingStrategy strategy = findMappingStrategy(section.getMappingType());
                    Object result = strategy.evaluatePath(context.getData(), section.getCondition());
                    boolean shouldRender = false;
                    if (result instanceof Boolean) {
                        shouldRender = (Boolean) result;
                    } else if (result != null) {
                        shouldRender = !result.toString().isEmpty() && !result.toString().equalsIgnoreCase("false");
                    }
                    if (!shouldRender) continue;
                }

                context.setCurrentSectionId(section.getSectionId());
                SectionRenderer renderer = findRenderer(section.getType());
                // If renderer can produce a Workbook directly, use that API path
                if (renderer instanceof com.example.demo.docgen.renderer.ExcelRenderer) {
                    org.apache.poi.ss.usermodel.Workbook wb = ((com.example.demo.docgen.renderer.ExcelRenderer) renderer).renderWorkbook(section, context);
                    if (wb != null) {
                        workbook = wb;
                        context.setMetadata("excelWorkbook", wb);
                    }
                } else {
                    // Fallback to PDF-based render contract
                    renderer.render(section, context);
                }
            }

            if (workbook == null) {
                throw new RuntimeException("No Excel workbook produced by renderers");
            }
            return excelOutputService.toBytes(workbook);

        } catch (TemplateLoadingException | ResourceLoadingException e) {
            log.error("Template/resource error during Excel generation", e);
            throw e;
        } catch (Exception e) {
            log.error("Excel generation failed", e);
            throw new RuntimeException("Failed to generate Excel", e);
        }
    }

    /**
     * Handles data overflow by generating addendum pages
     */
    private List<PDDocument> handleOverflow(PageSection section, OverflowConfig config, RenderContext context) throws IOException {
        List<PDDocument> overflowDocs = new ArrayList<>();
        
        if (config.getArrayPath() == null || config.getAddendumTemplatePath() == null) {
            return overflowDocs;
        }

        // 1. Evaluate arrayPath using the specified strategy
        FieldMappingStrategy strategy = findMappingStrategy(config.getMappingType());
        Object collection = strategy.evaluatePath(context.getData(), config.getArrayPath());
        
        if (!(collection instanceof List)) {
            log.debug("Overflow arrayPath '{}' did not evaluate to a List", config.getArrayPath());
            return overflowDocs;
        }
        
        List<?> allItems = (List<?>) collection;
        if (allItems.size() <= config.getMaxItemsInMain()) {
            log.debug("No overflow detected for section {}. Items: {}, Max: {}", 
                     section.getSectionId(), allItems.size(), config.getMaxItemsInMain());
            return overflowDocs;
        }
        
        log.info("Overflow detected for section {}. Total items: {}, Max in main: {}", 
                 section.getSectionId(), allItems.size(), config.getMaxItemsInMain());
        
        // 2. Get overflow items
        List<?> overflowItems = allItems.subList(config.getMaxItemsInMain(), allItems.size());
        
        // 3. Partition into pages and render
        int pageSize = config.getItemsPerOverflowPage() > 0 ? config.getItemsPerOverflowPage() : overflowItems.size();
        for (int i = 0; i < overflowItems.size(); i += pageSize) {
            List<?> chunk = overflowItems.subList(i, Math.min(i + pageSize, overflowItems.size()));
            int pageNum = (i / pageSize) + 1;
            
            log.info("Rendering addendum page {} for section {} with {} items", 
                     pageNum, section.getSectionId(), chunk.size());

            // Create a temporary section for the addendum
            PageSection addendumSection = PageSection.builder()
                .sectionId(section.getSectionId() + "_addendum_" + pageNum)
                .type(SectionType.FREEMARKER)
                .templatePath(config.getAddendumTemplatePath())
                .build();
            
            // Create a temporary data map for this addendum page
            Map<String, Object> addendumData = new HashMap<>(context.getData());
            addendumData.put("overflowItems", chunk);
            addendumData.put("isAddendum", true);
            addendumData.put("addendumPageNumber", pageNum);
            addendumData.put("totalAddendumPages", (int) Math.ceil((double) overflowItems.size() / pageSize));
            
            RenderContext addendumContext = new RenderContext(context.getTemplate(), addendumData);
            
            SectionRenderer renderer = findRenderer(SectionType.FREEMARKER);
            overflowDocs.add(renderer.render(addendumSection, addendumContext));
        }
        
        return overflowDocs;
    }

    private FieldMappingStrategy findMappingStrategy(MappingType type) {
        return mappingStrategies.stream()
            .filter(s -> s.supports(type))
            .findFirst()
            .orElseThrow(() -> new UnsupportedOperationException("No strategy found for type: " + type));
    }
    
    private SectionRenderer findRenderer(com.example.demo.docgen.model.SectionType type) {
        return renderers.stream()
            .filter(r -> r.supports(type))
            .findFirst()
            .orElseThrow(() -> new UnsupportedOperationException(
                "No renderer found for section type: " + type));
    }
    
    @LogExecutionTime("Merging PDF Sections")
    private PDDocument mergeSections(List<PDDocument> sections) throws IOException {
        if (sections.isEmpty()) {
            return new PDDocument();
        }
        
        if (sections.size() == 1) {
            return sections.get(0);
        }
        
        PDDocument result = new PDDocument();
        PDFMergerUtility merger = new PDFMergerUtility();
        
        for (PDDocument section : sections) {
            merger.appendDocument(result, section);
        }
        
        return result;
    }
    
    private byte[] convertToBytes(PDDocument document) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        document.save(baos);
        return baos.toByteArray();
    }

    /**
     * Extract column spacing configuration from template.
     * 
     * Looks for config.columnSpacing in the template definition.
     * Default is 1 column of spacing between plan columns.
     * 
     * @param template The loaded document template
     * @return Column spacing width (default: 1)
     */
    private int getColumnSpacingFromTemplate(DocumentTemplate template) {
        try {
            if (template != null && template.getConfig() != null) {
                Object spacingObj = template.getConfig().get("columnSpacing");
                if (spacingObj instanceof Integer) {
                    int spacing = (Integer) spacingObj;
                    if (spacing >= 0) {
                        log.debug("Using configured column spacing: {}", spacing);
                        return spacing;
                    }
                } else if (spacingObj instanceof Number) {
                    int spacing = ((Number) spacingObj).intValue();
                    if (spacing >= 0) {
                        log.debug("Using configured column spacing: {}", spacing);
                        return spacing;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error extracting columnSpacing from template, using default", e);
        }
        log.debug("No columnSpacing config found or invalid, using default: 1");
        return 1; // Default spacing
    }

    /**
     * Auto-transform plan data into comparison matrix if conditions are met.
     * 
     * Detects if:
     * 1. The template is "plan-comparison" (exact match)
     * 2. The data contains a "plans" field (List of plans)
     * 3. The data does NOT already have a "comparisonMatrix" field
     * 
     * If all conditions are met, transforms the nested plan/benefits structure
     * into a 2D matrix using PlanComparisonTransformer and injects it into the data.
     * 
     * This allows clients to send raw plan data without needing to call the
     * transformer themselves. The server handles the transformation transparently.
     * 
     * @param request The document generation request containing template ID and data
     * @param columnSpacing Number of columns between plan columns (from template config)
     */
    /**
     * Legacy helper used by existing tests.  It wraps the new
     * {@link #applySectionTransform(Map,DocumentTemplate,PageSection,int)}
     * functionality using a synthetic section that has the template's config.
     *
     * This method mutates the request data map in-place and therefore retains
     * the original behaviour clients relied upon.
     */
    private void transformPlanDataIfNeeded(DocumentGenerationRequest request,
                                             DocumentTemplate template,
                                             int columnSpacing) {
        Map<String, Object> dummyData = request.getData() != null ? request.getData() : new HashMap<>();
        // create a fake section whose config mirrors the template-level config
        PageSection dummySection = PageSection.builder()
                .sectionId("__global__")
                .config(template.getConfig() != null ? template.getConfig() : new HashMap<>())
                .build();
        Map<String,Object> transformed = applySectionTransform(dummyData, template, dummySection, columnSpacing);
        request.setData(transformed);
    }

    /**
     * Decide column spacing by consulting section config first, then falling
     * back to template config (defaulting to 1 if neither specifies a valid
     * value).
     */
    private int getColumnSpacing(PageSection section, DocumentTemplate template) {
        try {
            if (section != null && section.getConfig() != null) {
                Object spacingObj = section.getConfig().get("columnSpacing");
                if (spacingObj instanceof Number) {
                    int spacing = ((Number) spacingObj).intValue();
                    if (spacing >= 0) {
                        log.debug("Using section-specific columnSpacing: {}", spacing);
                        return spacing;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error extracting columnSpacing from section config, deferring to template", e);
        }
        return getColumnSpacingFromTemplate(template);
    }

    /**
     * Apply any transformer specified either at the template level or the
     * individual section level.  Operates on a copy of the supplied data map
     * and returns the potentially-enriched map.
     */
    private Map<String,Object> applySectionTransform(Map<String,Object> originalData,
                                                     DocumentTemplate template,
                                                     PageSection section,
                                                     int columnSpacing) {
        Map<String,Object> data = new HashMap<>(originalData != null ? originalData : new HashMap<>());
        // build combined config: template first, then override with section values
        Map<String,Object> combined = new HashMap<>();
        if (template.getConfig() != null) combined.putAll(template.getConfig());
        if (section != null && section.getConfig() != null) combined.putAll(section.getConfig());

        // determine which transformer (if any) is requested.  we support
        // both the original "plan-comparison" and the new "age-rating" names
        String transformerName = null;
        if (combined.containsKey("transformer") && combined.get("transformer") instanceof String) {
            transformerName = (String) combined.get("transformer");
        }
        boolean wantsTransform = false;
        if ("plan-comparison".equals(transformerName) || "age-rating".equals(transformerName)) {
            wantsTransform = true;
        }
        // legacy: if no explicit transformer, fall back to templateId prefix
        if (!wantsTransform && template.getTemplateId() != null) {
            if (template.getTemplateId().startsWith("plan-comparison") ||
                    template.getTemplateId().startsWith("age-rating")) {
                wantsTransform = true;
                // implicitly treat legacy prefix as plan-comparison so we don't
                // accidentally switch behaviour; we only check templateId for
                // triggering, not for determining which transformer to run.
                if (transformerName == null) {
                    transformerName = "plan-comparison";
                }
            }
        }
        if (!wantsTransform) {
            return data;
        }

        // determine value-only or name-match behaviour using combined config
        boolean valuesOnly = false;
        boolean matchNames = false;
        if (combined.containsKey("valuesOnly") && combined.get("valuesOnly") instanceof Boolean) {
            valuesOnly = (Boolean) combined.get("valuesOnly");
        }
        if (combined.containsKey("matchBenefitNamesInTemplate") && combined.get("matchBenefitNamesInTemplate") instanceof Boolean) {
            matchNames = (Boolean) combined.get("matchBenefitNamesInTemplate");
        }
        boolean useValues = valuesOnly || matchNames;

        // check if there is any plan data present (used to decide whether to override
        // an existing matrix).  We evaluate the same path we'll use later so the
        // logic stays in sync; having to compute it twice is a minor cost.
        boolean hasPlans = false;
        Object plansPreview;
        if (combined.containsKey("plansPath") && combined.get("plansPath") instanceof String) {
            String path = (String) combined.get("plansPath");
            FieldMappingStrategy json = new com.example.demo.docgen.mapper.JsonPathMappingStrategy();
            plansPreview = json.evaluatePath(data, path);
        } else {
            plansPreview = data.get("plans");
        }
        if (plansPreview instanceof List && !((List<?>) plansPreview).isEmpty()) {
            hasPlans = true;
        }

        // If an appropriate matrix already exists, prefer the provided matrix
        // and skip the auto-transformation. This lets clients supply precomputed
        // matrices that should be used as-is. Only skip when the provided matrix
        // is actually present and non-empty (defensive against empty/placeholder values).
        Object existingMatrix = useValues ? data.get("comparisonMatrixValues") : data.get("comparisonMatrix");
        // debug: log presence/types/sizes of potential matrix keys and plans to help diagnose test failures
        try {
            Object cm = data.get("comparisonMatrix");
            Object cmv = data.get("comparisonMatrixValues");
            String cmType = cm == null ? "null" : cm.getClass().getName();
            String cmvType = cmv == null ? "null" : cmv.getClass().getName();
            int cmSize = (cm instanceof List) ? ((List<?>) cm).size() : -1;
            int cmvSize = (cmv instanceof List) ? ((List<?>) cmv).size() : -1;
            String plansType = plansPreview == null ? "null" : plansPreview.getClass().getName();
            int plansSize = (plansPreview instanceof List) ? ((List<?>) plansPreview).size() : -1;
            log.debug("Combined config keys: {}", combined.keySet());
            log.debug("Incoming data keys: {}", data.keySet());
            log.debug("plansPreview type={} size={}; comparisonMatrix type={} size={}; comparisonMatrixValues type={} size={}; valuesOnly={}; matchNames={}",
                    plansType, plansSize, cmType, cmSize, cmvType, cmvSize, valuesOnly, matchNames);
        } catch (Exception ex) {
            log.debug("Failed to introspect existing matrix keys or plans preview", ex);
        }
        boolean hasAppropriateMatrix = false;
        if (existingMatrix instanceof List) {
            List<?> lm = (List<?>) existingMatrix;
            hasAppropriateMatrix = !lm.isEmpty();
        } else if (existingMatrix != null) {
            // If a non-list value exists for the matrix key, log for diagnostics
            log.debug("Existing matrix key is present but not a List (type={})", existingMatrix.getClass().getName());
        }
        // If an appropriate matrix exists and there are *no* plans provided, we
        // assume the caller wants to use their precomputed matrix. However, when
        // plans are also present we favour regenerating the matrix from the
        // latest plan data. This mirrors the behavior expected by
        // transformPlanData_withPlansAndMatrix_overridesMatrix test.
        if (hasAppropriateMatrix && !hasPlans) {
            log.debug("Section {} already has appropriate matrix and no plans present, skipping transform", section == null ? "<none>" : section.getSectionId());
            return data;
        }

        // locate plans array via plansPath or default
        Object plansObj;
        if (combined.containsKey("plansPath") && combined.get("plansPath") instanceof String) {
            String path = (String) combined.get("plansPath");
            FieldMappingStrategy json = new com.example.demo.docgen.mapper.JsonPathMappingStrategy();
            plansObj = json.evaluatePath(data, path);
        } else {
            plansObj = data.get("plans");
        }
        if (!(plansObj instanceof List)) {
            return data;
        }
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> plans = (List<Map<String,Object>>) plansObj;
        if (plans.isEmpty()) {
            return data;
        }

        log.info("Auto-transforming plan data for section {} (template {}); transformer={}, spacing={}, valuesOnly={}, matchNames={}",
                section == null ? "<none>" : section.getSectionId(), template.getTemplateId(), transformerName,
                columnSpacing, valuesOnly, matchNames);

        if ("age-rating".equals(transformerName)) {
            // read any custom field names
            String ageField = "age";
            String ratingField = "rating";
            if (combined.containsKey("ageField") && combined.get("ageField") instanceof String) {
                ageField = (String) combined.get("ageField");
            }
            if (combined.containsKey("ratingField") && combined.get("ratingField") instanceof String) {
                ratingField = (String) combined.get("ratingField");
            }
            return PlanComparisonTransformer.injectAgeRatings(data, plans, ageField, ratingField, columnSpacing);
        } else {
            if (useValues) {
                return PlanComparisonTransformer.injectComparisonMatrixValuesOnly(data, plans, "name", "value", columnSpacing);
            } else {
                return PlanComparisonTransformer.injectComparisonMatrix(data, plans, "name", "value", columnSpacing);
            }
        }
    }
    
}
