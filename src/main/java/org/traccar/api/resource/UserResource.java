/*
 * Copyright 2015 - 2024 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in com        Map<String, Object> report = summaryReportProvider.getLast24HDeviceReport(deviceId);

        final Device finalDevice = device;
        final Position finalPosition = position;
        final Map<String, Object> finalReport = report;

        // Extract original summary for backward compatibility
        SummaryReportItem originalSummary = null;
        double offHours = 0.0;
        
        if (report != null && report.get("originalSummary") != null) {
            originalSummary = (SummaryReportItem) report.get("originalSummary");
            
            long totalMillis = Duration.between(originalSummary.getStartTime().toInstant(), originalSummary.getEndTime().toInstant()).toMillis();
            // Calculate OFF hours in milliseconds
            long offMillis = totalMillis - originalSummary.getEngineHours();
            // Convert milliseconds to hours (decimal)
            offHours = offMillis / (1000.0 * 60 * 60);
        }

        final SummaryReportItem finalOriginalSummary = originalSummary;
        final double finalOffHours = offHours;

        return Response.ok(new Object() {
            public final Device device = finalDevice;
            public final Position position = finalPosition;
            public final Map<String, Object> enhancedReport = finalReport; // Full enhanced report
            public final SummaryReportItem summary = finalOriginalSummary; // Original summary for backward compatibility
            public final double engineOffHours = finalOffHours;
        }).build();ense.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.api.resource;

import com.warrenstrange.googleauth.GoogleAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import org.traccar.api.BaseObjectResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.LogAction;
import org.traccar.helper.SessionHelper;
import org.traccar.helper.model.UserUtil;
import org.traccar.model.Device;
import org.traccar.model.ManagedUser;
import org.traccar.model.Permission;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.reports.SummaryReportProvider;
import org.traccar.reports.model.SummaryReportItem;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

@Path("users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource extends BaseObjectResource<User> {

    @Inject
    private Config config;

    @Inject
    private LogAction actionLogger;

    @Context
    private HttpServletRequest request;

    @Inject
    private SummaryReportProvider summaryReportProvider;

    public UserResource() {
        super(User.class);
    }

    @GET
    public Collection<User> get(@QueryParam("userId") long userId, @QueryParam("deviceId") long deviceId)
            throws StorageException {
        var conditions = new LinkedList<Condition>();
        if (userId > 0) {
            permissionsService.checkUser(getUserId(), userId);
            conditions.add(new Condition.Permission(User.class, userId, ManagedUser.class).excludeGroups());
        } else if (permissionsService.notAdmin(getUserId())) {
            conditions.add(new Condition.Permission(User.class, getUserId(), ManagedUser.class).excludeGroups());
        }
        if (deviceId > 0) {
            permissionsService.checkManager(getUserId());
            conditions.add(new Condition.Permission(User.class, Device.class, deviceId).excludeGroups());
        }
        return storage.getObjects(baseClass, new Request(
                new Columns.All(), Condition.merge(conditions), new Order("name")));
    }

    @Override
    @PermitAll
    @POST
    public Response add(User entity) throws StorageException {
        User currentUser = getUserId() > 0 ? permissionsService.getUser(getUserId()) : null;
        if (currentUser == null || !currentUser.getAdministrator()) {
            permissionsService.checkUserUpdate(getUserId(), new User(), entity);
            if (currentUser != null && currentUser.getUserLimit() != 0) {
                int userLimit = currentUser.getUserLimit();
                if (userLimit > 0) {
                    int userCount = storage.getObjects(baseClass, new Request(
                            new Columns.All(),
                            new Condition.Permission(User.class, getUserId(), ManagedUser.class).excludeGroups()))
                            .size();
                    if (userCount >= userLimit) {
                        throw new SecurityException("Manager user limit reached");
                    }
                }
            } else {
                if (UserUtil.isEmpty(storage)) {
                    entity.setAdministrator(true);
                } else if (!permissionsService.getServer().getRegistration()) {
                    throw new SecurityException("Registration disabled");
                }
                if (permissionsService.getServer().getBoolean(Keys.WEB_TOTP_FORCE.getKey())
                        && entity.getTotpKey() == null) {
                    throw new SecurityException("One-time password key is required");
                }
                UserUtil.setUserDefaults(entity, config);
            }
        }

        entity.setId(storage.addObject(entity, new Request(new Columns.Exclude("id"))));
        storage.updateObject(entity, new Request(
                new Columns.Include("hashedPassword", "salt"),
                new Condition.Equals("id", entity.getId())));

        actionLogger.create(request, getUserId(), entity);

        if (currentUser != null && currentUser.getUserLimit() != 0) {
            storage.addPermission(new Permission(User.class, getUserId(), ManagedUser.class, entity.getId()));
            actionLogger.link(request, getUserId(), User.class, getUserId(), ManagedUser.class, entity.getId());
        }
        return Response.ok(entity).build();
    }

    @Path("{id}")
    @DELETE
    public Response remove(@PathParam("id") long id) throws Exception {
        Response response = super.remove(id);
        if (getUserId() == id) {
            request.getSession().removeAttribute(SessionHelper.USER_ID_KEY);
        }
        return response;
    }

    @Path("totp")
    @PermitAll
    @POST
    public String generateTotpKey() throws StorageException {
        if (!permissionsService.getServer().getBoolean(Keys.WEB_TOTP_ENABLE.getKey())) {
            throw new SecurityException("One-time password is disabled");
        }
        return new GoogleAuthenticator().createCredentials().getKey();
    }

    @Path("{id}/device/{deviceId}")
    @GET
    public Response getUserDevice(@PathParam("id") long userId, @PathParam("deviceId") long deviceId) throws Exception {
        permissionsService.checkUser(getUserId(), userId);

        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("id", deviceId),
                        new Condition.Permission(User.class, userId, Device.class))));

        Request positionRequest = new Request(new Columns.All(), new Condition.Equals("id", device.getPositionId()));
        Position position = storage.getObject(Position.class, positionRequest);

        if (position != null) {
            String currentStatus = summaryReportProvider.deviceCurrentStatus(position);
            position.getAttributes().put("currentStatus", currentStatus);
        }

        Map<String, Object> report = summaryReportProvider.getLast24HDeviceReport(deviceId, position);

        final Device finalDevice = device;
        final Position finalPosition = position;
        final Map<String, Object> finalReport = report;

        // Extract original summary for calculations
        double offHours = 0.0;
        if (report != null && report.get("originalSummary") != null) {
            SummaryReportItem originalSummary = (SummaryReportItem) report.get("originalSummary");

            long totalMillis = Duration
                    .between(originalSummary.getStartTime().toInstant(), originalSummary.getEndTime().toInstant())
                    .toMillis();
            // Calculate OFF hours in milliseconds
            long offMillis = totalMillis - originalSummary.getEngineHours();
            // Convert milliseconds to hours (decimal)
            offHours = offMillis / (1000.0 * 60 * 60);
        }

        final double finalOffHours = offHours;

        return Response.ok(new Object() {
            public final Device device = finalDevice;
            public final Position position = finalPosition;
            public final Map<String, Object> summary = finalReport;
            public final double engineOffHours = finalOffHours;
        }).build();

    }

    @Path("{id}/devices")
    @GET
    public Response getUserDevices(@PathParam("id") long userId) throws Exception {
        permissionsService.checkUser(getUserId(), userId);

        // Get all devices for the user
        Collection<Device> devices = storage.getObjects(Device.class, new Request(
                new Columns.All(),
                new Condition.Permission(User.class, userId, Device.class)));

        // Create response with devices and their positions/summaries
        var deviceResponses = devices.stream().map(device -> {
            try {
                Position position = null;
                if (device.getPositionId() != 0) {
                    Request positionRequest = new Request(new Columns.All(), 
                            new Condition.Equals("id", device.getPositionId()));
                    position = storage.getObject(Position.class, positionRequest);
                    
                    if (position != null) {
                        String currentStatus = summaryReportProvider.deviceCurrentStatus(position);
                        position.getAttributes().put("currentStatus", currentStatus);
                    }
                }

                Map<String, Object> report = summaryReportProvider.getLast24HDeviceReport(device.getId(), position);

                // Calculate engine off hours
                double offHours = 0.0;
                if (report != null && report.get("originalSummary") != null) {
                    SummaryReportItem originalSummary = (SummaryReportItem) report.get("originalSummary");
                    long totalMillis = Duration
                            .between(originalSummary.getStartTime().toInstant(), originalSummary.getEndTime().toInstant())
                            .toMillis();
                    long offMillis = totalMillis - originalSummary.getEngineHours();
                    offHours = offMillis / (1000.0 * 60 * 60);
                }

                final Device finalDevice = device;
                final Position finalPosition = position;
                final Map<String, Object> finalReport = report;
                final double finalOffHours = offHours;

                return new Object() {
                    public final Device device = finalDevice;
                    public final Position position = finalPosition;
                    public final Map<String, Object> summary = finalReport;
                    public final double engineOffHours = finalOffHours;
                };
            } catch (StorageException e) {
                throw new RuntimeException(e);
            }
        }).toList();

        return Response.ok(deviceResponses).build();
    }

}
