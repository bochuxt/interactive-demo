/* Copyright (c) 2013-2015 NuoDB, Inc. */

package com.nuodb.storefront.service.dbapi;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;

import com.nuodb.storefront.exception.ApiException;
import com.nuodb.storefront.exception.DataValidationException;
import com.nuodb.storefront.exception.DatabaseNotFoundException;
import com.nuodb.storefront.model.db.Database;
import com.nuodb.storefront.model.db.Host;
import com.nuodb.storefront.model.db.Process;
import com.nuodb.storefront.model.db.ProcessSpec;
import com.nuodb.storefront.model.db.Region;
import com.nuodb.storefront.model.db.Tag;
import com.nuodb.storefront.model.dto.ConnInfo;
import com.nuodb.storefront.model.dto.DbConnInfo;
import com.nuodb.storefront.model.dto.DbFootprint;
import com.nuodb.storefront.model.dto.RegionStats;
import com.nuodb.storefront.service.IDbApi;
import com.nuodb.storefront.service.IStorefrontTenant;
import com.nuodb.storefront.servlet.StorefrontWebApp;
import com.nuodb.storefront.util.NetworkUtil;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.filter.LoggingFilter;
import com.sun.jersey.api.uri.UriComponent;
import com.sun.jersey.api.uri.UriComponent.Type;
import com.sun.jersey.core.util.Base64;

public class DbApiProxy implements IDbApi {
    private static final String DBVAR_TAG_CONSTRAINT_GROUP_TE = "TEs";
    private static final String DBVAR_TAG_CONSTRAINT_GROUP_SM = "SMs";
    private static final String DBVAR_TAG_CONSTRAINT_TE = "TE_OK";
    private static final String DBVAR_TAG_CONSTRAINT_SM = "SM_OK";
    private static final String DBVAR_TAG_EXISTS_CONSTRAINT = "ex:";
    private static final String DBVAR_SM_MIN = "SM_MIN";
    private static final String DBVAR_SM_MAX = "SM_MAX";
    private static final String DBVAR_TE_MIN = "TE_MIN";
    private static final String DBVAR_TE_MAX = "TE_MAX";
    private static final String DBVAR_HOST = "HOST";
    private static final String DBVAR_REGION = "REGION";

    private static final String ARCHIVEVAR_ARCHIVE_DIR = "archiveDir";
    private static final String ARCHIVE_DIR_SSM_SUFFIX = "_snapshot";

    private static final String TEMPLATE_GEO_DISTRIBUTED = "Region Distribution";
    private static final String TEMPLATE_MULTI_HOST = "Multi Host";
    private static final String TEMPLATE_SINGLE_HOST = "Single Host";
    private static final String OPTIONS_PING_TIMEOUT = "ping-timeout";
    private static final String OPTIONS_STORAGE_GROUP = "storage-group";

    private static final String PROCESS_TRANSACTION_ENGINE = "TE";
    private static final String PROCESS_STORAGE_MANAGER = "SM";
    private static final String PROCESS_SNAPSHOT_STORAGE_MANAGER = "SSM";

    private static final String STORAGE_GROUP_ALL = "ALL";

    private final IStorefrontTenant tenant;
    private final ConnInfo apiConnInfo;
    private final DbConnInfo dbConnInfo;
    private final Logger logger;
    private final RequestLogger requestLogger;
    private int ssmFailCount = 0;

    public DbApiProxy(IStorefrontTenant tenant) {
        this.tenant = tenant;
        this.apiConnInfo = tenant.getApiConnInfo();
        this.dbConnInfo = tenant.getDbConnInfo();
        this.logger = tenant.getLogger(getClass());
        this.requestLogger = (logger.isDebugEnabled()) ? new RequestLogger(logger) : null;
    }

    @Override
    public ConnInfo getApiConnInfo() {
        ConnInfo info = new ConnInfo(apiConnInfo);
        info.setPassword(null);
        return info;
    }

    @Override
    public void testConnection() {
        try {
            buildClient("/processes").get(Object.class);
        } catch (Exception e) {
            throw ApiException.toApiException(e);
        }
    }

    @Override
    public Database getDb() throws ApiException {
        try {
            String dbName = dbConnInfo.getDbName();
            return buildClient("/databases/" + UriComponent.encode(dbName, Type.PATH_SEGMENT)).get(Database.class);
        } catch (ClientHandlerException e) {
            // DB not found
            return null;
        } catch (Exception e) {
            throw ApiException.toApiException(e);
        }
    }

