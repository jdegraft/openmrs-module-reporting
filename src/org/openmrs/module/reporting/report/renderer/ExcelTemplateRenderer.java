/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.reporting.report.renderer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.openmrs.annotation.Handler;
import org.openmrs.module.reporting.common.ExcelUtil;
import org.openmrs.module.reporting.common.Localized;
import org.openmrs.module.reporting.common.ObjectUtil;
import org.openmrs.module.reporting.dataset.DataSet;
import org.openmrs.module.reporting.dataset.DataSetRow;
import org.openmrs.module.reporting.evaluation.EvaluationUtil;
import org.openmrs.module.reporting.report.ReportData;
import org.openmrs.module.reporting.report.ReportDesign;
import org.openmrs.module.reporting.report.ReportDesignResource;

/**
 * Report Renderer implementation that supports rendering to an Excel template
 */
@Handler
@Localized("reporting.ExcelTemplateRenderer")
public class ExcelTemplateRenderer extends ReportTemplateRenderer {
	
	private Log log = LogFactory.getLog(this.getClass());
	
	public ExcelTemplateRenderer() {
		super();
	}

	/** 
	 * @see ReportRenderer#render(ReportData, String, OutputStream)
	 */
	public void render(ReportData reportData, String argument, OutputStream out) throws IOException, RenderingException {
		
		try {
			log.debug("Attempting to render report with ExcelTemplateRenderer");
			ReportDesign design = getDesign(argument);
			HSSFWorkbook wb = getExcelTemplate(design);

			// Put together base set of replacements.  Any dataSet with only one row is included.
			Map<String, Object> replacements = getBaseReplacementData(reportData, design);
			
			// Iterate across all of the sheets in the workbook, and configure all those that need to be added/cloned
			List<SheetToAdd> sheetsToAdd = new ArrayList<SheetToAdd>();

			Set<String> usedSheetNames = new HashSet<String>();
			int numberOfSheets = wb.getNumberOfSheets();
			
			for (int sheetNum=0; sheetNum<numberOfSheets; sheetNum++) {
				
				HSSFSheet currentSheet = wb.getSheetAt(sheetNum);
				String originalSheetName = wb.getSheetName(sheetNum);
				
				String dataSetName = getRepeatingSheetProperty(sheetNum, design);
				if (dataSetName != null) {
					
					DataSet repeatingSheetDataSet = reportData.getDataSets().get(dataSetName);
					int dataSetRowNum = 0;
					for (Iterator<DataSetRow> rowIterator = repeatingSheetDataSet.iterator(); rowIterator.hasNext();) {
						DataSetRow dataSetRow = rowIterator.next();
						dataSetRowNum++;
						
						Map<String, Object> newReplacements = new HashMap<String, Object>(replacements);
						newReplacements.putAll(getReplacementData(reportData, design, dataSetName, dataSetRow));
						
						HSSFSheet newSheet = (dataSetRowNum == 1 ? currentSheet : wb.cloneSheet(sheetNum));
						sheetsToAdd.add(new SheetToAdd(newSheet, sheetNum, originalSheetName, newReplacements));
					}
				}
				else {
					sheetsToAdd.add(new SheetToAdd(currentSheet, sheetNum, originalSheetName, replacements));
				}
			}
			
			// Then iterate across all of these and add them in
			for (int i=0; i<sheetsToAdd.size(); i++) {
				addSheet(wb, sheetsToAdd.get(i), usedSheetNames, reportData, design);
			}

			wb.write(out);
		}
		catch (Exception e) {
			throw new RenderingException("Unable to render results due to: " + e, e);
		}
	}
	
