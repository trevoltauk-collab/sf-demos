import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import java.io.*;
import java.nio.file.*;
public class ReadGeneratedExcel {
  public static void main(String[] args) throws Exception {
    Path p = Paths.get("docs/sample-multi-section-workbook.xlsx");
    if (!Files.exists(p)) { System.out.println("MISSING: " + p.toAbsolutePath()); return; }
    try (Workbook wb = new XSSFWorkbook(Files.newInputStream(p))) {
      System.out.println("Sheets: " + wb.getNumberOfSheets());
      System.out.println("Sheet names:");
      for (int i=0;i<wb.getNumberOfSheets();i++) System.out.println(" - " + wb.getSheetName(i));

      Sheet s = wb.getSheet("Summary");
      System.out.println("\n== Summary ==");
      printCell(s,0,0); // A1
      printCell(s,1,0); // A2
      printCell(s,4,1); // B5

      s = wb.getSheet("Departments");
      System.out.println("\n== Departments ==");
      printCell(s,2,0); // A3
      printCell(s,2,1); // B3
      printCell(s,3,0); // A4
      printCell(s,4,0); // A5

      s = wb.getSheet("Employees");
      System.out.println("\n== Employees ==");
      printCell(s,3,0); // A4
      printCell(s,3,1); // B4
      printCell(s,3,2); // C4
      printCell(s,4,0); // A5
      printCell(s,5,0); // A6

      s = wb.getSheet("Analysis");
      System.out.println("\n== Analysis ==");
      printCell(s,0,0); // A1
      printCell(s,4,1); // B5
    }
  }
  static void printCell(Sheet s, int r, int c) {
    if (s==null) { System.out.println("Sheet missing"); return; }
    Row row = s.getRow(r);
    if (row==null) { System.out.println("Row " + (r+1) + " missing"); return; }
    Cell cell = row.getCell(c);
    if (cell==null) { System.out.println("Cell " + cellRef(r,c) + " empty"); return; }
    switch(cell.getCellType()) {
      case STRING: System.out.println(cellRef(r,c) + " = '" + cell.getStringCellValue() + "'"); break;
      case NUMERIC: System.out.println(cellRef(r,c) + " = " + cell.getNumericCellValue()); break;
      case BOOLEAN: System.out.println(cellRef(r,c) + " = " + cell.getBooleanCellValue()); break;
      case FORMULA: System.out.println(cellRef(r,c) + " formula='" + cell.getCellFormula() + "'"); break;
      case BLANK: System.out.println(cellRef(r,c) + " blank"); break;
      default: System.out.println(cellRef(r,c) + " val=" + cell.toString());
    }
  }
  static String cellRef(int r, int c) { return "R" + (r+1) + "C" + (c+1); }
}
