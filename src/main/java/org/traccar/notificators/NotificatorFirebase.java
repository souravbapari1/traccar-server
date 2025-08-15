/*
 * Copyright 2018 - 2025 Anton Tananaev (anton@traccar.org)
 * Copyright 2018 Andrey Kunitsyn (andrey@traccar.org)
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
package org.traccar.notificators;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Event;
import org.traccar.model.ObjectOperation;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.notification.MessageException;
import org.traccar.notification.NotificationFormatter;
import org.traccar.notification.NotificationMessage;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

@Singleton
public class NotificatorFirebase extends Notificator {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificatorFirebase.class);

    private final Storage storage;
    private final CacheManager cacheManager;
    private final FirebaseMessaging firebaseMessaging;

    @Inject
    public NotificatorFirebase(
            Config config, NotificationFormatter notificationFormatter,
            Storage storage, CacheManager cacheManager) throws IOException {
        super(notificationFormatter);
        this.storage = storage;
        this.cacheManager = cacheManager;

        InputStream serviceAccount = new ByteArrayInputStream(
                config.getString(Keys.NOTIFICATOR_FIREBASE_SERVICE_ACCOUNT).getBytes());

        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build();

        firebaseMessaging = FirebaseMessaging.getInstance(
                FirebaseApp.initializeApp(options, "manager"));
    }

    private String getCustomSoundForEvent(Event event) {
        if (event == null) {
            return "default";
        }

        // Map event types to custom sounds
        switch (event.getType()) {
            case Event.TYPE_DEVICE_ONLINE:
                return "device_online";
            case Event.TYPE_DEVICE_OFFLINE:
                return "device_offline";
            case Event.TYPE_DEVICE_STOPPED:
                return "device_stopped";
            case Event.TYPE_DEVICE_MOVING:
                return "device_moving";
            case Event.TYPE_DEVICE_OVERSPEED:
                return "device_overspeed";
            case Event.TYPE_GEOFENCE_ENTER:
                return "geofence_enter";
            case Event.TYPE_GEOFENCE_EXIT:
                return "geofence_exit";
            case Event.TYPE_ALARM:
                return "alarm_critical";
            case Event.TYPE_IGNITION_ON:
                return "ignition_on";
            case Event.TYPE_IGNITION_OFF:
                return "ignition_off";
            case Event.TYPE_MAINTENANCE:
                return "maintenance";
            case Event.TYPE_DRIVER_CHANGED:
                return "driver_changed";
            case Event.TYPE_MEDIA:
                return "media";
            case Event.TYPE_PARKING_MODE_ON:
                return "parking_mode_on";
            case Event.TYPE_PARKING_MODE_OFF:
                return "parking_mode_off";
            case Event.TYPE_PARKING_MODE_ALERT:
                return "parking_mode_exit";
            default:
                return "default";
        }
    }

    @Override
    public void send(User user, NotificationMessage message, Event event, Position position) throws MessageException {
        if (user.hasAttribute("notificationTokens")) {

            List<String> registrationTokens = new ArrayList<>(
                    Arrays.asList(user.getString("notificationTokens").split("[, ]")));

            // Determine sound based on event type
            System.out.println("Event type: " + (event != null ? event.getType() : "null"));
            String soundName = getCustomSoundForEvent(event);

            var androidConfig = AndroidConfig.builder()
                    .setNotification(AndroidNotification.builder().setSound(soundName).build());

            var apnsConfig = ApnsConfig.builder()
                    .setAps(Aps.builder().setSound(soundName).build());

            if (message.priority()) {
                androidConfig.setPriority(AndroidConfig.Priority.HIGH);
                apnsConfig.putHeader("apns-priority", "10");
            }

            String body = message.digest();

            if (position != null) {
                body = body + "\n" + position.getAddress();
            }

            var messageBuilder = MulticastMessage.builder()
                    .setNotification(com.google.firebase.messaging.Notification.builder()
                            .setTitle(message.subject())
                            .setBody(body)
                            .build())
                    .setAndroidConfig(androidConfig.build())
                    .setApnsConfig(apnsConfig.build())
                    .addAllTokens(registrationTokens);

            if (event != null) {
                messageBuilder.putData("eventId", String.valueOf(event.getId()));
                messageBuilder.putData("eventType", event.getType());
            }

            if (position != null) {
                messageBuilder.putData("address", position.getAddress());
                messageBuilder.putData("latitude", String.valueOf(position.getLatitude()));
                messageBuilder.putData("longitude", String.valueOf(position.getLongitude()));
                messageBuilder.putData("speed", String.valueOf(position.getSpeed()));
                messageBuilder.putData("deviceId", String.valueOf(position.getDeviceId()));
            }

            try {
                var result = firebaseMessaging.sendEachForMulticast(messageBuilder.build());
                List<String> failedTokens = new LinkedList<>();
                var iterator = result.getResponses().listIterator();
                while (iterator.hasNext()) {
                    int index = iterator.nextIndex();
                    var response = iterator.next();
                    if (!response.isSuccessful()) {
                        MessagingErrorCode error = response.getException().getMessagingErrorCode();
                        if (error == MessagingErrorCode.INVALID_ARGUMENT || error == MessagingErrorCode.UNREGISTERED) {
                            failedTokens.add(registrationTokens.get(index));
                        }
                        LOGGER.warn("Firebase user {} error", user.getId(), response.getException());
                    }
                }
                if (!failedTokens.isEmpty()) {
                    registrationTokens.removeAll(failedTokens);
                    if (registrationTokens.isEmpty()) {
                        user.removeAttribute("notificationTokens");
                    } else {
                        user.set("notificationTokens", String.join(",", registrationTokens));
                    }
                    storage.updateObject(user, new Request(
                            new Columns.Include("attributes"),
                            new Condition.Equals("id", user.getId())));
                    cacheManager.invalidateObject(true, User.class, user.getId(), ObjectOperation.UPDATE);
                }
            } catch (Exception e) {
                LOGGER.warn("Firebase error", e);
            }
        }
    }

}
