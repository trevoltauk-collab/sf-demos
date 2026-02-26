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

            // Extract columnSpacing config from template (default to 1 if not specified)
            int columnSpacing = getColumnSpacingFromTemplate(template);
            
            // Auto-transform plan data if needed (using configured spacing).  Pass template so
            // we can inspect additional configuration like `valuesOnly`.
            transformPlanDataIfNeeded(request, template, columnSpacing);

            RenderContext context = new RenderContext(template, request.getData());
            String ns = request.getNamespace() != null ? request.getNamespace() : "common-templates";
            context.setNamespace(ns);

            List<PageSection> sections = new ArrayList<>(template.getSections());
            sections.sort(Comparator.comparingInt(PageSection::getOrder));

            for (PageSection section : sections) {
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
                    if (wb != null) context.setMetadata("excelWorkbook", wb);
                } else {
                    // Fallback to PDF-based render contract
                    renderer.render(section, context);
                }
            }

            Object wbObj = context.getMetadata("excelWorkbook");
            if (wbObj == null || !(wbObj instanceof org.apache.poi.ss.usermodel.Workbook)) {
                throw new RuntimeException("No Excel workbook produced by renderers");
            }

            org.apache.poi.ss.usermodel.Workbook workbook = (org.apache.poi.ss.usermodel.Workbook) wbObj;
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
    private void transformPlanDataIfNeeded(DocumentGenerationRequest request,
                                             DocumentTemplate template,
                                             int columnSpacing) {
        try {
            // Only auto-transform for the canonical plan-comparison templates.  There
            // may be additional variants (e.g. values-only) so we check prefix rather
            // than hard-code every possible id; the `valuesOnly` flag in the template
            // config will drive which helper is invoked.
            if (request.getTemplateId() == null || !request.getTemplateId().startsWith("plan-comparison")) {
                return; // Not a plan comparison template, skip transformation
            }
            
            Map<String, Object> data = request.getData();
            if (data == null) {
                return; // No data, nothing to transform
            }

            // Determine whether the template expects a values-only matrix
            // This applies to both "valuesOnly" mode and "matchBenefitNamesInTemplate" (name-matching) mode
            boolean valuesOnly = false;
            boolean matchBenefitNames = false;
            
            if (template.getConfig() != null) {
                Object flag = template.getConfig().get("valuesOnly");
                valuesOnly = flag instanceof Boolean && (Boolean) flag;
                
                Object matchFlag = template.getConfig().get("matchBenefitNamesInTemplate");
                matchBenefitNames = matchFlag instanceof Boolean && (Boolean) matchFlag;
            }
            
            // When name-matching mode is enabled, use values-only matrix (benefit column excluded)
            boolean useValuesOnly = valuesOnly || matchBenefitNames;

            // Skip if the appropriate matrix already exists in the data
            if (!useValuesOnly && data.containsKey("comparisonMatrix")) {
                log.debug("comparisonMatrix already exists in data, skipping auto-transformation");
                return;
            }
            if (useValuesOnly && data.containsKey("comparisonMatrixValues")) {
                log.debug("comparisonMatrixValues already exists in data, skipping auto-transformation");
                return;
            }
            
            // Check if plans data is present
            Object plansObj = data.get("plans");
            if (!(plansObj instanceof List)) {
                return; // No plans field or not a list, skip transformation
            }
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> plans = (List<Map<String, Object>>) plansObj;
            
            if (plans.isEmpty()) {
                return; // Empty plans, skip transformation
            }
            
            // Transform the plan data into a comparison matrix with configured spacing
            log.info("Auto-transforming plan data into comparison matrix for {} template (spacing: {}, valuesOnly: {}, matchBenefitNames: {})",
                request.getTemplateId(), columnSpacing, valuesOnly, matchBenefitNames);

            Map<String, Object> enrichedData;
            if (useValuesOnly) {
                enrichedData = PlanComparisonTransformer.injectComparisonMatrixValuesOnly(
                    data,
                    plans,
                    "name",
                    "value",
                    columnSpacing
                );
            } else {
                enrichedData = PlanComparisonTransformer.injectComparisonMatrix(
                    data,
                    plans,
                    "name",
                    "value",
                    columnSpacing
                );

                // Optionally build a two-row header (primary plan name above, group below)
                boolean twoRowHeader = false;
                String groupField = "group"; // default field name for plan group
                if (template.getConfig() != null) {
                    Object flag = template.getConfig().get("twoRowHeader");
                    twoRowHeader = flag instanceof Boolean && (Boolean) flag;
                    Object gf = template.getConfig().get("groupField");
                    if (gf instanceof String && !((String) gf).isEmpty()) {
                        groupField = (String) gf;
                    }
                }

                if (twoRowHeader) {
                    @SuppressWarnings("unchecked")
                    List<List<Object>> matrix = (List<List<Object>>) enrichedData.get("comparisonMatrix");
                    if (matrix != null && !matrix.isEmpty()) {
                        // Compute total columns expected
                        int totalColumns = 1 + (plans.size() * (1 + columnSpacing));

                        // Build header row 1 (primary plan names)
                        List<Object> header1 = new ArrayList<>(totalColumns);
                        header1.add("Benefit");
                        for (Map<String, Object> plan : plans) {
                            for (int s = 0; s < columnSpacing; s++) header1.add("");
                            Object pn = plan.get("planName");
                            header1.add(pn != null ? pn : "");
                        }

                        // Build header row 2 (group names)
                        List<Object> header2 = new ArrayList<>(totalColumns);
                        header2.add("");
                        for (Map<String, Object> plan : plans) {
                            for (int s = 0; s < columnSpacing; s++) header2.add("");
                            Object grp = plan.get(groupField);
                            header2.add(grp != null ? grp : "");
                        }

                        // Combine: drop the original single header row produced by transformer
                        List<List<Object>> full = new ArrayList<>();
                        full.add(header1);
                        full.add(header2);
                        if (matrix.size() > 1) {
                            full.addAll(matrix.subList(1, matrix.size()));
                        }

                        enrichedData.put("comparisonMatrix", full);
                    }
                }
            }
            
            // Update the request's data with the enriched version containing matrix
            request.setData(enrichedData);
            
            // Log dimensions using whichever key we injected
            // NOTE: prior bug: name-matching mode sets matchBenefitNames=true without
            // valuesOnly, so the matrix is stored under comparisonMatrixValues even
            // though valuesOnly=false.  We must use `useValuesOnly` (which includes
            // matchBenefitNames) when picking the key, otherwise we log with the
            // wrong key and end up with NPE when mat is null.
            String matrixKey = useValuesOnly ? "comparisonMatrixValues" : "comparisonMatrix";
            List<?> mat = (List<?>) enrichedData.get(matrixKey);
            if (mat == null) {
                log.warn("Plan comparison auto-transformation produced no matrix for key '{}'; " +
                         "the request data may not contain expected matrix.", matrixKey);
            } else {
                log.info("Plan comparison matrix auto-injection complete. Matrix dimensions: {} rows x {} columns", 
                    mat.size(),
                    mat.isEmpty() ? 0 : ((List<?>) mat.get(0)).size());
            }
        } catch (Exception e) {
            log.error("Error during auto-transformation of plan data, proceeding without transformation", e);
            // Don't fail the entire generation if auto-transformation has issues
            // The rendering may still work with raw plan data or may fail with a more specific error
        }
    }
}
