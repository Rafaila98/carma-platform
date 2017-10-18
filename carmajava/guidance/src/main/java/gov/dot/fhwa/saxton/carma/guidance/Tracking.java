/*
 * TODO: Copyright (C) 2017 LEIDOS.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

//TODO: Naming convention of "package gov.dot.fhwa.saxton.carmajava.<template>;"
//Originally "com.github.rosjava.carmajava.template;"
package gov.dot.fhwa.saxton.carma.guidance;

import cav_msgs.BSM;
import cav_msgs.BSMCoreData;
import cav_msgs.HeadingStamped;
import cav_msgs.SystemAlert;
import cav_srvs.GetDriversWithCapabilities;
import cav_srvs.GetDriversWithCapabilitiesRequest;
import cav_srvs.GetDriversWithCapabilitiesResponse;
import geometry_msgs.TwistStamped;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.IPubSubService;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.IPublisher;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.IService;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.ISubscriber;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.OnMessageCallback;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.OnServiceResponseCallback;

import org.jboss.netty.buffer.ChannelBuffers;
import org.ros.exception.RosRuntimeException;
import org.ros.node.ConnectedNode;
import org.ros.node.parameter.ParameterTree;

import sensor_msgs.NavSatFix;
import std_msgs.Float64;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Guidance package Tracking component
 * <p>
 * Reponsible for detecting when the vehicle strays from it's intended route or
 * trajectory and signalling the failure on the /system_alert topic
 */
public class Tracking extends GuidanceComponent {
    // Member variables
    protected final long sleepDurationMillis = 5000; // Not the real frequency for J2735
    protected int msgCount = 0;
    private float vehicleWidth = 0;
    private float vehicleLength = 0;
    private boolean drivers_ready = false;
    private boolean steer_wheel_ready = false;
    private boolean nav_sat_fix_ready = false;
    private boolean heading_ready = false;
    private boolean velocity_ready = false;
    Random randomIdGenerator = new Random();
    private byte[] random_id = new byte[4];
    private IPublisher<SystemAlert> statusPublisher;
    private IPublisher<BSM> bsmPublisher;
    private ISubscriber<NavSatFix> navSatFixSubscriber;
    private ISubscriber<HeadingStamped> headingStampedSubscriber;
    private ISubscriber<TwistStamped> velocitySubscriber;
    private ISubscriber<SystemAlert> statusSubscriber;
    private ISubscriber<Float64> steeringWheelSubscriber;
    private IService<GetDriversWithCapabilitiesRequest, GetDriversWithCapabilitiesResponse> getDriversService = null;
    private List<String> req_drivers = Arrays.asList("steering_wheel_angle");
    private List<String> resp_drivers;
    
    public Tracking(AtomicReference<GuidanceState> state, IPubSubService pubSubService, ConnectedNode node) {
        super(state, pubSubService, node);
    }

    @Override public String getComponentName() {
        return "Guidance.Tracking";
    }

    @Override public void onGuidanceStartup() {
    	//Publishers
        statusPublisher = pubSubService.getPublisherForTopic("system_alert", cav_msgs.SystemAlert._TYPE);
        bsmPublisher = pubSubService.getPublisherForTopic("bsm", BSM._TYPE);

        //Subscribers
        statusSubscriber = pubSubService.getSubscriberForTopic("system_alert", SystemAlert._TYPE);
        statusSubscriber.registerOnMessageCallback(new OnMessageCallback<SystemAlert>() {
        	@Override
        	public void onMessage(SystemAlert msg) {
        		if(msg.getType() == SystemAlert.DRIVERS_READY) {
        			drivers_ready = true;
        		}
            }
		});

        navSatFixSubscriber = pubSubService.getSubscriberForTopic("/saxton_cav/vehicle_environment/sensor_fusion/filtered/nav_sat_fix", NavSatFix._TYPE);
        navSatFixSubscriber.registerOnMessageCallback(new OnMessageCallback<NavSatFix>() {
            @Override public void onMessage(NavSatFix msg) {;
                nav_sat_fix_ready = true;
            }
        });

        headingStampedSubscriber = pubSubService.getSubscriberForTopic("/saxton_cav/vehicle_environment/sensor_fusion/filtered/heading", HeadingStamped._TYPE);
        headingStampedSubscriber.registerOnMessageCallback(new OnMessageCallback<HeadingStamped>() {
            @Override public void onMessage(HeadingStamped msg) {
                heading_ready = true;
            }
        });
        
        velocitySubscriber = pubSubService.getSubscriberForTopic("/saxton_cav/vehicle_environment/sensor_fusion/filtered/velocity", TwistStamped._TYPE);
        velocitySubscriber.registerOnMessageCallback(new OnMessageCallback<TwistStamped>() {
            @Override public void onMessage(TwistStamped msg) {
                velocity_ready = true;
            }
        });
        
        //Make service call to get drivers
        try {
        	//Use this only because drivers_ready is not working correctly
        	Thread.sleep(20000);
        	while(getDriversService == null) {
            	if(drivers_ready) {
            		getDriversService = pubSubService.getServiceForTopic("get_drivers_with_capabilities", GetDriversWithCapabilities._TYPE);
            	}
            	Thread.sleep(1000);
            }
        	while(resp_drivers == null || resp_drivers.size() == 0) {
        		GetDriversWithCapabilitiesRequest driver_request_wrapper = getDriversService.newMessage();
    			driver_request_wrapper.setCapabilities(req_drivers);
    			getDriversService.callSync(driver_request_wrapper, new OnServiceResponseCallback<GetDriversWithCapabilitiesResponse>() {
    				@Override
    				public void onSuccess(GetDriversWithCapabilitiesResponse msg) {
    					resp_drivers = msg.getDriverData();
    					log.info("Tracking: service call is successful: " + resp_drivers);
    				}
    				@Override
    				public void onFailure(Exception e) {
    					throw new RosRuntimeException(e);
    				}
    			});
    			Thread.sleep(1000);
        	}
		} catch (Exception e) {
			handleException(e);
		}
        
        steeringWheelSubscriber = pubSubService.getSubscriberForTopic(resp_drivers.get(0), Float64._TYPE);
        steeringWheelSubscriber.registerOnMessageCallback(new OnMessageCallback<Float64>() {
        	@Override
        	public void onMessage(Float64 msg) {
        		steer_wheel_ready = true;
            }
		});
    }

