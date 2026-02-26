import com.example.demo.docgen.util.PlanComparisonTransformer;
import java.util.*;
import com.jayway.jsonpath.JsonPath;

public class DebugMatrix {
    public static void main(String[] args) {
        List<Map<String,Object>> plans = new ArrayList<>();
        Map<String,Object> basic=new HashMap<>(); basic.put("planName","Basic");
        basic.put("benefits", Arrays.asList(
                Map.of("name","Doctor Visits","value","$20"),
                Map.of("name","Prescriptions","value","$10")
        ));
        Map<String,Object> premium=new HashMap<>(); premium.put("planName","Premium");
        premium.put("benefits", Arrays.asList(
                Map.of("name","Doctor Visits","value","Covered 100%"),
                Map.of("name","Prescriptions","value","$5 copay")
        ));
        plans.add(basic);
        plans.add(premium);
        Map<String,Object> data=new HashMap<>(); data.put("plans",plans);
        Map<String,Object> enriched = PlanComparisonTransformer.injectComparisonMatrix(data, plans, "name", "value", 2);
        Object matrix = enriched.get("comparisonMatrix");
        System.out.println("matrix class=" + (matrix!=null?matrix.getClass():"null"));
        System.out.println("matrix content:"+matrix);
        Object jsonpath = JsonPath.read(enriched, "$.comparisonMatrix");
        System.out.println("jsonpath result class="+(jsonpath!=null?jsonpath.getClass():"null"));
        System.out.println("jsonpath content:"+jsonpath);
    }
}
