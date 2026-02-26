package com.example.demo.docgen.service;

import com.example.demo.docgen.core.RenderContext;
import com.example.demo.docgen.mapper.FieldMappingStrategy;
import com.example.demo.docgen.mapper.JsonPathMappingStrategy;
import com.example.demo.docgen.model.*;
import com.example.demo.docgen.renderer.SectionRenderer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class DocumentComposerTest {

    private DocumentComposer composer;
    private SectionRenderer mockRenderer;
    private TemplateLoader mockTemplateLoader;
    private com.example.demo.docgen.processor.HeaderFooterProcessor mockHeaderFooterProcessor;
        private com.example.demo.docgen.service.ExcelOutputService mockExcelOutputService;

    @BeforeEach
    public void setup() {
        mockRenderer = mock(SectionRenderer.class);
        mockTemplateLoader = mock(TemplateLoader.class);
        mockHeaderFooterProcessor = mock(com.example.demo.docgen.processor.HeaderFooterProcessor.class);
        
        List<SectionRenderer> renderers = Collections.singletonList(mockRenderer);
        List<FieldMappingStrategy> strategies = Collections.singletonList(new JsonPathMappingStrategy());
        
        mockExcelOutputService = mock(com.example.demo.docgen.service.ExcelOutputService.class);
        composer = new DocumentComposer(renderers, strategies, mockTemplateLoader, mockHeaderFooterProcessor, mockExcelOutputService);
        
        when(mockRenderer.supports(any())).thenReturn(true);
        try {
            when(mockRenderer.render(any(), any())).thenReturn(new PDDocument());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testConditionalRendering() throws IOException {
        // Setup template with two sections, one with a condition that fails
        PageSection s1 = PageSection.builder()
                .sectionId("s1")
                .type(SectionType.ACROFORM)
                .condition("$.showS1 == true")
                .order(1)
                .build();
        
        PageSection s2 = PageSection.builder()
                .sectionId("s2")
                .type(SectionType.ACROFORM)
                .condition("$.showS2 == true")
                .order(2)
                .build();

        DocumentTemplate template = DocumentTemplate.builder()
                .templateId("test")
                .sections(Arrays.asList(s1, s2))
                .build();

        when(mockTemplateLoader.loadTemplate(anyString())).thenReturn(template);
        when(mockTemplateLoader.loadTemplate(anyString(), anyMap())).thenReturn(template);

        // Data where only s2 should show
        Map<String, Object> data = new HashMap<>();
        data.put("showS1", false);
        data.put("showS2", true);

        DocumentGenerationRequest request = DocumentGenerationRequest.builder()
                .templateId("test")
                .data(data)
                .build();
        composer.generateDocument(request);

        // Verify s1 was skipped and s2 was rendered
        verify(mockRenderer, never()).render(eq(s1), any());
        verify(mockRenderer, times(1)).render(eq(s2), any());
    }

    @Test
    public void testOverflowHandling() throws IOException {
        // Setup section with overflow
        OverflowConfig overflow = OverflowConfig.builder()
                .arrayPath("$.items")
                .maxItemsInMain(2)
                .itemsPerOverflowPage(2)
                .addendumTemplatePath("addendum.ftl")
                .build();

        PageSection s1 = PageSection.builder()
                .sectionId("main")
                .type(SectionType.ACROFORM)
                .overflowConfigs(Collections.singletonList(overflow))
                .order(1)
                .build();

        DocumentTemplate template = DocumentTemplate.builder()
                .templateId("test")
                .sections(Collections.singletonList(s1))
                .build();

        when(mockTemplateLoader.loadTemplate(anyString())).thenReturn(template);
        when(mockTemplateLoader.loadTemplate(anyString(), anyMap())).thenReturn(template);

        // Data with 5 items (2 in main, 2 in addendum 1, 1 in addendum 2)
        Map<String, Object> data = new HashMap<>();
        data.put("items", Arrays.asList("a", "b", "c", "d", "e"));

        DocumentGenerationRequest request = DocumentGenerationRequest.builder()
                .templateId("test")
                .data(data)
                .build();
        composer.generateDocument(request);

        // Verify main section rendered once
        verify(mockRenderer, times(1)).render(eq(s1), any());
        
        // Verify addendum pages rendered (2 more times for the 3 overflow items)
        // The addendum sections are created dynamically with type FREEMARKER
        verify(mockRenderer, times(2)).render(argThat(s -> s.getSectionId().contains("addendum")), any());
    }

    /**
     * Regression test for auto-transformation matrix key bug (see issue #??).
     *
     * Previously when a template enabled name-based matching without the
     * valuesOnly flag the composition code would log the wrong matrix key
     * and NPE during dimension logging.  This test verifies that no error is
     * recorded and the transformed data contains the expected key.
     */
    @Test
    public void transformPlanData_nameMatching_doesNotLogError() throws Exception {
        // prepare a minimal template with name-matching enabled
        DocumentTemplate template = DocumentTemplate.builder()
                .templateId("plan-comparison-name-matching")
                .config(Map.of(
                        "matchBenefitNamesInTemplate", true,
                        "columnSpacing", 2
                ))
                .build();
        when(mockTemplateLoader.loadTemplate(anyString())).thenReturn(template);
        when(mockTemplateLoader.loadTemplate(anyString(), anyMap())).thenReturn(template);

        // simple plan data
        Map<String, Object> data = new HashMap<>();
        List<Map<String, Object>> plans = new ArrayList<>();
        plans.add(Map.of(
                "planName", "P",
                "benefits", List.of(
                        Map.of("name", "A", "value", "1")
                )
        ));
        data.put("plans", plans);

        DocumentGenerationRequest req = DocumentGenerationRequest.builder()
                .templateId("plan-comparison-name-matching")
                .data(data)
                .build();

        // attach a list appender to capture DocumentComposer logs
        ch.qos.logback.classic.Logger log =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DocumentComposer.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> listAppender =
                new ch.qos.logback.core.read.ListAppender<>();
        listAppender.start();
        log.addAppender(listAppender);

        // Instead of running the whole Excel pipeline (which would throw because
        // our mock renderer doesn't produce a workbook) we call the private
        // transformation helper directly via reflection.  This still exercises the
        // logging logic we care about.
        java.lang.reflect.Method transformMethod = DocumentComposer.class
                .getDeclaredMethod("transformPlanDataIfNeeded",
                        DocumentGenerationRequest.class, DocumentTemplate.class, int.class);
        transformMethod.setAccessible(true);
        transformMethod.invoke(composer, req, template, 2);

        // verify no ERROR message about auto-transformation
        boolean hasError = listAppender.list.stream().anyMatch(e ->
                e.getLevel() == ch.qos.logback.classic.Level.ERROR &&
                        e.getFormattedMessage().contains("Error during auto-transformation"));
        assertFalse(hasError, "Auto-transformation should not log an error");

        // transformed data should contain the values-only matrix
        assertTrue(req.getData().containsKey("comparisonMatrixValues"));
        Object mat = req.getData().get("comparisonMatrixValues");
        assertNotNull(mat, "comparisonMatrixValues should be injected");

        log.detachAppender(listAppender);
    }
}
