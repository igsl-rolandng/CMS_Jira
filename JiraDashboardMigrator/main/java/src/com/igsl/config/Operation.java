package com.igsl.config;

public enum Operation {
	DUMP("dump"),
	DUMP_FILTER("dumpFilter"),
	CREATE_FILTER("createFilter"),
	DELETE_FILTER("deleteFilter"),
	LIST_FILTER("listFilter"),
	DUMP_DASHBOARD("dumpDashboard"),
	CREATE_DASHBOARD("createDashboard"),
	DELETE_DASHBOARD("deleteDashboard"),
	LIST_DASHBOARD("listDashboard");
	private String value;
	private Operation(String s) {
		this.value = s;
	}
	public static Operation parse(String s) {
		for (Operation o : Operation.values()) {
			if (o.value.equals(s)) {
				return o;
			}
		}
		return null;
	}
	@Override
	public String toString() {
		return value;
	}
}
