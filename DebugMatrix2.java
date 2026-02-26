import com.example.demo.docgen.util.PlanComparisonTransformer;
import java.util.*;
public class DebugMatrix2 {
    public static void main(String[] args) {
        List<Map<String,Object>> plans=new ArrayList<>();
        plans.add(Map.of("planName","X","benefits",Arrays.asList(Map.of("name","B","value","1"))));
        plans.add(Map.of("planName","Y","benefits",Arrays.asList(Map.of("name","B","value","2"))));
        Map<String,Object> data=new HashMap<>(); data.put("plans",plans);
        Map<String,Object> enriched=PlanComparisonTransformer.injectComparisonMatrixValuesOnly(data,plans,"name","value",1);
        System.out.println("matrix class="+enriched.get("comparisonMatrixValues").getClass());
        System.out.println("matrix content="+enriched.get("comparisonMatrixValues"));
    }
}
