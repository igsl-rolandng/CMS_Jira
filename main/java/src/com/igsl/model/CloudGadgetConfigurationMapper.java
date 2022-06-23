package com.igsl.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.igsl.model.mapping.Mapping;
import com.igsl.model.mapping.MappingType;

public class CloudGadgetConfigurationMapper {
	
	public static class ConfigData {
		String pattern;
		MappingType type;
		public ConfigData(String pattern, MappingType type) {
			this.pattern = pattern;
			this.type = type;
		}
		public String getPattern() {
			return pattern;
		}
		public MappingType getType() {
			return type;
		}
	}
	
	public enum Config {
		FILTER_ID_WITH_PREFIX("filterId", new ConfigData("filterId-([0-9]+)", MappingType.FILTER)),
		Y_STAT_TYPE("ystattype", new ConfigData("(customfield_[0-9]+)", MappingType.CUSTOM_FIELD)),
		X_STAT_TYPE("xstattype", new ConfigData("(customfield_[0-9]+)", MappingType.CUSTOM_FIELD)),
		FILTER_ID("filterId", new ConfigData("([0-9]+)", MappingType.FILTER)),
		COLUMN_NAMES("columnNames", true, new ConfigData("(customfield_[0-9]+)", MappingType.CUSTOM_FIELD)),
		SORT_BY("sortBy", false, new ConfigData("(customfield_[0-9]+)", MappingType.CUSTOM_FIELD)),
		STAT_TYPE("statType", new ConfigData("(customfield_[0-9]+)", MappingType.CUSTOM_FIELD)),
		PROJECT_OR_FILTER_ID_PROJECT("projectOrFilterId", new ConfigData("projectId-([0-9]+)", MappingType.PROJECT)),
		PROJECT_OR_FILTER_ID_FILTER("projectOrFilterId", new ConfigData("filterId-([0-9]+)", MappingType.FILTER)),
		ID_FILTER_ID("id", new ConfigData("([0-9]+)", MappingType.FILTER)),
		SORT_COLUMN("sortColumn", false, new ConfigData("(customfield_[0-9]+)", MappingType.CUSTOM_FIELD));
		private String key;
		private List<ConfigData> data = new ArrayList<>();
		private boolean fullString = true;
		private Config(String key, ConfigData... data) {
			this.key = key;
			if (data != null) {
				this.data.addAll(Arrays.asList(data));
			}
		}
		private Config(String key, boolean multiple, ConfigData... data) {
			this.key = key;
			this.fullString = multiple;
			if (data != null) {
				this.data.addAll(Arrays.asList(data));
			}
		}
		public String getKey() {
			return key;
		}
		public List<ConfigData> getData() {
			return data;
		}
		public boolean isFullString() {
			return fullString;
		}
	}

	public enum GadgetType {
		TWO_DIMENSIONAL_STATS(	
				null, "rest/gadgets/1.0/g/com.atlassian.jira.gadgets:two-dimensional-stats-gadget/gadgets/two-dimensional-stats-gadget.xml", 
				Config.FILTER_ID_WITH_PREFIX, Config.Y_STAT_TYPE, Config.X_STAT_TYPE, Config.SORT_BY),
		FILTER_RESULTS(
				null, "rest/gadgets/1.0/g/com.atlassian.jira.gadgets:filter-results-gadget/gadgets/filter-results-gadget.xml",
				Config.FILTER_ID, Config.COLUMN_NAMES, Config.SORT_BY),
		PIE_CHART(
				null, "rest/gadgets/1.0/g/com.atlassian.jira.gadgets:pie-chart-gadget/gadgets/piechart-gadget.xml",
				Config.STAT_TYPE, Config.PROJECT_OR_FILTER_ID_FILTER, Config.PROJECT_OR_FILTER_ID_PROJECT, Config.ID_FILTER_ID),
		RECENTLY_CREATED_CHART(
				null, "rest/gadgets/1.0/g/com.atlassian.jira.gadgets:recently-created-chart-gadget/gadgets/recently-created-gadget.xml",
				Config.PROJECT_OR_FILTER_ID_FILTER, Config.PROJECT_OR_FILTER_ID_PROJECT),
		STATS(
				null, "rest/gadgets/1.0/g/com.atlassian.jira.gadgets:stats-gadget/gadgets/stats-gadget.xml",
				Config.STAT_TYPE, Config.PROJECT_OR_FILTER_ID_FILTER, Config.PROJECT_OR_FILTER_ID_PROJECT, Config.SORT_BY, Config.ID_FILTER_ID),
		ASSIGNED_TO_ME(
				null, "rest/gadgets/1.0/g/com.atlassian.jira.gadgets:assigned-to-me-gadget/gadgets/assigned-to-me-gadget.xml",
				Config.COLUMN_NAMES, Config.SORT_COLUMN, Config.SORT_BY);		
		private static Comparator<String> nullFirstComparator = Comparator.nullsFirst(String::compareTo);
		private String moduleKey;
		private String uri;
		private List<Config> config = new ArrayList<>();
		private GadgetType(String moduleKey, String uri, Config... config) {
			this.moduleKey = moduleKey;
			this.uri = uri;
			if (config != null) {
				this.config.addAll(Arrays.asList(config));
			}
		}
		public static GadgetType parse(String moduleKey, String uri) {
			for (GadgetType type : GadgetType.values()) {
				if (nullFirstComparator.compare(moduleKey, type.moduleKey) == 0 &&
					nullFirstComparator.compare(uri, type.uri) == 0) {
					return type;
				}
			}
			return null;
		}
		public Config getConfig(String key) {
			for (Config conf : this.getConfig()) {
				if (conf.getKey().equals(key)) {
					return conf;
				}
			}
			return null;
		}
		public String getModuleKey() {
			return moduleKey;
		}
		public String getUri() {
			return uri;
		}
		public List<Config> getConfig() {
			return config;
		}
	}
	
	public static void mapConfiguration(DataCenterPortletConfiguration gadget, Mapping project, Mapping role, Mapping field, Mapping group, Mapping user, Mapping filter) {
		GadgetType type = GadgetType.parse(gadget.getDashboardCompleteKey(), gadget.getGadgetXml());
		if (type != null) {
			for (DataCenterGadgetConfiguration item : gadget.getGadgetConfigurations()) {
				Config conf = type.getConfig(item.getUserPrefKey());
				if (conf != null) {
					for (ConfigData data : conf.getData()) {
						Mapping map = null;
						switch (data.getType()) {
						case CUSTOM_FIELD:
							map = field;
							break;
						case DASHBOARD:
							break;
						case FILTER:
							map = filter;
							break;
						case GROUP:
							map = group;
							break;
						case PROJECT:
							map = project;
							break;
						case ROLE:
							map = role;
							break;
						case USER:
							map = user;
							break;
						}
						Pattern p = Pattern.compile(data.getPattern());
						Matcher m = p.matcher(item.getUserPrefValue());
						if (conf.isFullString()) {
							// Multiple find and replace
							
						} else {
							// Check whole string
							if (m.matches()) {
								// Replace value from map
								String oldValue = m.group(1);
								if (map.getMapped().containsKey(oldValue)) {
									String newValue = map.getMapped().get(oldValue);
									item.setUserPrefValue(newValue);
								} else {
									System.out.println("Failed to map gadget " + gadget.getId() + " configuration " + item.getUserPrefKey() + " value " + item.getUserPrefValue());
								}
							}
						}
					}
				}
			}
		}
	}
}
