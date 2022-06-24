package com.igsl;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.glassfish.jersey.logging.LoggingFeature;
import org.glassfish.jersey.logging.LoggingFeature.Verbosity;
import org.postgresql.Driver;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.igsl.config.Config;
import com.igsl.config.DataFile;
import com.igsl.config.Operation;
import com.igsl.model.CloudDashboard;
import com.igsl.model.CloudFilter;
import com.igsl.model.CloudGadget;
import com.igsl.model.CloudGadgetConfiguration;
import com.igsl.model.CloudGadgetConfigurationMapper;
import com.igsl.model.DataCenterFilter;
import com.igsl.model.DataCenterPermission;
import com.igsl.model.DataCenterPortalPage;
import com.igsl.model.DataCenterPortletConfiguration;
import com.igsl.model.PermissionTarget;
import com.igsl.model.PermissionType;
import com.igsl.model.mapping.CustomField;
import com.igsl.model.mapping.Group;
import com.igsl.model.mapping.GroupPickerResult;
import com.igsl.model.mapping.Mapping;
import com.igsl.model.mapping.MappingType;
import com.igsl.model.mapping.Project;
import com.igsl.model.mapping.Role;
import com.igsl.model.mapping.SearchResult;
import com.igsl.model.mapping.User;
import com.igsl.mybatis.FilterMapper;

/**
 * Migrate dashboard and filter from Jira Data Center 8.14.1 to Jira Cloud.
 * The official Jira Cloud Migration Assistant does not migrate dashboards and filters.
 * This tool will:
 * - Read dashboard and filter data from Jira 8.14.1 database (to get a list of them)
 * - Extract information on dashboard and filter using 8.14.1 REST API
 * - Recreate filters on Cloud using REST API
 * - Recreate dashboard on Cloud using REST API
 * @author kcwong
 */
public class DashboardMigrator {
	
	private static final String NEWLINE = System.getProperty("line.separator");
	private static Logger LOGGER; 
	
	static {
		// We must update system property before ever using Log4j2 Logger or LogManager
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
		LOGGER = LogManager.getLogger();
	}
	
	private static final JacksonJsonProvider JACKSON_JSON_PROVIDER = new JacksonJaxbJsonProvider()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.configure(SerializationFeature.INDENT_OUTPUT, true);
	
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	
	private static SqlSessionFactory setupMyBatis(Config conf) throws Exception {
		PooledDataSource ds = new PooledDataSource();
        ds.setDriver(Driver.class.getCanonicalName());
        ds.setUrl(conf.getSourceDatabaseURL());
        ds.setUsername(conf.getSourceDatabaseUser());
        ds.setPassword(conf.getSourceDatabasePassword());
		TransactionFactory transactionFactory = new JdbcTransactionFactory();
		Environment environment = new Environment("development", transactionFactory, ds);
		Configuration configuration = new Configuration(environment);
		configuration.addMapper(FilterMapper.class);
		return new SqlSessionFactoryBuilder().build(configuration);
	}
	
	/**
	 * Invoke REST API
	 * @param client Jersey2 client
	 * @param path URI
	 * @param method Method as string
	 * @param acceptedTypes String[]
	 * @param headers MultivaluedMap<String, Object>
	 * @param queryParameters Map<String, List<Object>>
	 * @param dataType String, valid for POST and PUT
	 * @param data Object, valid for POST and PUT
	 * @return Response
	 * @throws Exception
	 */
	private static Response restCall(Client client, URI path, String method, 
			MultivaluedMap<String, Object> headers, Map<String, Object> queryParameters, Object data) throws Exception {
		WebTarget target = client.target(path);
		if (queryParameters != null) {
			for (Map.Entry<String, Object> entry : queryParameters.entrySet()) {
				if (entry.getValue() instanceof Collection) {
					Collection<?> list = (Collection<?>) entry.getValue();
					target = target.queryParam(entry.getKey(), list.toArray());
				} else if (entry.getValue().getClass().isArray()) {
					Object[] list = (Object[]) entry.getValue();
					target = target.queryParam(entry.getKey(), list);
				} else {
					target = target.queryParam(entry.getKey(), entry.getValue());
				}
			}
		}
		Builder builder = target.request(MediaType.APPLICATION_JSON);
		if (headers != null) {
			builder = builder.headers(headers);
		}
		if (HttpMethod.DELETE.equals(method)) {
			return builder.delete();
		} else if (HttpMethod.GET.equals(method)) {
			return builder.get();
		} else if (HttpMethod.HEAD.equals(method)) {
			return builder.head();
		} else if (HttpMethod.OPTIONS.equals(method)) {
			return builder.options();
		} else if (HttpMethod.POST.equals(method)) {
			return builder.post(Entity.entity(data, MediaType.APPLICATION_JSON));
		} else if (HttpMethod.PUT.equals(method)) {
			return builder.put(Entity.entity(data, MediaType.APPLICATION_JSON));
		} else {
			return builder.method(method, Entity.entity(data, MediaType.APPLICATION_JSON));
		}
	}
	
