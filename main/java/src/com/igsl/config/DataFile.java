package com.igsl.config;

public enum DataFile {
	USER_MAP("User", "Map"),
	USER_CLOUD("User", "Cloud"),
	USER_DATACENTER("User", "DataCenter"),
	
	FIELD_MAP("Field", "Map"),
	FIELD_CLOUD("Field", "Cloud"),
	FIELD_DATACENTER("Field", "DataCenter"),

	PROJECT_MAP("Project", "Map"),
	PROJECT_CLOUD("Project", "Cloud"),
	PROJECT_DATACENTER("Project", "DataCenter"),
	
	GROUP_MAP("Group", "Map"),
	GROUP_CLOUD("Group", "Cloud"),
	GROUP_DATACENTER("Group", "DataCenter"),
	
	ROLE_MAP("Role", "Map"),
	ROLE_CLOUD("Role", "Cloud"),
	ROLE_DATACENTER("Role", "DataCenter"),
	
	FILTER_DATA("Filter", "Data"),
	FILTER_MIGRATED("Filter", "Migrated"),
	
	DASHBOARD_DATA("Dashboard", "Data"),
	DASHBOARD_MIGRATED("Dashboard", "Migrated");
	
	private static final String EXTENSION = ".json";
	private String value;
	private DataFile(String category, String type) {
		value = category + "." + type + EXTENSION;
	}
	@Override
	public String toString() {
		return value;
	}
}
