package com.example.demo.docgen;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonPathTest {
    public static void main(String[] args) throws Exception {
        String json = "{\"customers\":[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]}";
        ObjectMapper mapper = new ObjectMapper();
        Map data = mapper.readValue(json, Map.class);
        Object result = JsonPath.read(data, "$.customers[*].name");
        System.out.println("result class: " + result.getClass().getName());
        System.out.println("result toString: " + result);
        if (result instanceof java.util.List) {
            java.util.List list = (java.util.List) result;
            System.out.println("list size = " + list.size());
            for (Object o : list) System.out.println(" item: " + o + " (" + (o!=null?o.getClass().getName():"null") + ")");
        }
    }
}