    @Override public void onSystemReady() {
        // NO-OP
    }

    @Override public void onGuidanceEnable() {

    }

    @Override public void loop() {
    	try {
    		cav_msgs.SystemAlert systemAlertMsg = statusPublisher.newMessage();
            systemAlertMsg.setDescription("Tracking has not detected a running trajectory, no means to compute crosstrack error");
            systemAlertMsg.setType(SystemAlert.CAUTION);
    		if(nav_sat_fix_ready && steer_wheel_ready && heading_ready && velocity_ready) {
    			log.info("Guidance.Tracking is publishing bsm...");
                statusPublisher.publish(systemAlertMsg);
                bsmPublisher.publish(composeBSMData());   
        	}
            Thread.sleep(sleepDurationMillis);
        } catch (Exception e) {
        	handleException(e);
        }
    }
    
    private BSM composeBSMData() {
    	
    	BSM bsmFrame = bsmPublisher.newMessage();
    	
    	try {
    		
    		if(msgCount == 0) {
        		randomIdGenerator.nextBytes(random_id);
        	}

        	//Set header
        	bsmFrame.getHeader().setStamp(node.getCurrentTime());
        	bsmFrame.getHeader().setFrameId("MessageNode");
        	
        	//Set core data
        	BSMCoreData coreData = bsmFrame.getCoreData();
            coreData.setMsgCount((byte) (msgCount++ % 127));
            
            //ID is random and changes every 5 minutes
            if(msgCount == 3000) {
            	msgCount = 0;
            }
            coreData.setId(ChannelBuffers.copiedBuffer(ByteOrder.LITTLE_ENDIAN, random_id));
            
            //Use ros node time
            coreData.setSecMark((short) (node.getCurrentTime().toSeconds() % 65535));
            
            //Set GPS data
            coreData.setLatitude(navSatFixSubscriber.getLastMessage().getLatitude());
            coreData.setLongitude(navSatFixSubscriber.getLastMessage().getLongitude());
            coreData.setElev((float) navSatFixSubscriber.getLastMessage().getAltitude());
            
            //N/A for now
            coreData.getAccuracy().setSemiMajor((float) (255 * 0.05));
            coreData.getAccuracy().setSemiMinor((float) (255 * 0.05));
            coreData.getAccuracy().setOrientation(65535 * 0.0054932479);
            coreData.getTransmission().setTransmissionState((byte) 7);
            
            coreData.setSpeed((float) velocitySubscriber.getLastMessage().getTwist().getLinear().getX());
            coreData.setHeading((float) (headingStampedSubscriber.getLastMessage().getHeading()));
            coreData.setAngle((float) steeringWheelSubscriber.getLastMessage().getData());
            
            //N/A for now
            coreData.getAccelSet().setLongitude((float) (2001 * 0.01));
            coreData.getAccelSet().setLatitude((float) (2001 * 0.01));
            coreData.getAccelSet().setVert((float) (-127 * 0.02));
            coreData.getAccelSet().setYaw(0);
            coreData.getBrakes().getWheelBrakes().setBrakeAppliedStatus((byte) 10);
            coreData.getBrakes().getTraction().setTractionControlStatus((byte) 0);
            coreData.getBrakes().getAbs().setAntiLockBrakeStatus((byte) 0);
            coreData.getBrakes().getScs().setStabilityControlStatus((byte) 0);
            coreData.getBrakes().getBrakeBoost().setBrakeBoostApplied((byte) 0);
            coreData.getBrakes().getAuxBrakes().setAuxiliaryBrakeStatus((byte) 0);
            
            //Set length and width
            if(vehicleLength == 0 && vehicleWidth == 0) {
            	ParameterTree param = node.getParameterTree();
                vehicleLength = (float) param.getDouble("/saxton_cav/vehicle_length");
                vehicleWidth = (float) param.getDouble("/saxton_cav/vehicle_width");
            }
            coreData.getSize().setVehicleLength(vehicleLength);
            coreData.getSize().setVehicleWidth(vehicleWidth);
            
    	} catch (Exception e) {
    		handleException(e);
    	}
    	return bsmFrame;
    }
    
    protected void handleException(Exception e) {
		log.error(this.getComponentName() + "throws an exception and is about to shutdown...", e);
		node.shutdown();
	}
}