    @Override
    public List<Process> getDbProcesses() {
        try {
            String dbName = dbConnInfo.getDbName();
            String result = buildClient("/processes?filterBy=database&database=" + dbName).accept(MediaType.APPLICATION_JSON_TYPE).get(String.class);
    		List<Process> processes = new ObjectMapper().readValue(result, new TypeReference<List<Process>>(){});
            return processes;
        } catch (Exception e) {
            throw ApiException.toApiException(e);
        }
    }
  

    @Override
    public void shutdownProcess(String uid) {
        try {
            buildClient("/processes/" + uid).delete();
        } catch (Exception e) {
            ApiException ape = ApiException.toApiException(e);
            if (ape.getErrorCode() != Status.NOT_FOUND) {
                throw ape;
            }
        }
    }

    @Override
    public List<RegionStats> getRegionStats() {
        List<Region> regions = getRegions();
        List<RegionStats> stats = new ArrayList<RegionStats>(regions.size());
        for (Region region : regions) {
        	RegionStats rStats = new RegionStats(region);
        	rStats.usedHostCount = getDbProcesses().size();
            stats.add(rStats);
        }
        return stats;
    }

    @Override
    public DbFootprint getDbFootprint() {
        return getDbFootprint(getRegions());
    }

    @Override
    public void increaseTeCount() {

    }

    @Override
    public void decreaseTeCount() {

    }

    @Override
    public void resetTeCount() {

    }

    protected List<Region> getRegions() {
        try {
            String dbName = dbConnInfo.getDbName();
            String dbProcessTag = dbConnInfo.getDbProcessTag();
            Region[] regions = buildClient("/regions").get(Region[].class);

            for (Region region : regions) {
                for (Host host : region.hosts) {
                    region.hostCount++;
                    if (host.tags.containsKey(dbProcessTag)) {
                        boolean hostHasDbProcess = false;

                        for (Process process : host.processes) {
                            if (process.dbname.equals(dbName)) {
                                if (PROCESS_TRANSACTION_ENGINE.equals(process.type)) {
                                    region.transactionManagerCount++;
                                    hostHasDbProcess = true;
                                } else if (PROCESS_STORAGE_MANAGER.equals(process.type)) {
                                    region.storageManagerCount++;
                                    hostHasDbProcess = true;
                                } else if (PROCESS_SNAPSHOT_STORAGE_MANAGER.equals(process.type)) {
                                    region.snapshotStorageManagerCount++;
                                    hostHasDbProcess = true;
                                }
                            }
                        }

                        if (hostHasDbProcess) {
                            region.usedHostCount++;
                        }

                        if (region.usedHostUrls == null) {
                            region.usedHostUrls = new HashSet<URI>();
                        }
                        if (!StringUtils.isEmpty(host.ipaddress)) {
                            region.usedHostUrls.add(new URI("jdbc", null, host.ipaddress, host.port, null, null, null));
                        }
                    }
                }
            }

            return Arrays.asList(regions);
        } catch (Exception e) {
            throw ApiException.toApiException(e);
        }
    }

    protected void addHostTag(Host host, String tagName, String tagValue) {
        String oldTagValue = host.tags.get(tagName);
        if (tagValue.equals(oldTagValue)) {
            // Tag already exists
            return;
        }

        logger.info("Adding tag '" + tagName + "' to host " + host.address + " (id=" + host.id + ")");

        Tag tag = new Tag(tagName, tagValue);
        buildClient("/hosts/" + host.id + "/tags").post(tag);

        host.tags.put(tag.key, tag.value);
    }

    protected void removeHostTag(Host host, String tagName, boolean shutdownSMs) {
        if (host.tags.remove(tagName) != null) {
            logger.info("Removing tag '" + tagName + "' from host " + host.address + " (id=" + host.id + ")");

            try {
                buildClient("/hosts/" + host.id + "/tags/" + UriComponent.encode(tagName, Type.PATH_SEGMENT)).delete();
            } catch (Exception e) {
                ApiException ape = ApiException.toApiException(e);
                if (ape.getErrorCode() != Status.NOT_FOUND) {
                    throw ape;
                }
            }
        }

        String dbName = dbConnInfo.getDbName();
        for (Process process : host.processes) {
            if (process.dbname.equals(dbName)) {
                if (shutdownSMs || !PROCESS_STORAGE_MANAGER.equals(process.type)) {
                    logger.info("Shutting down " + process.type + " process on host " + host.address + " (uid=" + process.uid + ")");
                    shutdownProcess(process.uid);
                }
            }
        }
    }

