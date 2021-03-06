//
// Copyright (C) 2007 United States Government as represented by the
// Administrator of the National Aeronautics and Space Administration
// (NASA).  All Rights Reserved.
// 
// This software is distributed under the NASA Open Source Agreement
// (NOSA), version 1.3.  The NOSA has been approved by the Open Source
// Initiative.  See the file NOSA-1.3-JPF at the top of the distribution
// directory tree for the complete NOSA document.
// 
// THE SUBJECT SOFTWARE IS PROVIDED "AS IS" WITHOUT ANY WARRANTY OF ANY
// KIND, EITHER EXPRESSED, IMPLIED, OR STATUTORY, INCLUDING, BUT NOT
// LIMITED TO, ANY WARRANTY THAT THE SUBJECT SOFTWARE WILL CONFORM TO
// SPECIFICATIONS, ANY IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR
// A PARTICULAR PURPOSE, OR FREEDOM FROM INFRINGEMENT, ANY WARRANTY THAT
// THE SUBJECT SOFTWARE WILL BE ERROR FREE, OR ANY WARRANTY THAT
// DOCUMENTATION, IF PROVIDED, WILL CONFORM TO THE SUBJECT SOFTWARE.
//
package gov.nasa.jpf.symbc.numeric;

import gov.nasa.jpf.jvm.IntChoiceGenerator;
import gov.nasa.jpf.jvm.choice.IntIntervalGenerator;

public class PCChoiceGenerator extends IntIntervalGenerator {

	PathCondition[] PC;
	boolean isReverseOrder;
	
	private static int n;
	
	public PCChoiceGenerator(int size) {
		super("" + n++, 0, size - 1);
		PC = new PathCondition[size];
		isReverseOrder = false;
		
		//assert size < 2 : "kkkk";
	}
	
	/*
	 * If reverseOrder is true, the PCChoiceGenerator
	 * explores paths in the opposite order used by
	 * the default constructor. If reverseOrder is false
	 * the usual behavior is used.  
	 */
	public PCChoiceGenerator(int size, boolean reverseOrder) {
		super("" + n++, size - 1, reverseOrder ? -1 : 1);
		PC = new PathCondition[size];
		isReverseOrder = reverseOrder;
	}
	
	public boolean isReverseOrder() {
		return isReverseOrder;
	}

	// sets the PC constraints for the current choice
	public void setCurrentPC(PathCondition pc) {
		System.err.println("Set PC: before setting: " + PC[getNextChoice()]);
		PC[getNextChoice()] = pc;
		System.err.println("Set PC: after setting: " + PC[getNextChoice()]);
	}
	
	// returns the PC constraints for the current choice
	public PathCondition getCurrentPC() {
		PathCondition pc;
		
		pc = PC[getNextChoice()];
		if (pc != null) {
			return pc.make_copy();
		} else {
			return null;
		}
	}
	
	public PathCondition getPreviousPC() {
		PathCondition pc;
		
		pc = getNextChoice() - 1 >= 0 ? PC[getNextChoice() - 1] : null;
		if (pc != null) {
			return pc.make_copy();
		} else {
			return null;
		}
	}
	
	public PathCondition getNextPC() {
		PathCondition pc;
		
		pc = getNextChoice() + 1 < max ? PC[getNextChoice() + 1] : null;
		if (pc != null) {
			return pc.make_copy();
		} else {
			return null;
		}
	}
	
	public IntChoiceGenerator randomize() {
		return new PCChoiceGenerator(PC.length, random.nextBoolean()); 
	}
	
	public void setNextChoice(int nextChoice){
		super.next = nextChoice;
	}

	public void advance () {
		super.advance();
		try {
			throw new Exception("CG advance: " + this.toString());
		} catch(Exception ex) {
			ex.printStackTrace();
		}
	}

	public String toString () {
		String s = super.toString();
		for (int i = 0; i <= this.max; i++) {
			s += "[" + PC[i] + "]";
		}
		return s;
	}
}