	private static boolean checkStatusCode(Response resp, Response.Status check) {
		if ((resp.getStatus() & check.getStatusCode()) == check.getStatusCode()) {
			return true;
		}
		return false;
	}
	
	private static List<Project> getCloudProjects(Client client, Config conf) throws Exception {
		List<Project> result = new ArrayList<>();
		int startAt = 0;
		int maxResults = 50;
		boolean isLast = false;
		do {
			Map<String, Object> queryParameters = new HashMap<>();
			queryParameters.put("startAt", startAt);
			queryParameters.put("maxResults", maxResults);
			Response resp = restCall(client, new URI(conf.getTargetRESTBaseURL()).resolve("rest/api/latest/project/search"), HttpMethod.GET, null, queryParameters, null);
			if (checkStatusCode(resp, Response.Status.OK)) {
				SearchResult<Project> searchResult = resp.readEntity(new GenericType<SearchResult<Project>>() {});
				isLast = searchResult.getIsLast();
				result.addAll(searchResult.getValues());
				startAt += searchResult.getMaxResults();
			} else {
				throw new Exception(resp.readEntity(String.class));
			}
		} while (!isLast);
		return result;
	}
	
	private static List<Project> getServerProjects(Client client, Config conf) throws Exception {
		List<Project> result = new ArrayList<>();
		Response resp = restCall(client, new URI(conf.getSourceRESTBaseURL()).resolve("rest/api/latest/project"), HttpMethod.GET, null, null, null);
		if (checkStatusCode(resp, Response.Status.OK)) {
			List<Project> searchResult = resp.readEntity(new GenericType<List<Project>>() {});
			result.addAll(searchResult);
		} else {
			throw new Exception(resp.readEntity(String.class));
		}
		return result;
	}
	
	private static List<CustomField> getCustomFields(Client client, String baseURL) throws Exception {
		List<CustomField> result = new ArrayList<>();
		Response resp = restCall(client, new URI(baseURL).resolve("rest/api/latest/field"), HttpMethod.GET, null, null, null);
		if (checkStatusCode(resp, Response.Status.OK)) {
			List<CustomField> searchResult = resp.readEntity(new GenericType<List<CustomField>>() {});
			for (CustomField cf : searchResult) {
				if (cf.isCustom()) {
					result.add(cf);
				}
			}
		} else {
			throw new Exception(resp.readEntity(String.class));
		}
		return result;
	}
	
	private static List<User> getServerUsers(Client client, Config conf) throws Exception {
		List<User> result = new ArrayList<>();
		int startAt = 0;
		do {
			Map<String, Object> queryParameters = new HashMap<>();
			queryParameters.put("username", ".");
			queryParameters.put("startAt", startAt);
			queryParameters.put("includeActive", true);
			queryParameters.put("includeInactive", true);
			Response resp = restCall(client, new URI(conf.getSourceRESTBaseURL()).resolve("rest/api/latest/user/search"), HttpMethod.GET, null, queryParameters, null);
			if (checkStatusCode(resp, Response.Status.OK)) {
				List<User> searchResult = resp.readEntity(new GenericType<List<User>>() {});
				if (searchResult.size() != 0) {
					result.addAll(searchResult);
					startAt += searchResult.size();
				} else {
					break;
				}
			} else {
				throw new Exception(resp.readEntity(String.class));
			}
		} while (true);
		return result;
	}
	
	private static List<User> getCloudUsers(Client client, Config conf) throws Exception {
		List<User> result = new ArrayList<>();
		int startAt = 0;
		do {
			Map<String, Object> queryParameters = new HashMap<>();
			queryParameters.put("query", ".");
			queryParameters.put("startAt", startAt);
			queryParameters.put("includeActive", true);
			queryParameters.put("includeInactive", true);
			Response resp = restCall(client, new URI(conf.getTargetRESTBaseURL()).resolve("rest/api/latest/users/search"), HttpMethod.GET, null, queryParameters, null);
			if (checkStatusCode(resp, Response.Status.OK)) {
				List<User> searchResult = resp.readEntity(new GenericType<List<User>>() {});
				if (searchResult.size() != 0) {
					result.addAll(searchResult);
					startAt += searchResult.size();
				} else {
					break;
				}
			} else {
				throw new Exception(resp.readEntity(String.class));
			}
		} while (true);
		return result;
	}
	