	/**
	 * Clone the sheet at the passed index and replace values as needed
	 */
	public HSSFSheet addSheet(HSSFWorkbook wb, SheetToAdd sheetToAdd, Set<String> usedSheetNames, ReportData reportData, ReportDesign design) {

		String prefix = getExpressionPrefix(design);
		String suffix = getExpressionSuffix(design);
		
		HSSFSheet sheet = sheetToAdd.getSheet();
		sheet.setForceFormulaRecalculation(true);
		
		int sheetIndex = wb.getSheetIndex(sheet);

		// Configure the sheet name, replacing any values as needed, and ensuring it is unique for the workbook
		String sheetName = EvaluationUtil.evaluateExpression(sheetToAdd.getOriginalSheetName(), sheetToAdd.getReplacementData(), prefix, suffix).toString();
		sheetName = ExcelUtil.formatSheetTitle(sheetName, usedSheetNames);
		wb.setSheetName(sheetIndex, sheetName);
		usedSheetNames.add(sheetName);
		
		log.debug("Handling sheet: " + sheetName + " at index " + sheetIndex);
		
		// Iterate across all of the rows in the sheet, and configure all those that need to be added/cloned
		List<RowToAdd> rowsToAdd = new ArrayList<RowToAdd>();
			
		int totalRows = sheet.getPhysicalNumberOfRows();
		int rowsFound = 0;
		for (int rowNum=0; rowsFound < totalRows; rowNum++) {
			HSSFRow currentRow = sheet.getRow(rowNum);
			log.debug("Handling row: " + ExcelUtil.formatRow(currentRow));
			if (currentRow != null) {
				rowsFound++;
			}
			// If we find that the row that we are on is a repeating row, then add the appropriate number of rows to clone
			String repeatingRowProperty = getRepeatingRowProperty(sheetToAdd.getOriginalSheetNum(), rowNum, design);
			if (repeatingRowProperty != null) {
				String[] dataSetSpanSplit = repeatingRowProperty.split(",");
				String dataSetName = dataSetSpanSplit[0];
				DataSet dataSet = reportData.getDataSets().get(dataSetName);
				int numRowsToRepeat = 1;
				if (dataSetSpanSplit.length == 2) {
					numRowsToRepeat = Integer.parseInt(dataSetSpanSplit[1]);
				}
				log.debug("Repeating this row with dataset: " + dataSet + " and repeat of " + numRowsToRepeat);
				int repeatNum=0;
				for (DataSetRow dataSetRow : dataSet) {
					for (int i=0; i<numRowsToRepeat; i++) {
						HSSFRow row = (i == 0 ? currentRow : sheet.getRow(rowNum+i));
						if (repeatNum == 0 && row != null && row != currentRow) {
							rowsFound++;
						}
						
						Map<String, Object> newReplacements = new HashMap<String, Object>(sheetToAdd.getReplacementData());
						newReplacements.putAll(getReplacementData(reportData, design, dataSetName, dataSetRow));
						
						// Add the repeated row to the list
						rowsToAdd.add(new RowToAdd(row, newReplacements));
						log.debug("Adding " + ExcelUtil.formatRow(row) + " with dataSetRow: " + dataSetRow);
					}
					repeatNum++;
				}
				rowNum += numRowsToRepeat;
			}
			else {
				rowsToAdd.add(new RowToAdd(currentRow, sheetToAdd.getReplacementData()));
				log.debug("Adding row: " + ExcelUtil.formatRow(currentRow));
			}
		}

		// Now, go through all of the collected rows, and add them back in
		for (int i=0; i<rowsToAdd.size(); i++) {
			RowToAdd rowToAdd = rowsToAdd.get(i);
			if (rowToAdd.getRowToClone() != null && rowToAdd.getRowToClone().cellIterator() != null) {
				HSSFRow addedRow = addRow(wb, sheetToAdd, rowToAdd, i, reportData, design);
				log.debug("Wrote row " + i + ": " + ExcelUtil.formatRow(addedRow));
			}
		}

		return sheet;
	}
	
