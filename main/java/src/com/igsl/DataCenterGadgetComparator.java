package com.igsl;

import java.util.Comparator;

import com.igsl.model.DataCenterPortletConfiguration;

public class DataCenterGadgetComparator implements Comparator<DataCenterPortletConfiguration> {

	private static Comparator<Integer> integerComparator = Comparator.naturalOrder();
	
	@Override
	public int compare(DataCenterPortletConfiguration o1, DataCenterPortletConfiguration o2) {
		if (o1 != null && o2 == null) {
			return 1;
		}
		if (o1 == null && o2 != null) {
			return -1;
		}
		if (o1.getPositionSeq() != o2.getPositionSeq()) {
			return integerComparator.compare(o1.getPositionSeq(), o2.getPositionSeq());
		}
		return integerComparator.compare(o1.getColumnNumber(), o2.getColumnNumber());
	}

}