	private static List<Role> getRoles(Client client, String baseURL) throws Exception {
		List<Role> result = new ArrayList<>();
		Response resp = restCall(client, new URI(baseURL).resolve("rest/api/latest/role"), HttpMethod.GET, null, null, null);
		if (checkStatusCode(resp, Response.Status.OK)) {
			List<Role> searchResult = resp.readEntity(new GenericType<List<Role>>() {});
			result.addAll(searchResult);
		} else {
			throw new Exception(resp.readEntity(String.class));
		}
		return result;
	}
	
	private static List<Group> getGroups(Client client, String baseURL) throws Exception {
		List<Group> result = new ArrayList<>();
		Response resp = restCall(client, new URI(baseURL).resolve("rest/api/latest/groups/picker"), HttpMethod.GET, null, null, null);
		if (checkStatusCode(resp, Response.Status.OK)) {
			GroupPickerResult searchResult = resp.readEntity(GroupPickerResult.class);
			result.addAll(searchResult.getGroups());
		} else {
			throw new Exception(resp.readEntity(String.class));
		}
		return result;
	}
	
	private static Config parseConfig(String[] args) {
		Config result = null;
		if (args.length == 2) {
			try (FileReader fr = new FileReader(args[0])) {
				result = new Gson().fromJson(fr, Config.class);
			} catch (IOException ioex) {
				ioex.printStackTrace();
			} catch (JsonIOException | JsonSyntaxException jex) {
				jex.printStackTrace();
			}
			Operation o = Operation.parse(args[1]);
			result.setOperation(o);
		}
		return result;
	}

	private static void printHelp() {
		System.out.println("java -jar JiraDashboardMigrator.jar com.igsl.DashboardMigrator <Config File> <Operation>");
		System.out.println();
		System.out.println("Config file content: ");
		System.out.println(new Gson().toJson(new Config()));
		System.out.println();
		System.out.println("Operation: ");
		for (Operation o : Operation.values()) {
			System.out.println(o.toString());
		}
	}

	private static <T> T readFile(DataFile file, Class<? extends T> cls) throws IOException, JsonParseException {
		StringBuilder sb = new StringBuilder();
		for (String line : Files.readAllLines(Paths.get(file.toString()))) {
			sb.append(line).append(NEWLINE);
		}
		return GSON.fromJson(sb.toString(), cls);
	}
	
	private static <T> T readFile(DataFile file, GenericType<T> t) throws IOException, JsonParseException {
		StringBuilder sb = new StringBuilder();
		for (String line : Files.readAllLines(Paths.get(file.toString()))) {
			sb.append(line).append(NEWLINE);
		}
		return GSON.fromJson(sb.toString(), t.getType());
	}
	
	private static void saveFile(DataFile file, Object content) throws IOException {
		try (FileWriter fw = new FileWriter(file.toString())) {
			fw.write(GSON.toJson(content));
		}
	}
	