	/**
	 * Adds in a Row to the given Sheet
	 */
	public HSSFRow addRow(HSSFWorkbook wb, SheetToAdd sheetToAdd, RowToAdd rowToAdd, int rowIndex, ReportData reportData, ReportDesign design) {
		
		// Create a new row and copy over style attributes from the row to add
		HSSFRow newRow = sheetToAdd.getSheet().createRow(rowIndex);
		HSSFRow rowToClone = rowToAdd.getRowToClone();
		HSSFCellStyle rowStyle = rowToClone.getRowStyle();
		if (rowStyle != null) {
			newRow.setRowStyle(rowStyle);
		}
		newRow.setHeight(rowToClone.getHeight());
		
		// Iterate across all of the cells in the row, and configure all those that need to be added/cloned
		List<CellToAdd> cellsToAdd = new ArrayList<CellToAdd>();

		int totalCells = rowToClone.getPhysicalNumberOfCells();
		int cellsFound = 0;
		for (int cellNum=0; cellsFound < totalCells; cellNum++) {
			HSSFCell currentCell = rowToClone.getCell(cellNum);
			log.debug("Handling cell: " + currentCell);
			if (currentCell != null) {
				cellsFound++;
			}
			// If we find that the cell that we are on is a repeating cell, then add the appropriate number of cells to clone
			String repeatingColumnProperty = getRepeatingColumnProperty(sheetToAdd.getOriginalSheetNum(), cellNum, design);
			if (repeatingColumnProperty != null) {
				String[] dataSetSpanSplit = repeatingColumnProperty.split(",");
				String dataSetName = dataSetSpanSplit[0];
				DataSet dataSet = reportData.getDataSets().get(dataSetName);
				int numCellsToRepeat = 1;
				if (dataSetSpanSplit.length == 2) {
					numCellsToRepeat = Integer.parseInt(dataSetSpanSplit[1]);
				}
				log.debug("Repeating this cell with dataset: " + dataSet + " and repeat of " + numCellsToRepeat);
				int repeatNum=0;
				for (DataSetRow dataSetRow : dataSet) {
					log.debug("In row: " + repeatNum + " of dataset");
					for (int i=0; i<numCellsToRepeat; i++) {
						HSSFCell cell = (i == 0 ? currentCell : rowToClone.getCell(cellNum+i));
						if (repeatNum == 0 && cell != null && cell != currentCell) {
							cellsFound++;
						}
						
						Map<String, Object> newReplacements = new HashMap<String, Object>(rowToAdd.getReplacementData());
						newReplacements.putAll(getReplacementData(reportData, design, dataSetName, dataSetRow));
						
						cellsToAdd.add(new CellToAdd(cell, newReplacements));
						log.debug("Adding " + cell + " with dataSetRow: " + dataSetRow);
					}
					repeatNum++;
				}
				cellNum += numCellsToRepeat;
			}
			else {
				cellsToAdd.add(new CellToAdd(currentCell, rowToAdd.getReplacementData()));
				log.debug("Adding " + currentCell);
			}
		}
		
		// Now, go through all of the collected cells, and add them back in
		
		ExcelStyleHelper styleHelper = new ExcelStyleHelper(wb);
		String prefix = getExpressionPrefix(design);
		String suffix = getExpressionSuffix(design);
		
		for (int i=0; i<cellsToAdd.size(); i++) {
			CellToAdd cellToAdd = cellsToAdd.get(i);
			HSSFCell newCell = newRow.createCell(i);
			HSSFCell cellToClone = cellToAdd.getCellToClone();
			if (cellToClone != null) {
		    	String contents = ExcelUtil.getCellContentsAsString(cellToClone);
		    	HSSFCellStyle style = cellToClone.getCellStyle();
		    	newCell.setCellStyle(style);
		    	if (ObjectUtil.notNull(contents)) {
		    		Object newContents = EvaluationUtil.evaluateExpression(contents, cellToAdd.getReplacementData(), prefix, suffix);
		    		ExcelUtil.setCellContents(styleHelper, newCell, newContents);
		    	}
			}
		}
		
		return newRow;
	}
	
	/**
	 * @return an Excel Workbook for the given argument
	 */
	protected HSSFWorkbook getExcelTemplate(ReportDesign design) throws IOException {
		InputStream is = null;
		try {
			ReportDesignResource r = getTemplate(design);
			is = new ByteArrayInputStream(r.getContents());
			POIFSFileSystem fs = new POIFSFileSystem(is);
			HSSFWorkbook wb = new HSSFWorkbook(fs);
			return wb;
		}
		finally {
			IOUtils.closeQuietly(is);
		}
	}
	
	/**
	 * @return if the sheet with the passed number (1-indexed) is repeating, returns the dataset name to use
	 * for example:  repeatSheet0=myIndicatorDataSet would indicate that sheet 0 should be repeated for each row in the dataset
	 */
	public String getRepeatingSheetProperty(int sheetNumber, ReportDesign design) {
		return design.getPropertyValue("repeatSheet" + (int)(sheetNumber+1), null);
	}
	
	/**
	 * @return if the row with the passed number (1-indexed) is repeating, returns the dataset name to use, optionally with a span
	 * for example:  repeatSheet0Row7=myPatientDataSet,2 would indicate that rows 7 and 8 in sheet 0 should be repeated for each row in the dataset
	 */
	public String getRepeatingRowProperty(int sheetNumber, int rowNumber, ReportDesign design) {
		return design.getPropertyValue("repeatSheet" + (int)(sheetNumber+1) + "Row" + (int)(rowNumber+1), null);
	}
	
