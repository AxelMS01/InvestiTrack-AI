package com.example.auditservice.domain;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Csv {
    private Csv() {}
    public static byte[] write(List<String> columns,List<Map<String,Object>> rows) {
        var output=new StringBuilder();line(output,new ArrayList<>(columns));
        for(var row:rows)line(output,columns.stream().map(c->Objects.toString(row.get(c),"")).toList());
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void line(StringBuilder out,List<String> values) {
        var cells=new ArrayList<String>();
        for(String value:values) {
            // Spreadsheet applications interpret formulas even in quoted CSV cells.
            String stripped=value.stripLeading();
            if(!stripped.isEmpty()&&"=+-@".indexOf(stripped.charAt(0))>=0||value.startsWith("\t")||value.startsWith("\r")||value.startsWith("\n"))value="'"+value;
            cells.add("\""+value.replace("\"","\"\"")+"\"");
        }
        out.append(String.join(",",cells)).append("\r\n");
    }
}