	private static void dumpData(FilterMapper filterMapper, Client cloudClient, Client dataCenterClient, Config conf) throws Exception {
		LOGGER.info("Dumping data from Data Center and Cloud...");
		// Project mapping
		LOGGER.info("Processing Projects...");
		int mappedProjectCount = 0;
		List<Project> cloudProjects = getCloudProjects(cloudClient, conf);
		saveFile(DataFile.PROJECT_CLOUD, cloudProjects);
		List<Project> serverProjects = getServerProjects(dataCenterClient, conf);
		saveFile(DataFile.PROJECT_DATACENTER, serverProjects);
		Mapping projectMapping = new Mapping(MappingType.PROJECT);
		for (Project src : serverProjects) {
			List<String> targets = new ArrayList<>();
			for (Project target: cloudProjects) {
				if (target.getKey().equals(src.getKey()) && 
					target.getProjectTypeKey().equals(src.getProjectTypeKey()) && 
					target.getName().equals(src.getName())) {
					targets.add(Integer.toString(target.getId()));
				}	
			}
			switch (targets.size()) {
			case 0:
				projectMapping.getUnmapped().add(src);
				LOGGER.error("\tProject " + src.getName() + " is not mapped");
				break;
			case 1:
				projectMapping.getMapped().put(Integer.toString(src.getId()), targets.get(0));
				mappedProjectCount++;
				break;
			default:
				projectMapping.getConflict().put(Integer.toString(src.getId()), targets);
				LOGGER.error("\tProject " + src.getName() + " is mapped to multiple Cloud projects");
				break;
			}
		}
		LOGGER.info("Projects mapped: " + mappedProjectCount + "/" + serverProjects.size());
		saveFile(DataFile.PROJECT_MAP, projectMapping);
		// User mapping
		int mappedUserCount = 0;
		LOGGER.info("Processing Users...");
		List<User> cloudUsers = getCloudUsers(cloudClient, conf);
		saveFile(DataFile.USER_CLOUD, cloudUsers);
		List<User> serverUsers = getServerUsers(dataCenterClient, conf);
		saveFile(DataFile.USER_DATACENTER, serverUsers);
		Mapping userMapping = new Mapping(MappingType.USER);
		Comparator<String> nullFirstCompare = Comparator.nullsFirst(String::compareTo);
		for (User src : serverUsers) {
			List<String> targets = new ArrayList<>();
			for (User target: cloudUsers) {
				// Migrated user names got changed... compare case-insensitively, remove all space, check both name and display name against Cloud display name
				// Email should be the best condition, but cannot be retrieved from REST API unless approved by Atlassian
				String srcDisplayName = src.getDisplayName().toLowerCase().replaceAll("\\s", "");
				String srcName = src.getName().toLowerCase().replaceAll("\\s", "");
				String targetDisplayName = target.getDisplayName().toLowerCase().replaceAll("\\s", "");	
				if (nullFirstCompare.compare(srcDisplayName, targetDisplayName) == 0 || 
					nullFirstCompare.compare(srcName, targetDisplayName) == 0) {
					targets.add(target.getAccountId());
				}
			}
			switch (targets.size()) {
			case 0:
				userMapping.getUnmapped().add(src);
				LOGGER.error("\tUser " + src.getName() + " is not mapped");
				break;
			case 1:
				userMapping.getMapped().put(src.getName(), targets.get(0));
				mappedUserCount++;
				break;
			default:
				userMapping.getConflict().put(src.getName(), targets);
				LOGGER.error("\tUser " + src.getName() + " is mapped to multiple Cloud users");
				break;
			}
		}
		LOGGER.info("Users mapped: " + mappedUserCount + "/" + serverUsers.size());
		saveFile(DataFile.USER_MAP, userMapping);
		// Custom field mapping
		int mappedFieldCount = 0;
		LOGGER.info("Processing Custom Fields...");
		List<CustomField> cloudFields = getCustomFields(cloudClient, conf.getTargetRESTBaseURL());
		saveFile(DataFile.FIELD_CLOUD, cloudFields);
		List<CustomField> serverFields = getCustomFields(dataCenterClient, conf.getSourceRESTBaseURL());
		saveFile(DataFile.FIELD_DATACENTER, serverFields);
		Mapping fieldMapping = new Mapping(MappingType.CUSTOM_FIELD);
		for (CustomField src : serverFields) {
			List<String> targets = new ArrayList<>();
			List<String> migratedTargets = new ArrayList<>();
			for (CustomField target: cloudFields) {
				if (target.getSchema().compareTo(src.getSchema()) == 0) {
					if (target.getName().equals(src.getName())) {
						targets.add(target.getId());
					}
				}
				if (target.getName().equals(src.getName() + " (migrated)")) {
					migratedTargets.add(target.getId());
				}
			}
			switch (migratedTargets.size()) {
			case 1:
				fieldMapping.getMapped().put(src.getId(), migratedTargets.get(0));
				mappedFieldCount++;
				break;
			case 0:
				// Fallback to targets
				switch (targets.size()) {
				case 0:
					fieldMapping.getUnmapped().add(src);
					LOGGER.error("\tCustom Field " + src.getName() + " is not mapped");
					break;
				case 1:
					fieldMapping.getMapped().put(src.getId(), targets.get(0));
					mappedFieldCount++;
					break;
				default:
					fieldMapping.getConflict().put(src.getId(), targets);
					LOGGER.error("\tCustom Field " + src.getName() + " is mapped to multiple Cloud fields");
					break;
				}
				break;
			default: 
				List<String> list = new ArrayList<>();
				list.addAll(migratedTargets);
				list.addAll(targets);
				fieldMapping.getConflict().put(src.getId(), list);
				LOGGER.error("\tCustom Field " + src.getName() + " is mapped to multiple Cloud fields");
				break;
			}
		}
		LOGGER.info("Custom Fields mapped: " + mappedFieldCount + "/" + serverFields.size());
		saveFile(DataFile.FIELD_MAP, fieldMapping);		
		LOGGER.info("Data dump completed");
		// Role mapping
		LOGGER.info("Processing Roles...");
		int mappedRoleCount = 0;
		List<Role> cloudRoles = getRoles(cloudClient, conf.getTargetRESTBaseURL());
		saveFile(DataFile.ROLE_CLOUD, cloudRoles);
		List<Role> serverRoles = getRoles(dataCenterClient, conf.getSourceRESTBaseURL());
		saveFile(DataFile.ROLE_DATACENTER, serverRoles);
		Mapping roleMapping = new Mapping(MappingType.ROLE);
		for (Role src : serverRoles) {
			List<String> targets = new ArrayList<>();
			for (Role target: cloudRoles) {
				if (target.getDescription().equals(src.getDescription()) && 
					target.getName().equals(src.getName())) {
					targets.add(Integer.toString(target.getId()));
				}	
			}
			switch (targets.size()) {
			case 0:
				roleMapping.getUnmapped().add(src);
				LOGGER.error("\tRole " + src.getName() + " is not mapped");
				break;
			case 1:
				roleMapping.getMapped().put(Integer.toString(src.getId()), targets.get(0));
				mappedRoleCount++;
				break;
			default:
				roleMapping.getConflict().put(Integer.toString(src.getId()), targets);
				LOGGER.error("\tRole " + src.getName() + " is mapped to multiple Cloud roles");
				break;
			}
		}
		LOGGER.info("Roles mapped: " + mappedRoleCount + "/" + serverRoles.size());
		saveFile(DataFile.ROLE_MAP, roleMapping);
		// Group mapping
		LOGGER.info("Processing Groups...");
		int mappedGroupCount = 0;
		List<Group> cloudGroups = getGroups(cloudClient, conf.getTargetRESTBaseURL());
		saveFile(DataFile.GROUP_CLOUD, cloudGroups);
		List<Group> serverGroups = getGroups(dataCenterClient, conf.getSourceRESTBaseURL());
		saveFile(DataFile.GROUP_DATACENTER, serverGroups);
		Mapping groupMapping = new Mapping(MappingType.GROUP);
		for (Group src : serverGroups) {
			List<String> targets = new ArrayList<>();
			for (Group target: cloudGroups) {
				if (target.getHtml().equals(src.getHtml()) && 
					target.getName().equals(src.getName())) {
					targets.add(target.getGroupId());
				}	
			}
			switch (targets.size()) {
			case 0:
				groupMapping.getUnmapped().add(src);
				LOGGER.error("\tGroup " + src.getName() + " is not mapped");
				break;
			case 1:
				groupMapping.getMapped().put(src.getName(), targets.get(0));
				mappedGroupCount++;
				break;
			default:
				groupMapping.getConflict().put(src.getName(), targets);
				LOGGER.error("\tGroup " + src.getName() + " is mapped to multiple Cloud groups");
				break;
			}
		}
		LOGGER.info("Groups mapped: " + mappedGroupCount + "/" + serverGroups.size());
		saveFile(DataFile.GROUP_MAP, groupMapping);
		LOGGER.info("Data dump completed");
	}
	
