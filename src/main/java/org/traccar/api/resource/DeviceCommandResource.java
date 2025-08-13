/*
 * Copyright 2025 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

import org.traccar.api.BaseResource;
import org.traccar.database.CommandsManager;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.model.QueuedCommand;
import org.traccar.helper.LogAction;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

@Path("device-commands")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DeviceCommandResource extends BaseResource {

    @Inject
    private CommandsManager commandsManager;

    @Inject
    private LogAction actionLogger;

    @Context
    private HttpServletRequest request;

    /**
     * Request current position from a device
     * This will send a position single command to the device to get its current location
     * 
     * @param deviceId Device ID to request position from
     * @return Response with command status
     */
    @POST
    @Path("request-position")
    public Response requestCurrentPosition(@QueryParam("deviceId") long deviceId) throws Exception {
        // Check permissions
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);

        // Create position single command
        Command command = new Command();
        command.setDeviceId(deviceId);
        command.setType(Command.TYPE_POSITION_SINGLE);
        command.setDescription("Request current position from server API");

        try {
            // Send command to device
            QueuedCommand queuedCommand = commandsManager.sendCommand(command);
            
            // Log the action
            actionLogger.command(request, getUserId(), 0, deviceId, Command.TYPE_POSITION_SINGLE);

            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Position request command sent to device");
            response.put("deviceId", deviceId);
            response.put("commandType", Command.TYPE_POSITION_SINGLE);
            
            if (queuedCommand != null) {
                response.put("queued", true);
                response.put("commandId", queuedCommand.getId());
                response.put("status", "Command queued for delivery");
                return Response.accepted(response).build();
            } else {
                response.put("queued", false);
                response.put("status", "Command sent directly to device");
                return Response.ok(response).build();
            }

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to send position request: " + e.getMessage());
            errorResponse.put("deviceId", deviceId);
            errorResponse.put("error", e.getClass().getSimpleName());
            
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResponse)
                    .build();
        }
    }

    /**
     * Request periodic position updates from a device
     * 
     * @param deviceId Device ID
     * @param intervalSeconds Interval in seconds for periodic updates
     * @return Response with command status
     */
    @POST
    @Path("request-periodic-position")
    public Response requestPeriodicPosition(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("interval") int intervalSeconds) throws Exception {
        
        // Check permissions
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);

        // Validate interval (minimum 30 seconds)
        if (intervalSeconds < 30) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Interval must be at least 30 seconds");
            return Response.status(Response.Status.BAD_REQUEST).entity(errorResponse).build();
        }

        // Create periodic position command
        Command command = new Command();
        command.setDeviceId(deviceId);
        command.setType(Command.TYPE_POSITION_PERIODIC);
        command.set(Command.KEY_FREQUENCY, intervalSeconds);
        command.setDescription("Request periodic position updates from server API");

        try {
            QueuedCommand queuedCommand = commandsManager.sendCommand(command);
            
            actionLogger.command(request, getUserId(), 0, deviceId, Command.TYPE_POSITION_PERIODIC);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Periodic position request command sent to device");
            response.put("deviceId", deviceId);
            response.put("commandType", Command.TYPE_POSITION_PERIODIC);
            response.put("intervalSeconds", intervalSeconds);
            
            if (queuedCommand != null) {
                response.put("queued", true);
                response.put("commandId", queuedCommand.getId());
                response.put("status", "Command queued for delivery");
                return Response.accepted(response).build();
            } else {
                response.put("queued", false);
                response.put("status", "Command sent directly to device");
                return Response.ok(response).build();
            }

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to send periodic position request: " + e.getMessage());
            errorResponse.put("deviceId", deviceId);
            errorResponse.put("intervalSeconds", intervalSeconds);
            errorResponse.put("error", e.getClass().getSimpleName());
            
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResponse)
                    .build();
        }
    }

    /**
     * Stop periodic position updates from a device
     * 
     * @param deviceId Device ID
     * @return Response with command status
     */
    @POST
    @Path("stop-position-updates")
    public Response stopPositionUpdates(@QueryParam("deviceId") long deviceId) throws Exception {
        // Check permissions
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);

        // Create position stop command
        Command command = new Command();
        command.setDeviceId(deviceId);
        command.setType(Command.TYPE_POSITION_STOP);
        command.setDescription("Stop position updates from server API");

        try {
            QueuedCommand queuedCommand = commandsManager.sendCommand(command);
            
            actionLogger.command(request, getUserId(), 0, deviceId, Command.TYPE_POSITION_STOP);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Stop position updates command sent to device");
            response.put("deviceId", deviceId);
            response.put("commandType", Command.TYPE_POSITION_STOP);
            
            if (queuedCommand != null) {
                response.put("queued", true);
                response.put("commandId", queuedCommand.getId());
                response.put("status", "Command queued for delivery");
                return Response.accepted(response).build();
            } else {
                response.put("queued", false);
                response.put("status", "Command sent directly to device");
                return Response.ok(response).build();
            }

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to send stop position command: " + e.getMessage());
            errorResponse.put("deviceId", deviceId);
            errorResponse.put("error", e.getClass().getSimpleName());
            
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResponse)
                    .build();
        }
    }
}
