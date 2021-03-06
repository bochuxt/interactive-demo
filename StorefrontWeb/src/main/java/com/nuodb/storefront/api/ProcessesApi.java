/* Copyright (c) 2013-2015 NuoDB, Inc. */

package com.nuodb.storefront.api;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.log4j.Logger;

import com.nuodb.storefront.exception.ApiException;
import com.nuodb.storefront.launcher.TourLauncher;
import com.nuodb.storefront.model.db.Process;
import com.nuodb.storefront.model.dto.ProcessDetail;

@Path("/processes")
public class ProcessesApi extends BaseApi {
	
	private static final Map<String, Map<String, Integer>> tourTopologies = new HashMap<>();
	private static Map<String, Integer> currentTopology = new HashMap<>();
	
	static {
		Map<String, Integer> comparisonTour = new HashMap<>();
		Map<String, Integer> scalingTour = new HashMap<>();
		scalingTour.put("SM", 1);
		scalingTour.put("TE", 1);
		scalingTour.put("MYSQL", 0);
		comparisonTour.put("SM", 1);
		comparisonTour.put("TE", 1);
		comparisonTour.put("MYSQL", 1);
		tourTopologies.put("tour-scale-out", scalingTour);
		tourTopologies.put("tour-database-comparison", comparisonTour);
		currentTopology.put("SM", 1);
		currentTopology.put("TE", 1);
		currentTopology.put("MYSQL", 0);
	}
	
	

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<ProcessDetail> getProcesses(@Context HttpServletRequest req) {
        int currentNodeId = getTenant(req).getAppInstance().getNodeId();

        // Fetch processes
        Map<Integer, ProcessDetail> processMap = new HashMap<Integer, ProcessDetail>();
        for (Process process : getDbApi(req).getDbProcesses()) {
            ProcessDetail detail;
            processMap.put(process.nodeId, detail = new ProcessDetail(process));
            
            if (process.nodeId == currentNodeId) {
                detail.setCurrentConnection(true);
            }
        }

        return processMap.values();
    }

    @POST
    @Path("/increaseHostCount")
    @Produces(MediaType.APPLICATION_JSON)
    public Response increaseHostCount(@Context HttpServletRequest req) {
        Logger log = getTenant(req).getLogger(this.getClass());

        try {
            getDbApi(req).increaseTeCount();
            log.warn("Host count increase requested");
            putActivityLog("Host count increase requested");
            currentTopology.put("TE", Math.max(currentTopology.get("TE") + 1, 5));
        } catch (ApiException e) {
            log.error(e.getMessage() + "\n" + e.getStackTraceAsString());

            return Response.serverError().header("X-Exception-Message", e.getMessage()).build();
        }

        return Response.ok().build();
    }

    @POST
    @Path("/decreaseHostCount")
    @Produces(MediaType.APPLICATION_JSON)
    public Response decreaseHostCount(@Context HttpServletRequest req) {
        Logger log = getTenant(req).getLogger(this.getClass());

        try {
            getDbApi(req).decreaseTeCount();
            log.warn("Host count decrease requested");
            putActivityLog("Host count decrease requested");
            currentTopology.put("TE", Math.max(currentTopology.get("TE") - 1, 1));
        } catch (ApiException e) {
            log.error(e.getMessage() + "\n" + e.getStackTraceAsString());

            return Response.serverError().header("X-Exception-Message", e.getMessage()).build();
        }

        return Response.ok().build();
    }

    @POST
    @Path("/resetHostCount")
    @Produces(MediaType.APPLICATION_JSON)
    public Response resetHostCount(@Context HttpServletRequest req) {
        Logger log = getTenant(req).getLogger(this.getClass());

        try {
            getDbApi(req).resetTeCount();
            log.info("Host count reset requested");
            putActivityLog("Host count reset requested");
            currentTopology = new HashMap<>(tourTopologies.get("tour-scale-out"));
        } catch (ApiException e) {
            log.error(e.getMessage() + "\n" + e.getStackTraceAsString());

            return Response.serverError().header("X-Exception-Message", e.getMessage()).build();
        }

        return Response.ok().build();
    }

    @DELETE
    @Path("/{uid}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@Context HttpServletRequest req, @PathParam("uid") String uid) {
        getDbApi(req).shutdownProcess(uid);
        return Response.ok().build();
    }

    private void putActivityLog(String message) {
        Map<String, String> event = new HashMap<>();
        event.put("Data", message);

        AppInstanceApi app = new AppInstanceApi();
        app.putLog(event);

        return;
    }
    
    public static boolean initializeTourInfrastructure(String tourName, HttpServletRequest req) {
    	Logger log = getTenant(req).getLogger(ProcessesApi.class);
    	if (tourTopologies.containsKey(tourName)) {
    		if (tourTopologies.get(tourName).equals(currentTopology)) {
    			return true;
    		} else {
    			log.info("Initializing tour: " + tourName);
    			try {
					return new TourLauncher().initializeTour(tourTopologies.get(tourName));
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					return false;
				}
    		}
    	}
    	log.warn("Tour " + tourName + " has no associated topology.");
    	return false;
    }
}