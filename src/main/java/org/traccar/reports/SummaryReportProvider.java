/*
 * Copyright 2016 - 2025 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 Andrey Kunitsyn (andrey@traccar.org)
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
package org.traccar.reports;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jxls.util.JxlsHelper;
import org.traccar.api.security.PermissionsService;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.UnitsConverter;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.helper.model.DeviceUtil;
import org.traccar.helper.model.PositionUtil;
import org.traccar.helper.model.UserUtil;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.reports.common.ReportUtils;
import org.traccar.reports.common.TripsConfig;
import org.traccar.reports.model.StopReportItem;
import org.traccar.reports.model.SummaryReportItem;
import org.traccar.reports.model.TripReportItem;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;
import jakarta.inject.Inject;

public class SummaryReportProvider {

    private final Config config;
    private final ReportUtils reportUtils;
    private final PermissionsService permissionsService;
    private final Storage storage;

    @Inject
    public SummaryReportProvider(
            Config config, ReportUtils reportUtils, PermissionsService permissionsService, Storage storage) {
        this.config = config;
        this.reportUtils = reportUtils;
        this.permissionsService = permissionsService;
        this.storage = storage;
    }

    /**
     * Calculate the distance between two points using the Haversine formula
     * 
     * @param lat1 Latitude of first point
     * @param lon1 Longitude of first point
     * @param lat2 Latitude of second point
     * @param lon2 Longitude of second point
     * @return Distance in kilometers
     */
    private double calculateHaversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // Radius of the Earth in kilometers

        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);

        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return R * c; // Distance in kilometers
    }

    private Collection<SummaryReportItem> calculateDeviceResult(
            Device device, Date from, Date to, boolean fast) throws StorageException {

        SummaryReportItem result = new SummaryReportItem();
        result.setDeviceId(device.getId());
        result.setDeviceName(device.getName());

        System.out.println("Calculating summary for device: " + device.getName() + " from " + from + " to " + to);

        Position first = null;
        Position last = null;
        if (fast) {
            try {
                first = PositionUtil.getEdgePosition(storage, device.getId(), from, to, false);
                last = PositionUtil.getEdgePosition(storage, device.getId(), from, to, true);
            } catch (Exception e) {
                System.out.println(
                        "No positions found for device " + device.getName() + " in time range: " + e.getMessage());
                // If no positions found, return empty result
                return List.of();
            }
        } else {
            var positions = PositionUtil.getPositions(storage, device.getId(), from, to);
            if (positions.isEmpty()) {
                System.out.println("No positions found for device " + device.getName() + " in time range");
                return List.of();
            }
            for (Position position : positions) {
                if (first == null) {
                    first = position;
                }
                if (position.getSpeed() > result.getMaxSpeed()) {
                    result.setMaxSpeed(position.getSpeed());
                }
                last = position;
            }
        }

        if (first != null && last != null) {

            if (!first.hasAttribute(Position.KEY_HOURS)) {
                Map<String, Object> attributes = new HashMap<>(first.getAttributes());
                attributes.put(Position.KEY_HOURS, 1000L);
                first.setAttributes(attributes);
            }

            System.out.println(first.getAttributes());

            TripsConfig tripsConfig = new TripsConfig(
                    new AttributeUtil.StorageProvider(config, storage, permissionsService, device));
            boolean ignoreOdometer = tripsConfig.getIgnoreOdometer();
            result.setDistance(PositionUtil.calculateDistance(first, last, !ignoreOdometer));
            result.setSpentFuel(reportUtils.calculateFuel(first, last));

            if (first.hasAttribute(Position.KEY_HOURS) && last.hasAttribute(Position.KEY_HOURS)) {
                result.setStartHours(first.getLong(Position.KEY_HOURS));
                result.setEndHours(last.getLong(Position.KEY_HOURS));
                long engineHours = result.getEngineHours();
                if (engineHours > 0) {
                    result.setAverageSpeed(UnitsConverter.knotsFromMps(result.getDistance() * 1000 / engineHours));
                }
            }

            if (!ignoreOdometer
                    && first.getDouble(Position.KEY_ODOMETER) != 0 && last.getDouble(Position.KEY_ODOMETER) != 0) {
                result.setStartOdometer(first.getDouble(Position.KEY_ODOMETER));
                result.setEndOdometer(last.getDouble(Position.KEY_ODOMETER));
            } else {
                result.setStartOdometer(first.getDouble(Position.KEY_TOTAL_DISTANCE));
                result.setEndOdometer(last.getDouble(Position.KEY_TOTAL_DISTANCE));
            }

            result.setStartTime(first.getFixTime());
            result.setEndTime(last.getFixTime());
            return List.of(result);
        }

        return List.of();
    }

    private Collection<SummaryReportItem> calculateDeviceResults(
            Device device, ZonedDateTime from, ZonedDateTime to, boolean daily) throws StorageException {

        boolean fast = Duration.between(from, to).toSeconds() > config.getLong(Keys.REPORT_FAST_THRESHOLD);
        var results = new ArrayList<SummaryReportItem>();
        if (daily) {
            while (from.truncatedTo(ChronoUnit.DAYS).isBefore(to.truncatedTo(ChronoUnit.DAYS))) {
                ZonedDateTime fromDay = from.truncatedTo(ChronoUnit.DAYS);
                ZonedDateTime nextDay = fromDay.plusDays(1);
                results.addAll(calculateDeviceResult(
                        device, Date.from(from.toInstant()), Date.from(nextDay.toInstant()), fast));
                from = nextDay;
            }
        }
        results.addAll(calculateDeviceResult(device, Date.from(from.toInstant()), Date.from(to.toInstant()), fast));
        return results;
    }

    public Collection<SummaryReportItem> getObjects(
            long userId, Collection<Long> deviceIds, Collection<Long> groupIds,
            Date from, Date to, boolean daily) throws StorageException {
        reportUtils.checkPeriodLimit(from, to);

        var tz = UserUtil.getTimezone(permissionsService.getServer(), permissionsService.getUser(userId)).toZoneId();

        ArrayList<SummaryReportItem> result = new ArrayList<>();
        for (Device device : DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, groupIds)) {
            var deviceResults = calculateDeviceResults(
                    device, from.toInstant().atZone(tz), to.toInstant().atZone(tz), daily);
            for (SummaryReportItem summaryReport : deviceResults) {
                if (summaryReport.getStartTime() != null && summaryReport.getEndTime() != null) {
                    result.add(summaryReport);
                }
            }
        }
        return result;
    }

    public Map<String, Object> getLast24HDeviceReport(Long deviceId, Position currentPosition) throws StorageException {
        // Get the device
        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(),
                new Condition.Equals("id", deviceId)));
        if (device == null) {
            return null;
        }

        // Use India Standard Time (IST) timezone
        java.util.TimeZone istTimeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata");

        // Calculate time range from 12:00 AM today IST to current time IST
        Date to = new Date(); // Current time

        // Get start of today (12:00 AM) in IST
        java.util.Calendar cal = java.util.Calendar.getInstance(istTimeZone);
        cal.setTime(to);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        Date from = cal.getTime(); // 12:00 AM today IST

        return getDeviceReportForDateRange(deviceId, currentPosition, from, to);
    }

    /**
     * Get comprehensive device report for custom date range
     * 
     * @param deviceId        Device ID to get report for
     * @param currentPosition Current position of the device (can be null)
     * @param from            Start date/time for the report
     * @param to              End date/time for the report
     * @return Map containing comprehensive device report data
     * @throws StorageException if database error occurs
     */
    public Map<String, Object> getDeviceReportForDateRange(Long deviceId, Position currentPosition, Date from, Date to)
            throws StorageException {
        // Get the device
        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(),
                new Condition.Equals("id", deviceId)));
        if (device == null) {
            return null;
        }

        // Validate date range
        if (from == null || to == null) {
            throw new IllegalArgumentException("From and To dates cannot be null");
        }

        if (from.after(to)) {
            throw new IllegalArgumentException("From date cannot be after To date");
        }

        // Use fast calculation for the period
        boolean fast = true;

        // Calculate device result for the specified date range
        Collection<SummaryReportItem> results = calculateDeviceResult(device, from, to, fast);
        List<TripReportItem> tripReport = reportUtils.detectTripsAndStops(device, from, to, TripReportItem.class);
        List<StopReportItem> stopReport = reportUtils.detectTripsAndStops(device, from, to, StopReportItem.class);

        // Calculate comprehensive timing data in milliseconds
        long totalRunningTime = 0;
        long totalStopTime = 0;
        double totalTripDistance = 0;
        double maxTripSpeed = 0;
        double totalFuelConsumed = 0;

        // Analyze trip data
        for (TripReportItem trip : tripReport) {
            totalRunningTime += trip.getDuration();
            totalTripDistance += trip.getDistance();
            totalFuelConsumed += trip.getSpentFuel();
            if (trip.getMaxSpeed() > maxTripSpeed) {
                maxTripSpeed = trip.getMaxSpeed();
            }
        }

        // Analyze stop data
        for (StopReportItem stop : stopReport) {
            totalStopTime += stop.getDuration();
            totalFuelConsumed += stop.getSpentFuel(); // Fuel consumed during stops (idling)
        }

        // Calculate comprehensive metrics
        long totalPeriod = to.getTime() - from.getTime(); // Time period in milliseconds
        long totalIdleTime = 0; // Initialize idle time as 0

        // Only calculate idle time if there was actual activity (trips or stops)
        if (totalRunningTime > 0 || totalStopTime > 0) {
            totalIdleTime = totalPeriod - totalRunningTime - totalStopTime;
            // Ensure idle time is not negative
            if (totalIdleTime < 0) {
                totalIdleTime = 0;
            }
        }

        // If no activity at all, the entire period should be considered as stopped
        // time, not idle
        long totalStoppedTime = 0;
        if (totalRunningTime == 0 && totalStopTime == 0) {
            // No activity detected - entire period is stopped time
            totalStoppedTime = totalPeriod;
            totalIdleTime = 0;
        } else {
            // Some activity detected - calculate remaining stopped time
            totalStoppedTime = totalPeriod - totalRunningTime - totalIdleTime;
            if (totalStoppedTime < 0) {
                totalStoppedTime = 0;
            }
        }

        // Calculate percentages
        double runningPercentage = totalPeriod > 0 ? (totalRunningTime * 100.0) / totalPeriod : 0;
        double stopPercentage = totalPeriod > 0 ? (totalStopTime * 100.0) / totalPeriod : 0;
        double idlePercentage = totalPeriod > 0 ? (totalIdleTime * 100.0) / totalPeriod : 0;
        double stoppedPercentage = totalPeriod > 0 ? (totalStoppedTime * 100.0) / totalPeriod : 0;

        // Calculate averages
        double averageTripSpeed = totalRunningTime > 0 ? (totalTripDistance * 3600000.0) / totalRunningTime : 0; // km/h

        // Create enhanced summary data structure
        Map<String, Object> enhancedSummary = new HashMap<>();

        // Basic device information
        enhancedSummary.put("deviceId", device.getId());
        enhancedSummary.put("deviceName", device.getName());
        enhancedSummary.put("reportFrom", from);
        enhancedSummary.put("reportTo", to);
        enhancedSummary.put("reportDurationMs", totalPeriod);
        enhancedSummary.put("reportDurationHours", Math.round((totalPeriod / 3600000.0) * 100.0) / 100.0);
        enhancedSummary.put("reportDurationDays", Math.round((totalPeriod / 86400000.0) * 100.0) / 100.0);

        // Timing breakdown (all in milliseconds for consistency)
        enhancedSummary.put("totalRunningTimeMs", totalRunningTime);
        enhancedSummary.put("totalStopTimeMs", totalStopTime);
        enhancedSummary.put("totalIdleTimeMs", totalIdleTime);
        enhancedSummary.put("totalStoppedTimeMs", totalStoppedTime);
        enhancedSummary.put("totalPeriodMs", totalPeriod);

        // Also provide hours for convenience
        enhancedSummary.put("totalRunningHours", Math.round((totalRunningTime / 1000.0 / 3600.0) * 100.0) / 100.0);
        enhancedSummary.put("totalStopHours", Math.round((totalStopTime / 1000.0 / 3600.0) * 100.0) / 100.0);
        enhancedSummary.put("totalIdleHours", Math.round((totalIdleTime / 1000.0 / 3600.0) * 100.0) / 100.0);
        enhancedSummary.put("totalStoppedHours", Math.round((totalStoppedTime / 1000.0 / 3600.0) * 100.0) / 100.0);

        // Percentage breakdown
        enhancedSummary.put("runningPercentage", Math.round(runningPercentage * 100.0) / 100.0);
        enhancedSummary.put("stopPercentage", Math.round(stopPercentage * 100.0) / 100.0);
        enhancedSummary.put("idlePercentage", Math.round(idlePercentage * 100.0) / 100.0);
        enhancedSummary.put("stoppedPercentage", Math.round(stoppedPercentage * 100.0) / 100.0);

        // Trip and Stop counts
        enhancedSummary.put("totalTrips", tripReport.size());
        enhancedSummary.put("totalStops", stopReport.size());

        // Distance and Speed metrics (key metrics only)
        enhancedSummary.put("totalDistanceTraveled", Math.round(totalTripDistance * 100.0) / 100.0);
        enhancedSummary.put("maxSpeedAchieved", Math.round(maxTripSpeed * 100.0) / 100.0);
        enhancedSummary.put("averageTripSpeed", Math.round(averageTripSpeed * 100.0) / 100.0);

        // Fuel consumption (essential)
        enhancedSummary.put("totalFuelConsumed", Math.round(totalFuelConsumed * 100.0) / 100.0);

        // Activity patterns (essential only)
        enhancedSummary.put("activityLevel",
                totalRunningTime > 0 ? "Active" : (totalStopTime > 0 ? "Stationary" : "No Data"));

        // Calculate daily averages if period is more than 24 hours
        long dayCount = Math.max(1, totalPeriod / 86400000); // Minimum 1 day
        enhancedSummary.put("averageTripsPerDay", Math.round((tripReport.size() / (double) dayCount) * 100.0) / 100.0);
        enhancedSummary.put("averageDistancePerDay", Math.round((totalTripDistance / dayCount) * 100.0) / 100.0);
        enhancedSummary.put("averageRunningHoursPerDay",
                Math.round(((totalRunningTime / 3600000.0) / dayCount) * 100.0) / 100.0);

        // Extract ignition on/off data efficiently from trip and stop reports
        Date firstIgnitionOn = null;
        Date lastIgnitionOn = null;
        Date lastIgnitionOff = null;
        long totalIgnitionOnTimeMs = 0;
        long totalIgnitionOffTimeMs = 0;
        int ignitionOnCount = 0;
        int ignitionOffCount = 0;

        // Calculate proper engine on/off time from trips and stops
        if (!tripReport.isEmpty() && !stopReport.isEmpty()) {
            firstIgnitionOn = tripReport.get(0).getStartTime();
            lastIgnitionOn = tripReport.get(tripReport.size() - 1).getStartTime();
            lastIgnitionOff = stopReport.get(stopReport.size() - 1).getStartTime();

            totalIgnitionOnTimeMs = totalRunningTime;
            totalIgnitionOffTimeMs = totalStopTime + totalStoppedTime;

            ignitionOnCount = tripReport.size();
            ignitionOffCount = stopReport.size();

        } else if (!tripReport.isEmpty() && stopReport.isEmpty()) {
            firstIgnitionOn = tripReport.get(0).getStartTime();
            lastIgnitionOn = tripReport.get(tripReport.size() - 1).getStartTime();

            totalIgnitionOnTimeMs = totalRunningTime;
            totalIgnitionOffTimeMs = totalIdleTime + totalStoppedTime;

            ignitionOnCount = tripReport.size();
            ignitionOffCount = 0;

        } else if (tripReport.isEmpty() && !stopReport.isEmpty()) {
            lastIgnitionOff = stopReport.get(stopReport.size() - 1).getStartTime();

            totalIgnitionOnTimeMs = 0;
            totalIgnitionOffTimeMs = totalPeriod;

            ignitionOnCount = 0;
            ignitionOffCount = stopReport.size();

        } else {
            totalIgnitionOnTimeMs = 0;
            totalIgnitionOffTimeMs = totalPeriod;

            ignitionOnCount = 0;
            ignitionOffCount = 0;
        }

        // Calculate time since last ignition events (in milliseconds)
        Date now = new Date();
        Long msSinceLastIgnitionOn = null;
        Long msSinceLastIgnitionOff = null;

        if (lastIgnitionOn != null) {
            msSinceLastIgnitionOn = now.getTime() - lastIgnitionOn.getTime();
        }
        if (lastIgnitionOff != null) {
            msSinceLastIgnitionOff = now.getTime() - lastIgnitionOff.getTime();
        }

        // Add ignition data
        enhancedSummary.put("firstIgnitionOn", firstIgnitionOn);
        enhancedSummary.put("lastIgnitionOn", lastIgnitionOn);
        enhancedSummary.put("lastIgnitionOff", lastIgnitionOff);
        enhancedSummary.put("totalIgnitionOnTimeMs", totalIgnitionOnTimeMs);
        enhancedSummary.put("totalIgnitionOffTimeMs", totalIgnitionOffTimeMs);
        enhancedSummary.put("ignitionOnCount", ignitionOnCount);
        enhancedSummary.put("ignitionOffCount", ignitionOffCount);
        enhancedSummary.put("msSinceLastIgnitionOn", msSinceLastIgnitionOn);
        enhancedSummary.put("msSinceLastIgnitionOff", msSinceLastIgnitionOff);

        // Calculate distance from last stop
        double distanceFromLastStop = 0.0;
        Date lastStopTime = null;
        double lastStopLat = 0.0;
        double lastStopLon = 0.0;

        if (!stopReport.isEmpty()) {
            StopReportItem lastStop = stopReport.get(stopReport.size() - 1);
            lastStopTime = lastStop.getEndTime();
            lastStopLat = lastStop.getLatitude();
            lastStopLon = lastStop.getLongitude();

            if (currentPosition != null) {
                distanceFromLastStop = calculateHaversineDistance(
                        lastStopLat, lastStopLon,
                        currentPosition.getLatitude(), currentPosition.getLongitude());
            }
        }

        enhancedSummary.put("distanceFromLastStop", Math.round(distanceFromLastStop * 100.0) / 100.0);
        enhancedSummary.put("lastStopTime", lastStopTime);

        // Determine current state
        String currentState = "Unknown";
        double currentSpeed = 0.0;
        boolean engineOn = false;

        if (currentPosition != null) {
            currentState = deviceCurrentStatus(currentPosition);
            currentSpeed = currentPosition.getSpeed();
            engineOn = currentPosition.hasAttribute(Position.KEY_IGNITION)
                    && currentPosition.getBoolean(Position.KEY_IGNITION);
        }

        enhancedSummary.put("currentState", currentState);
        enhancedSummary.put("currentSpeed", Math.round(currentSpeed * 100.0) / 100.0);
        enhancedSummary.put("engineOn", engineOn);

        // Add essential original summary data
        if (!results.isEmpty()) {
            SummaryReportItem summary = results.iterator().next();
            enhancedSummary.put("engineHours", summary.getEngineHours());
            enhancedSummary.put("startOdometer", summary.getStartOdometer());
            enhancedSummary.put("endOdometer", summary.getEndOdometer());
        }

        return enhancedSummary;
    }

    public void getExcel(OutputStream outputStream,
            long userId, Collection<Long> deviceIds, Collection<Long> groupIds,
            Date from, Date to, boolean daily) throws StorageException, IOException {
        Collection<SummaryReportItem> summaries = getObjects(userId, deviceIds, groupIds, from, to, daily);

        File file = Paths.get(config.getString(Keys.TEMPLATES_ROOT), "export", "summary.xlsx").toFile();
        try (InputStream inputStream = new FileInputStream(file)) {
            var context = reportUtils.initializeContext(userId);
            context.putVar("summaries", summaries);
            context.putVar("from", from);
            context.putVar("to", to);
            JxlsHelper.getInstance().setUseFastFormulaProcessor(false)
                    .processTemplate(inputStream, outputStream, context);
        }
    }

    /**
     * Determine the current status of a device based on its position data
     * 
     * @param currentPosition The current position of the device
     * @return String representing the current status: "Running", "Idle", "Stopped",
     *         or "Unknown"
     */
    public String deviceCurrentStatus(Position currentPosition) {
        if (currentPosition == null) {
            return Device.STATUS_UNKNOWN;
        }

        double currentSpeed = currentPosition.getSpeed();
        boolean engineOn = false;
        String currentState = Device.STATUS_UNKNOWN;

        // Check if ignition status is available
        if (currentPosition.hasAttribute(Position.KEY_IGNITION)) {
            engineOn = currentPosition.getBoolean(Position.KEY_IGNITION);
        }

        if (currentSpeed > 5) { // Speed greater than 5 km/h
            currentState = Device.STATUS_RUNNING;
        } else if (engineOn && currentSpeed <= 5) { // Engine on but not moving
            currentState = Device.STATUS_IDLE;
        } else if (!engineOn) { // Engine off
            currentState = Device.STATUS_STOPPED;
        } else {
            currentState = Device.STATUS_UNKNOWN;
        }

        return currentState;
    }

    public List<StopReportItem> getDeviceStopReports(long deviceId, Date from, Date to) throws StorageException {
        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(),
                new Condition.Equals("id", deviceId)));
        List<StopReportItem> stopReport = reportUtils.detectTripsAndStops(device, from, to, StopReportItem.class);
        return stopReport;
    }

}
