package gov.nasa.jpf.symbc.numeric;

import java.util.ArrayList;
import java.util.List;

public class LinearOrIntegerConstraints extends Constraint{
	
	private List<LinearIntegerConstraint> list;
	
	public LinearOrIntegerConstraints () {
		super (null, null, null);
		list = new ArrayList<LinearIntegerConstraint>();
	}
	
	public void addToList (LinearIntegerConstraint lic) {
		if (!list.contains(lic)) list.add(lic);
	}

	public List<LinearIntegerConstraint> getList () {
		return list;
	}
	
	@Override
	public Constraint not() {
		throw new UnsupportedOperationException("Not supported");
		//return null;
	}
	
	public String toString () {
		StringBuilder sb = new StringBuilder();
		if (list.size() == 1) {
			return list.get(0).toString();
		}
		else {
			sb.append (list.get(0).toString());
		}
		for (int i = 1; i < list.size(); i++) {
			sb.append (" OR ");
			sb.append (list.get(i).toString());
		}
		if (and != null) {
			sb.append (" && \n");
			sb.append (and.stringPC());
		}
		return sb.toString();
	}
	
	public String stringPC () {
		return this.toString();
	}
	
	public boolean equals (Object o) {
		if (!(o instanceof LinearOrIntegerConstraints))
			return false;
		LinearOrIntegerConstraints other = (LinearOrIntegerConstraints) o;
		if (this.list.size() != other.list.size()) {
			return false;
		}
		for (int i = 0; i < this.list.size(); i++) {
			if (!this.list.get(i).equals(other.list.get(i))) {
				return false;
			}
		}
		return true;
	}
}
