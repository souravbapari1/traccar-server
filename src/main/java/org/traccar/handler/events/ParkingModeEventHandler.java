/*
 * Copyright 2016 - 2024 Anton Tananaev (anton@traccar.org)
 * Copyright 2017 Andrey Kunitsyn (andrey@traccar.org)
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
package org.traccar.handler.events;

import org.traccar.config.Keys;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;

import jakarta.inject.Inject;

public class ParkingModeEventHandler extends BaseEventHandler {

    private final CacheManager cacheManager;
    private final Storage storage;

    @Inject
    public ParkingModeEventHandler(CacheManager cacheManager, Storage storage) {
        this.cacheManager = cacheManager;
        this.storage = storage;
    }

    @Override
    public void onPosition(Position position, Callback callback) {

        long deviceId = position.getDeviceId();
        Device device = cacheManager.getObject(Device.class, deviceId);
        if (device == null || !PositionUtil.isLatest(cacheManager, position)) {
            return;
        }
        boolean processInvalid = AttributeUtil.lookup(
                cacheManager, Keys.EVENT_MOTION_PROCESS_INVALID_POSITIONS, deviceId);
        if (!processInvalid && !position.getValid()) {
            return;
        }

        // Check if parking mode is enabled for this device
        boolean parkingModeEnabled = device.getParkingMode();

        if (!parkingModeEnabled) {
            return;
        }

        Position lastPosition = cacheManager.getPosition(deviceId);
        if (lastPosition == null) {
            return;
        }

        // Get parking mode sensitivity threshold (default 0.5 km/h)
        double parkingModeThreshold = 0.1;
        // Check for unauthorized movement while in parking mode
        boolean currentlyParked = !position.getBoolean(Position.KEY_MOTION)
                && position.getSpeed() <= parkingModeThreshold;
        boolean wasParked = !lastPosition.getBoolean(Position.KEY_MOTION)
                && lastPosition.getSpeed() <= parkingModeThreshold;

        // Check for parking alarm in the position
        String alarm = position.getString(Position.KEY_ALARM);
        if (alarm != null && alarm.contains(Position.ALARM_PARKING)) {
            Event event = new Event(Event.TYPE_PARKING_MODE_ALERT, position);
            event.set(Position.KEY_ALARM, Position.ALARM_PARKING);
            event.set("message", "Parking mode alert detected");
            callback.eventDetected(event);
            return;
        } else if (wasParked && !currentlyParked) {
            // Check if the movement is significant enough to trigger an alert
            double speedDifference = position.getSpeed() - lastPosition.getSpeed();
            long timeDifference = position.getFixTime().getTime() - lastPosition.getFixTime().getTime();

            // Generate alert if there's sudden movement after being parked
            if (speedDifference > parkingModeThreshold && timeDifference < 60000) { // Within 1 minute
                Event event = new Event(Event.TYPE_PARKING_MODE_ALERT, position);
                event.set("previousSpeed", lastPosition.getSpeed());
                event.set("currentSpeed", position.getSpeed());
                event.set("speedDifference", speedDifference);
                event.set("message", "Unauthorized movement detected while in parking mode");
                callback.eventDetected(event);
            }
        } else if (position.hasAttribute(Position.KEY_IGNITION) && lastPosition.hasAttribute(Position.KEY_IGNITION)) {
            boolean currentIgnition = position.getBoolean(Position.KEY_IGNITION);
            boolean previousIgnition = lastPosition.getBoolean(Position.KEY_IGNITION);

            // If ignition turns on while in parking mode
            if (!previousIgnition && currentIgnition && wasParked) {
                Event event = new Event(Event.TYPE_PARKING_MODE_ALERT, position);
                event.set("ignitionChange", true);
                event.set("message", "Ignition activated while in parking mode");
                callback.eventDetected(event);
            }
        }
    }

}
