package com.igsl.model.mapping;

import java.util.HashMap;
import java.util.Map;

public class Migrated {
	private MappingType type;
	private Map<String, String> migrated = new HashMap<>();
	private Map<String, String> failed = new HashMap<>();
	public Migrated() {}
	public Migrated(MappingType type) {
		this.type = type;
	}
	public Map<String, String> getMigrated() {
		return migrated;
	}
	public void setMigrated(Map<String, String> migrated) {
		this.migrated = migrated;
	}
	public Map<String, String> getFailed() {
		return failed;
	}
	public void setFailed(Map<String, String> failed) {
		this.failed = failed;
	}	
}
