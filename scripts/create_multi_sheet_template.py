#!/usr/bin/env python3
# Creates a simple XLSX with two sheets: Customers and Pricing
# Requires: pip install openpyxl

from openpyxl import Workbook
from pathlib import Path

out = Path("src/main/resources/templates")
out.mkdir(parents=True, exist_ok=True)

wb = Workbook()
ws1 = wb.active
ws1.title = "Customers"
ws1["A1"] = "Name"
ws1["B1"] = "Email"

ws2 = wb.create_sheet("Pricing")
ws2.append(["Tier", "Price", "Notes"])

path = out / "multi-sheet-template.xlsx"
wb.save(path)
print("Created:", path)