    protected HomeHostInfo findHomeHostInfo(Collection<Region> regions) {
        String homeRegionName = tenant.getAppInstance().getRegion();
        String dbName = dbConnInfo.getDbName();
        Set<String> ipAddresses = NetworkUtil.getLocalIpAddresses();

        // Look for best match: Host running SM and sharing our IP and region
        HomeHostInfo smRegionMatch = null;
        HomeHostInfo ipRegionMatch = null;
        HomeHostInfo ipMatch = null;
        HomeHostInfo regionMatch = null;
        for (Region region : regions) {
            for (Host host : region.hosts) {
                HomeHostInfo match = new HomeHostInfo(host, region);

                if (homeRegionName.equals(region.region)) {
                    regionMatch = match;
                    for (Process process : host.processes) {
                        if (dbName.equals(process.dbname) && PROCESS_STORAGE_MANAGER.equals(process.type)) {
                            smRegionMatch = match;
                            break;
                        }
                    }
                    if (ipAddresses.contains(host.ipaddress)) {
                        ipRegionMatch = match;
                        if (smRegionMatch == ipRegionMatch) {
                            // Found best match
                            return smRegionMatch;
                        }
                    }
                } else if (ipAddresses.contains(host.ipaddress)) {
                    ipMatch = match;
                }
            }
        }

        // Second best match: Host running SM in our region
        if (smRegionMatch != null) {
            return smRegionMatch;
        }

        // Third best match: Host sharing our IP and region
        if (ipRegionMatch != null) {
            return ipRegionMatch;
        }

        // Fourth best match: Host sharing our region
        if (regionMatch != null) {
            return regionMatch;
        }

        // Fifth best match: Host sharing our IP
        if (ipMatch != null) {
            return ipMatch;
        }

        // Last resort: random host
        for (Region region : regions) {
            if (region.hosts.length > 0) {
                return new HomeHostInfo(region.hosts[0], region);
            }
        }

        // No host available
        return new HomeHostInfo();
    }

    protected Database findStorefrontDatabase(Collection<Region> regions) {
        String dbName = dbConnInfo.getDbName();

        for (Region region : regions) {
            for (Database database : region.databases) {
                if (database != null && database.name.equals(dbName)) {
                    return database;
                }
            }
        }

        return null;
    }

    protected DbFootprint getDbFootprint(Collection<Region> regions) {
        DbFootprint dbStats = new DbFootprint();
        dbStats.regionCount = regions.size();

        for (Region region : regions) {
            List<Process> procs = getDbProcesses();

            for (Process proc : procs) {
                dbStats.hostCount += 1;
                dbStats.usedHostCount += 1;

                if (proc.type.equals("TE")) {
                    dbStats.usedTeHostCount += 1;
                }
            }

            if (region.usedHostCount > 0) {
                dbStats.usedRegions.add(region.region);
                dbStats.usedRegionCount++;
            }
        }

        return dbStats;
    }

    protected WebResource.Builder buildClient(String path) {
        String authHeader = "Basic " + new String(Base64.encode(apiConnInfo.getUsername() + ":" + apiConnInfo.getPassword()));
        Client client = tenant.createApiClient();
        if (requestLogger != null) {
            client.addFilter(new LoggingFilter(requestLogger));
        }
        return client
                .resource(apiConnInfo.getUrl() + path)
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .type(MediaType.APPLICATION_JSON);
    }

    private static Map<String, String> buildTagMustExistConstraint(String tagName) {
        Map<String, String> constraints = new HashMap<String, String>();
        constraints.put(tagName, DBVAR_TAG_EXISTS_CONSTRAINT);
        return constraints;
    }

    private static int applyVariables(Map<String, String> src, Map<String, String> vars) {
        int changeCount = 0;
        for (Map.Entry<String, String> varPair : vars.entrySet()) {
            if (varPair.getValue() == null) {
                if (src.remove(varPair.getKey()) != null) {
                    changeCount++;
                }
            } else {
                if (!varPair.getValue().equals(src.put(varPair.getKey(), varPair.getValue()))) {
                    changeCount++;
                }
            }
        }
        return changeCount;
    }

    private static int applyMapVariables(Map<String, Map<String, String>> src, Map<String, Map<String, String>> vars) {
        int changeCount = 0;
        for (Map.Entry<String, Map<String, String>> varPair : vars.entrySet()) {
            String key = varPair.getKey();
            Map<String, String> value = varPair.getValue();

            if (value == null) {
                if (src.remove(value) != null) {
                    changeCount++;
                }
            } else if (!src.containsKey(key)) {
                src.put(key, value);
                changeCount += value.size();
            } else {
                changeCount += applyVariables(src.get(key), value);
            }
        }
        return changeCount;
    }
}
