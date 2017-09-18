package com.appdynamics.extensions.logmonitor.customEvents;

import com.appdynamics.extensions.logmonitor.config.ControllerInfo;
import com.appdynamics.extensions.logmonitor.config.EventParameters;
import com.google.common.collect.Lists;
import org.apache.http.client.utils.URIBuilder;

import java.net.URL;
import java.util.List;

import static com.appdynamics.extensions.logmonitor.Constants.CONTROLLER_EVENTS_ENDPOINT;
import static com.appdynamics.extensions.logmonitor.Constants.EVENT_TYPE;

/**
 * Created by aditya.jagtiani on 9/15/17.
 */

public class CustomEventBuilder {

    public static List<URL> eventsToBePosted = Lists.newArrayList();

    public static void createEvent(ControllerInfo controllerInfo, EventParameters eventParameters, String propertyName, String propertyValue) throws Exception {
        URIBuilder builder = new URIBuilder();
        builder.setScheme("http").setHost(controllerInfo.getHost()).setPort(controllerInfo.getPort())
                .setPath(CONTROLLER_EVENTS_ENDPOINT + eventParameters.getApplicationID() + "/events")
                .setParameter("severity", eventParameters.getSeverity())
                .setParameter("summary", "Match Found in " + propertyName + " : " + propertyValue)
                .setParameter("eventtype", EVENT_TYPE)
                .setParameter("customeventtype", eventParameters.getCustomEventType());
        eventsToBePosted.add(builder.build().toURL());
        ;
    }
}