	private static DataCenterFilter getFilter(Client client, String baseURL, int id) throws Exception {
		URI uri = new URI(baseURL).resolve("rest/api/latest/filter/").resolve(Integer.toString(id));
		Response resp = restCall(client, uri, HttpMethod.GET, null, null, null);
		if (checkStatusCode(resp, Response.Status.OK)) {
			return resp.readEntity(DataCenterFilter.class);
		} else {
			throw new Exception(resp.readEntity(String.class));
		}
	}
	
	private static void dumpFilter(FilterMapper filterMapper, Client cloudClient, Client dataCenterClient, Config conf) throws Exception {
		LOGGER.info("Dumping filters from Data Center, remapping fields using mapping files...");
		// Filter mapping
		LOGGER.info("Processing Filters...");
		// Dump filter from server
		List<Integer> filters = filterMapper.getFilters();
		List<DataCenterFilter> filterList = new ArrayList<>();
		// Read mappings
		Mapping userMapping = readFile(DataFile.USER_MAP, Mapping.class);
		Mapping projectMapping = readFile(DataFile.PROJECT_MAP, Mapping.class);
		Mapping roleMapping = readFile(DataFile.ROLE_MAP, Mapping.class);
		Mapping groupMapping = readFile(DataFile.GROUP_MAP, Mapping.class);
		for (Integer id : filters) {
			try {
				DataCenterFilter filter = getFilter(dataCenterClient, conf.getSourceRESTBaseURL(), id);
				//System.out.println("Processing filter " + filter.getName() + "...");
				// Translate owner id via cloud site
				if (userMapping.getMapped().containsKey(filter.getOwner().getName())) {
					filter.getOwner().setAccountId(userMapping.getMapped().get(filter.getOwner().getName()));
				} else {
					LOGGER.error("Filter " + filter.getName() + " owner " + filter.getOwner().getName() + " cannot be mapped");
				}				
				// Translate id via cloud site
				for (DataCenterPermission permission : filter.getSharePermissions()) {
					PermissionType type = PermissionType.parse(permission.getType());
					if (type == PermissionType.USER) {
						if (userMapping.getMapped().containsKey(permission.getUser().getId())) {
							permission.getUser().setAccountId(userMapping.getMapped().get(permission.getUser().getName()));
						} else {
							LOGGER.error("Filter " + filter.getName() + " user " + permission.getUser().getName() + " (" + permission.getUser().getDisplayName() + ") cannot be mapped");
						}
					} else if (type == PermissionType.GROUP) {
						if (groupMapping.getMapped().containsKey(permission.getGroup().getName())) {
							permission.getGroup().setGroupId(groupMapping.getMapped().get(permission.getGroup().getName()));
						} else {
							LOGGER.error("Filter " + filter.getName() + " group " + permission.getGroup().getName() + " cannot be mapped");
						}
					} else if (type == PermissionType.PROJECT) {
						if (projectMapping.getMapped().containsKey(permission.getProject().getId())) {
							permission.getProject().setId(projectMapping.getMapped().get(permission.getProject().getId()));
						} else {
							LOGGER.error("Filter " + filter.getName() + " project " + permission.getProject().getId() + " (" + permission.getProject().getName() + ") cannot be mapped");
						}
						if (permission.getRole() != null) {
							permission.setType(PermissionType.PROJECT_ROLE.toString());
							if (roleMapping.getMapped().containsKey(permission.getRole().getId())) {
								permission.getRole().setId(roleMapping.getMapped().get(permission.getRole().getId()));
							} else {
								LOGGER.error("Filter " + filter.getName() + " role " + permission.getRole().getId() + " (" + permission.getRole().getName() + ") cannot be mapped");
							}
						}
					}
				}
				filterList.add(filter);						
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
		saveFile(DataFile.FILTER_DATA, filterList);
		LOGGER.info("Filter dump completed");
	}
	
	private static void createFilter(FilterMapper filterMapper, Client cloudClient, Client dataCenterClient, Config conf) throws Exception {
		LOGGER.info("Creating filters...");
		List<DataCenterFilter> filters = readFile(DataFile.FILTER_DATA, new GenericType<List<DataCenterFilter>>() {});
		// Create filters
		Mapping migratedList = new Mapping(MappingType.FILTER);
		int migratedCount = 0;
		for (DataCenterFilter filter : filters) {
			CloudFilter cf = CloudFilter.create(filter);
			Response resp = restCall(cloudClient, new URI(conf.getTargetRESTBaseURL()).resolve("rest/api/latest/filter"), HttpMethod.POST, null, null, cf);
			if (checkStatusCode(resp, Response.Status.OK)) {
				CloudFilter newFilter = resp.readEntity(CloudFilter.class);
				// Change owner
				PermissionTarget owner = new PermissionTarget();
				owner.setAccountId(filter.getOwner().getAccountId());
				Response resp2 = restCall(cloudClient, new URI(conf.getTargetRESTBaseURL()).resolve("rest/api/latest/filter/").resolve(newFilter.getId() + "/").resolve("owner"), 
						HttpMethod.PUT, null, null, owner);
				if (!checkStatusCode(resp2, Response.Status.NO_CONTENT)) {
					LOGGER.error("Failed to set owner for filter " + filter.getName() + ": " + resp2.readEntity(String.class));
				}
				migratedList.getMapped().put(filter.getId(), newFilter.getId());
				migratedCount++;
			} else {
				String msg = resp.readEntity(String.class);
				migratedList.getFailed().put(filter.getId(), msg);
				LOGGER.error("Failed to create filter " + filter.getName() + ": " + msg);
			}
		}
		saveFile(DataFile.FILTER_MIGRATED, migratedList);
		LOGGER.info("Filters migrated: " + migratedCount + "/" + filters.size());
		LOGGER.info("Create filter completed");
	}
	
	private static void deleteFilter(FilterMapper filterMapper, Client cloudClient, Client dataCenterClient, Config conf) throws Exception {
		LOGGER.info("Deleting migrated filters...");
		Mapping filters = readFile(DataFile.FILTER_MIGRATED, Mapping.class);
		int deletedCount = 0;
		for (Map.Entry<String, String> filter : filters.getMapped().entrySet()) {
			Response resp = restCall(cloudClient, new URI(conf.getTargetRESTBaseURL()).resolve("rest/api/latest/filter/").resolve(filter.getValue()), HttpMethod.DELETE, null, null, null);
			if (checkStatusCode(resp, Response.Status.OK)) {
				deletedCount++;
			} else {
				LOGGER.error("Failed to delete filter " + filter.getKey() + " (" + filter.getValue() + "): " + resp.readEntity(String.class));
			}
		}
		LOGGER.info("Filters deleted: " + deletedCount + "/" + filters.getMapped().size());
		LOGGER.info("Delete filter completed");
	}
	
	private static void dumpDashboard(FilterMapper filterMapper, Client cloudClient, Client dataCenterClient, Config conf) throws Exception {
		LOGGER.info("Dumping dashboards from Data Center...");
		// Dump dashboard from server
		List<DataCenterPortalPage> dashboards = filterMapper.getDashboards();
		Mapping projectMapping = readFile(DataFile.PROJECT_MAP, Mapping.class);
		Mapping roleMapping = readFile(DataFile.ROLE_MAP, Mapping.class);
		Mapping userMapping = readFile(DataFile.USER_MAP, Mapping.class);
		Mapping groupMapping = readFile(DataFile.GROUP_MAP, Mapping.class);
		Mapping fieldMapping = readFile(DataFile.FIELD_MAP, Mapping.class);
		Mapping filterMapping = readFile(DataFile.FILTER_MIGRATED, Mapping.class);
		for (DataCenterPortalPage dashboard : dashboards) {
			// Translate owner
			if (userMapping.getMapped().containsKey(dashboard.getUsername())) {
				dashboard.setAccountId(userMapping.getMapped().get(dashboard.getUsername()));
			}
			// Sort gadgets, then loop over to ensure no gaps or collisions
			dashboard.getPortlets().sort(new DataCenterGadgetComparator());
			int cursorY = 0;
			int cursorX = 0;
			int maxY = 0;
			int maxX = 0;
			for (DataCenterPortletConfiguration gadget : dashboard.getPortlets()) {
				// Save cursor position
				cursorY = gadget.getPositionSeq();
				cursorX = gadget.getColumnNumber();
				// If max is exceeded, calculate offset
				if (cursorY > maxY + 1) {
					gadget.setPositionSeq(maxY + 1);
				}
				if (cursorX > maxX + 1) {
					gadget.setColumnNumber(maxX + 1);
				}
				//System.out.println("Gadget: (" + cursorY + ", " + cursorX + ") => (" + gadget.getPositionSeq() + ", " + gadget.getColumnNumber() + ")");
				// Save max
				maxY = Math.max(maxY, cursorY);
				maxX = Math.max(maxX, cursorX);				
			}
			// Fix configuration values
			for (DataCenterPortletConfiguration gadget : dashboard.getPortlets()) {
				CloudGadgetConfigurationMapper.mapConfiguration(gadget, projectMapping, roleMapping, fieldMapping, groupMapping, userMapping, filterMapping);
			}
		}
		saveFile(DataFile.DASHBOARD_DATA, dashboards);
		LOGGER.info("Dump dashboard completed");
		LOGGER.info("Please manually translate references");
	}
	
	private static void createDashboard(FilterMapper filterMapper, Client cloudClient, Client dataCenterClient, Config conf) throws Exception {
		LOGGER.info("Creating dashboards...");
		List<DataCenterPortalPage> dashboards = readFile(DataFile.DASHBOARD_DATA, new GenericType<List<DataCenterPortalPage>>() {});
		// Create filters
		Mapping migratedList = new Mapping(MappingType.DASHBOARD);
		int migratedCount = 0;
		for (DataCenterPortalPage dashboard : dashboards) {
			// Create dashboard
			CloudDashboard cd = CloudDashboard.create(dashboard);
			Response resp = restCall(cloudClient, new URI(conf.getTargetRESTBaseURL()).resolve("rest/api/latest/dashboard"), HttpMethod.POST, null, null, cd);
			if (checkStatusCode(resp, Response.Status.OK)) {
				CloudDashboard createdDashboard = resp.readEntity(CloudDashboard.class);
				//System.out.println("Created dashboard " + dashboard.getPageName() + ": " + createdDashboard.getId());
				// Impossible to change layout via REST?
				for (DataCenterPortletConfiguration gadget : dashboard.getPortlets()) {
					// Add gadgets
					CloudGadget cg = CloudGadget.create(gadget);
					Response resp1 = restCall(
							cloudClient, 
							new URI(conf.getTargetRESTBaseURL()).resolve("rest/api/latest/dashboard/").resolve(createdDashboard.getId() + "/").resolve("gadget"),
							HttpMethod.POST,
							null,
							null,
							cg);				
					if (checkStatusCode(resp1, Response.Status.OK)) {
						CloudGadget createdGadget = resp1.readEntity(CloudGadget.class);
						//System.out.println("Created gadget " + gadget.getGadgetXml() + " for dashboard " + dashboard.getPageName() + ": " + createdGadget.getId());
						// Gadget configuration
						CloudGadgetConfiguration cc = CloudGadgetConfiguration.create(gadget.getGadgetConfigurations());
						Response resp2 = restCall(
								cloudClient, 
								new URI(conf.getTargetRESTBaseURL())
									.resolve("rest/api/latest/dashboard/").resolve(createdDashboard.getId() + "/")
									.resolve("items/").resolve(createdGadget.getId() + "/")
									.resolve("properties/config"),
								HttpMethod.PUT,
								null,
								null,
								cc);
						if (checkStatusCode(resp2, Response.Status.OK)) {
							//System.out.println("Added config for gadget " + gadget.getGadgetXml() + " for dashboard " + dashboard.getPageName() + ": " + createdGadget.getId());
						} else {
							LOGGER.error("Failed to config for gadget " + gadget.getGadgetXml() + " in dashboard " + dashboard.getPageName() + ": " + resp2.readEntity(String.class));
						}
					} else {
						LOGGER.error("Failed to add gadget " + gadget.getGadgetXml() + " to dashboard " + dashboard.getPageName() + ": " + resp1.readEntity(String.class));
					}
				}
				// Change owner
				// There's no REST API to change owner?!!
				LOGGER.warn("Please change owner of " + createdDashboard.getName() + " to " + dashboard.getUserDisplayName());
				migratedList.getMapped().put(Integer.toString(dashboard.getId()), createdDashboard.getId());
				migratedCount++;
			} else {
				String msg = resp.readEntity(String.class);
				migratedList.getFailed().put(Integer.toString(dashboard.getId()), msg);
				LOGGER.error("Failed to create dashboard " + dashboard.getPageName() + ": " + msg);
			}
		}
		saveFile(DataFile.DASHBOARD_MIGRATED, migratedList);
		LOGGER.info("Dashboards migrated: " + migratedCount + "/" + dashboards.size());
		LOGGER.info("Create dashboard completed");
	}
	
	private static void deleteDashboard(FilterMapper filterMapper, Client cloudClient, Client dataCenterClient, Config conf) throws Exception {
		LOGGER.info("Deleting migrated dashboards...");
		Mapping dashboards = readFile(DataFile.DASHBOARD_MIGRATED, Mapping.class);
		int deletedCount = 0;
		for (Map.Entry<String, String> dashboard : dashboards.getMapped().entrySet()) {
			Response resp = restCall(cloudClient, new URI(conf.getTargetRESTBaseURL()).resolve("rest/api/latest/dashboard/").resolve(dashboard.getValue()), HttpMethod.DELETE, null, null, null);
			if (checkStatusCode(resp, Response.Status.OK)) {
				deletedCount++;
			} else {
				LOGGER.error("Failed to delete dashboard " + dashboard.getKey() + " (" + dashboard.getValue() + "): " + resp.readEntity(String.class));
			}
		}
		LOGGER.info("Dashboards deleted: " + deletedCount + "/" + dashboards.getMapped().size());
		LOGGER.info("Delete dashboard completed");
	}
	
	public static void main(String[] args) {
		try {
			// Parse config
			Config conf = parseConfig(args);
			if (conf == null) {
				printHelp();
				return;
			}
			// Setup Jersey2 to source and target sites
			Client dataCenterClient = ClientBuilder.newClient();
			dataCenterClient.register(JACKSON_JSON_PROVIDER);
			Client cloudClient = ClientBuilder.newClient();
			cloudClient.register(JACKSON_JSON_PROVIDER);
			// Setup authentication
			dataCenterClient.register(HttpAuthenticationFeature.basic(conf.getSourceUser(), conf.getSourcePassword()));
			cloudClient.register(HttpAuthenticationFeature.basic(conf.getTargetUser(), conf.getTargetAPIToken()));
			if (conf.isJerseyLog()) {
				java.util.logging.Logger JERSEY_LOGGER = org.apache.logging.log4j.jul.LogManager.getLogManager().getLogger(DashboardMigrator.class.getName());
				LoggingFeature loggingFeature = new LoggingFeature(JERSEY_LOGGER, Verbosity.PAYLOAD_ANY);
				dataCenterClient.register(loggingFeature);
				cloudClient.register(loggingFeature);
			}
			// Setup MyBatis
			SqlSessionFactory sqlSessionFactory = setupMyBatis(conf);
			try (SqlSession session = sqlSessionFactory.openSession()) {
				// Get filter info from source
				FilterMapper filterMapper = session.getMapper(FilterMapper.class);
				switch (conf.getOperation()) {
				case DUMP:
					dumpData(filterMapper, cloudClient, dataCenterClient, conf);
					break;
				case DUMP_FILTER:
					dumpFilter(filterMapper, cloudClient, dataCenterClient, conf);
					break;
				case CREATE_FILTER:
					createFilter(filterMapper, cloudClient, dataCenterClient, conf);
					break;
				case DELETE_FILTER: 
					deleteFilter(filterMapper, cloudClient, dataCenterClient, conf);
					break;
				case DUMP_DASHBOARD:
					dumpDashboard(filterMapper, cloudClient, dataCenterClient, conf);
					break;
				case CREATE_DASHBOARD:
					createDashboard(filterMapper, cloudClient, dataCenterClient, conf);
					break;
				case DELETE_DASHBOARD:
					deleteDashboard(filterMapper, cloudClient, dataCenterClient, conf);
					break;
				}
			}
		} catch (Exception ex) {
			LOGGER.error("Exception: " + ex.getMessage(), ex);
		}
	}
}