	/**
	 * @return if the column with the passed number (1-indexed) is repeating, returns the dataset name to use, optionally with a span
	 * for example:  repeatSheet0Column5=myPatientDataSet,2 would indicate that columns 5 and 6 in sheet 0 should be repeated for each row in the dataset
	 */
	public String getRepeatingColumnProperty(int sheetNumber, int columnNumber, ReportDesign design) {
		return design.getPropertyValue("repeatSheet" + (int)(sheetNumber+1) + "Column" + (int)(columnNumber+1), null);
	}
	
	/**
	 * Inner class to encapsulate a sheet that should be rendered
	 */
	public class SheetToAdd {
		
		private HSSFSheet sheet;
		private Integer originalSheetNum;
		private String originalSheetName;
		private Map<String, Object> replacementData;
		
		/**
		 * Default Constructor
		 */
		public SheetToAdd(HSSFSheet sheet, Integer originalSheetNum, String originalSheetName, Map<String, Object> replacementData) {
			this.sheet = sheet;
			this.originalSheetNum = originalSheetNum;
			this.originalSheetName = originalSheetName;
			this.replacementData = replacementData;
		}
		
		/**
		 * @return the sheet
		 */
		public HSSFSheet getSheet() {
			return sheet;
		}
		/**
		 * @param sheet the sheet to set
		 */
		public void setSheet(HSSFSheet sheet) {
			this.sheet = sheet;
		}
		/**
		 * @return the originalSheetNum
		 */
		public Integer getOriginalSheetNum() {
			return originalSheetNum;
		}
		/**
		 * @param originalSheetNum the originalSheetNum to set
		 */
		public void setOriginalSheetNum(Integer originalSheetNum) {
			this.originalSheetNum = originalSheetNum;
		}
		/**
		 * @return the originalSheetName
		 */
		public String getOriginalSheetName() {
			return originalSheetName;
		}
		/**
		 * @param originalSheetName the originalSheetName to set
		 */
		public void setOriginalSheetName(String originalSheetName) {
			this.originalSheetName = originalSheetName;
		}
		/**
		 * @return the replacementData
		 */
		public Map<String, Object> getReplacementData() {
			return replacementData;
		}
		/**
		 * @param replacementData the replacementData to set
		 */
		public void setReplacementData(Map<String, Object> replacementData) {
			this.replacementData = replacementData;
		}
	}
	
	/**
	 * Inner class to encapsulate a row that should be rendered
	 */
	public class RowToAdd {
		
		private HSSFRow rowToClone;
		private Map<String, Object> replacementData;
		
		/**
		 * Default Constructor
		 */
		public RowToAdd(HSSFRow rowToClone, Map<String, Object> replacementData) {
			this.rowToClone = rowToClone;
			this.replacementData = replacementData;
		}

		/**
		 * @return the row
		 */
		public HSSFRow getRowToClone() {
			return rowToClone;
		}
		/**
		 * @param row the row to set
		 */
		public void setRowToClone(HSSFRow rowToClone) {
			this.rowToClone = rowToClone;
		}
		/**
		 * @return the replacementData
		 */
		public Map<String, Object> getReplacementData() {
			return replacementData;
		}
		/**
		 * @param replacementData the replacementData to set
		 */
		public void setReplacementData(Map<String, Object> replacementData) {
			this.replacementData = replacementData;
		}
	}
	
	/**
	 * Inner class to encapsulate a cell that should be cloned
	 */
	public class CellToAdd {
		
		private HSSFCell cellToClone;
		private Map<String, Object> replacementData;
		
		/**
		 * Default Constructor
		 */
		public CellToAdd(HSSFCell cellToClone, Map<String, Object> replacementData) {
			this.cellToClone = cellToClone;
			this.replacementData = replacementData;
		}

		/**
		 * @return the cellToClone
		 */
		public HSSFCell getCellToClone() {
			return cellToClone;
		}
		/**
		 * @param cellToClone the cellToClone to set
		 */
		public void setCellToClone(HSSFCell cellToClone) {
			this.cellToClone = cellToClone;
		}
		/**
		 * @return the replacementData
		 */
		public Map<String, Object> getReplacementData() {
			return replacementData;
		}
		/**
		 * @param replacementData the replacementData to set
		 */
		public void setReplacementData(Map<String, Object> replacementData) {
			this.replacementData = replacementData;
		}
	}
}
