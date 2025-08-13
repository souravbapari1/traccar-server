/*
 * Copyright 2016 - 2024 Anton Tananaev (anton@traccar.org)
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
package org.traccar.handler.events;

import java.util.concurrent.ConcurrentHashMap;

import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import jakarta.inject.Inject;

public class IgnitionEventHandler extends BaseEventHandler {

    private final CacheManager cacheManager;
    private final long debounceTime;
    
    // Track last ignition event times per device to implement debouncing
    private final ConcurrentHashMap<Long, Long> lastIgnitionEventTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> lastIgnitionState = new ConcurrentHashMap<>();

    @Inject
    public IgnitionEventHandler(Config config, CacheManager cacheManager) {
        this.cacheManager = cacheManager;
        this.debounceTime = config.getLong(Keys.EVENT_IGNITION_DEBOUNCE_TIME);
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        Device device = cacheManager.getObject(Device.class, position.getDeviceId());
        if (device == null || !PositionUtil.isLatest(cacheManager, position)) {
            return;
        }


        


        if (position.hasAttribute(Position.KEY_IGNITION)) {
            boolean ignition = position.getBoolean(Position.KEY_IGNITION);
            long deviceId = position.getDeviceId();
            long currentTime = position.getFixTime().getTime();

            Position lastPosition = cacheManager.getPosition(deviceId);
            if (lastPosition != null && lastPosition.hasAttribute(Position.KEY_IGNITION)) {
                boolean oldIgnition = lastPosition.getBoolean(Position.KEY_IGNITION);

                // Check if ignition state has changed
                if (ignition != oldIgnition) {
                    // Get the last event time and state for this device
                    Long lastEventTime = lastIgnitionEventTime.get(deviceId);
                    Boolean lastEventState = lastIgnitionState.get(deviceId);
                    
                    boolean shouldGenerateEvent = true;
                    
                    // Apply debouncing logic
                    if (lastEventTime != null && lastEventState != null) {
                        long timeSinceLastEvent = currentTime - lastEventTime;
                        
                        // If not enough time has passed since last event, check if we should suppress
                        if (timeSinceLastEvent < debounceTime) {
                            // Suppress event if it's the same state as the last generated event
                            // or if it's rapidly switching back and forth
                            if (ignition == lastEventState) {
                                shouldGenerateEvent = false;
                            }
                        }
                    }
                    
                    if (shouldGenerateEvent) {
                        // Update tracking data
                        lastIgnitionEventTime.put(deviceId, currentTime);
                        lastIgnitionState.put(deviceId, ignition);
                        
                        // Generate the appropriate event
                        if (ignition) {
                            callback.eventDetected(new Event(Event.TYPE_IGNITION_ON, position));
                        } else {
                            callback.eventDetected(new Event(Event.TYPE_IGNITION_OFF, position));
                        }
                    }
                } else {
                    // No state change - update timestamp but keep same state
                    // This helps with debouncing by extending the time window
                    Boolean lastEventState = lastIgnitionState.get(deviceId);
                    if (lastEventState != null && lastEventState == ignition) {
                        lastIgnitionEventTime.put(deviceId, currentTime);
                    }
                }
            }
        }
    }

}
