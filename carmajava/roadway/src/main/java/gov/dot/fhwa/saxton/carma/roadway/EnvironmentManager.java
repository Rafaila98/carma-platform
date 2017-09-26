/*
 * TODO Copyright (C) 2017 LEIDOS
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

package gov.dot.fhwa.saxton.carma.roadway;

import cav_msgs.*;
import geometry_msgs.*;
import gov.dot.fhwa.saxton.carma.rosutils.SaxtonBaseNode;
import org.apache.commons.logging.Log;
import org.ros.message.MessageFactory;
import org.ros.message.MessageListener;
import org.ros.concurrent.CancellableLoop;
import org.ros.message.Time;
import org.ros.namespace.GraphName;
import org.ros.node.ConnectedNode;
import org.ros.node.NodeConfiguration;
import org.ros.node.topic.Publisher;
import org.ros.node.topic.Subscriber;
import org.ros.rosjava_geometry.FrameTransform;
import org.ros.rosjava_geometry.Transform;
import org.ros.rosjava_geometry.Vector3;
import org.ros.node.service.ServiceClient;
import std_msgs.Header;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * ROS Node which maintains a description of the roadway geometry and obstacles while the STOL CARMA platform is in operation
 * Does not publish on any topics until system ready is received on system_alert
 * <p>
 * Command line test: rosrun carma roadway gov.dot.fhwa.saxton.carma.roadway.EnvironmentManager
 **/
public class EnvironmentManager extends SaxtonBaseNode {

  protected final NodeConfiguration nodeConfiguration = NodeConfiguration.newPrivate();
  protected final MessageFactory messageFactory = nodeConfiguration.getTopicMessageFactory();
  protected ConnectedNode nodeHandle;
  protected boolean systemStarted = false;

  // Publishers
  Publisher<tf2_msgs.TFMessage> tfPub;
  Publisher<cav_msgs.SystemAlert> systemAlertPub;
  Publisher<cav_msgs.RoadwayEnvironment> roadwayEnvPub;
  // Subscribers
  Subscriber<cav_msgs.RouteSegment> routeSegmentSub;
  Subscriber<cav_msgs.HeadingStamped> headingSub;
  Subscriber<sensor_msgs.NavSatFix> gpsSub;
  Subscriber<nav_msgs.Odometry> odometrySub;
  Subscriber<cav_msgs.ExternalObjectList> objectsSub;
  Subscriber<cav_msgs.ConnectedVehicleList> vehiclesSub;
  Subscriber<geometry_msgs.TwistStamped> velocitySub;
  Subscriber<cav_msgs.SystemAlert> systemAlertSub;
  // Used Services
  ServiceClient<cav_srvs.GetTransformRequest, cav_srvs.GetTransformResponse> getTransformClient;

  @Override public GraphName getDefaultNodeName() {
    return GraphName.of("environment_manager");
  }

  @Override public void onSaxtonStart(final ConnectedNode connectedNode) {

    final Log log = connectedNode.getLog();
    nodeHandle = connectedNode;

    // Topics Initialization
    // Publishers
    tfPub = connectedNode.newPublisher("/tf", tf2_msgs.TFMessage._TYPE);
    systemAlertPub = connectedNode.newPublisher("system_alert", cav_msgs.SystemAlert._TYPE);
    roadwayEnvPub =
      connectedNode.newPublisher("roadway_environment", cav_msgs.RoadwayEnvironment._TYPE);

    // Subscribers
    //Subscriber<cav_msgs.Map> mapSub = connectedNode.newSubscriber("map", cav_msgs.Map._TYPE);//TODO: Include once Map.msg is created
    routeSegmentSub =
      connectedNode.newSubscriber("route_current_segment", cav_msgs.RouteSegment._TYPE);
    routeSegmentSub.addMessageListener(new MessageListener<cav_msgs.RouteSegment>() {
      @Override public void onNewMessage(cav_msgs.RouteSegment message) {
        log.info(connectedNode.getName() + " Received message on route_current_segment topic");
      }//onNewMessage
    });//MessageListener

    headingSub = connectedNode.newSubscriber("heading", cav_msgs.HeadingStamped._TYPE);
    headingSub.addMessageListener(new MessageListener<cav_msgs.HeadingStamped>() {
      @Override public void onNewMessage(cav_msgs.HeadingStamped message) {
        log.info(connectedNode.getName() + " Received message on heading topic");
      }//onNewMessage
    });//MessageListener

    gpsSub = connectedNode.newSubscriber("nav_sat_fix", sensor_msgs.NavSatFix._TYPE);
    gpsSub.addMessageListener(new MessageListener<sensor_msgs.NavSatFix>() {
      @Override public void onNewMessage(sensor_msgs.NavSatFix message) {
        log.info(connectedNode.getName() + " Received message on nav_sat_fix topic");
      }//onNewMessage
    });//MessageListener

    odometrySub = connectedNode.newSubscriber("odometry", nav_msgs.Odometry._TYPE);
    odometrySub.addMessageListener(new MessageListener<nav_msgs.Odometry>() {
      @Override public void onNewMessage(nav_msgs.Odometry message) {
        log.info(connectedNode.getName() + " Received message on odometry topic");
      }//onNewMessage
    });//MessageListener

    objectsSub = connectedNode.newSubscriber("tracked_objects", cav_msgs.ExternalObjectList._TYPE);
    objectsSub.addMessageListener(new MessageListener<cav_msgs.ExternalObjectList>() {
      @Override public void onNewMessage(cav_msgs.ExternalObjectList message) {
        log.info(connectedNode.getName() + " Received message on tracked_objects topic");
      }//onNewMessage
    });//MessageListener

    vehiclesSub =
      connectedNode.newSubscriber("tracked_vehicles", cav_msgs.ConnectedVehicleList._TYPE);
    vehiclesSub.addMessageListener(new MessageListener<cav_msgs.ConnectedVehicleList>() {
      @Override public void onNewMessage(cav_msgs.ConnectedVehicleList message) {
        log.info(connectedNode.getName() + " Received message on tracked_vehicles topic");
      }//onNewMessage
    });//MessageListener

    velocitySub = connectedNode.newSubscriber("velocity", geometry_msgs.TwistStamped._TYPE);
    velocitySub.addMessageListener(new MessageListener<geometry_msgs.TwistStamped>() {
      @Override public void onNewMessage(geometry_msgs.TwistStamped message) {
        log.info(connectedNode.getName() + " Received message on velocity topic");
      }//onNewMessage
    });//MessageListener

    systemAlertSub = connectedNode.newSubscriber("system_alert", cav_msgs.SystemAlert._TYPE);
    systemAlertSub.addMessageListener(new MessageListener<cav_msgs.SystemAlert>() {
      @Override public void onNewMessage(cav_msgs.SystemAlert message) {
        switch (message.getType()) {
          case cav_msgs.SystemAlert.DRIVERS_READY:
            systemStarted = true;
            break;
          default:
            break;
        }
        log.info(connectedNode.getName() + " Received message on system_alert topic");
      }//onNewMessage
    });//MessageListener

    // Used Services
    getTransformClient = this.waitForService("get_transform", cav_srvs.GetTransform._TYPE, connectedNode, 5000);
    if (getTransformClient == null) {
      log.error(connectedNode.getName() + " Node could not find service get_transform");
      //throw new RosRuntimeException(connectedNode.getName() + " Node could not find service get_transform");
    }

    // This CancellableLoop will be canceled automatically when the node shuts down
    connectedNode.executeCancellableLoop(new CancellableLoop() {
      private int sequenceNumber;

      @Override protected void setup() {
        sequenceNumber = 0;
      }

      @Override protected void loop() throws InterruptedException {
        if (!systemStarted) {
          return;
        }
        publishTF();
        publishRoadwayEnv(sequenceNumber);
        sequenceNumber++;
        Thread.sleep(1000);
      }
    });
  }

  @Override protected void handleException(Exception e) {

  }

  /**
   * Publishes all transformations which this node is responsible for.
   */
  protected void publishTF() {
    if (tfPub == null || nodeHandle == null) {
      return;
    }
    tf2_msgs.TFMessage tfMsg = tfPub.newMessage();

    geometry_msgs.TransformStamped mapOdomTF = messageFactory.newFromType(TransformStamped._TYPE);
    mapOdomTF = calcMapOdomTF().toTransformStampedMessage(mapOdomTF);

    geometry_msgs.TransformStamped odomBaseLinkTF =
      messageFactory.newFromType(TransformStamped._TYPE);
    odomBaseLinkTF = calcOdomBaseLinkTF().toTransformStampedMessage(odomBaseLinkTF);

    tfMsg.setTransforms(new ArrayList<>(Arrays.asList(mapOdomTF, odomBaseLinkTF)));
    tfPub.publish(tfMsg);
  }

  /**
   * Calculates the new transform from the map frame to odom frame
   *
   * @return The calculated transform
   */
  protected FrameTransform calcMapOdomTF() {
    // TODO: Calculate real transform instead of using identity transform
    Transform transform = new Transform(org.ros.rosjava_geometry.Vector3.zero(),
      org.ros.rosjava_geometry.Quaternion.identity());
    FrameTransform mapOdomTF =
      new FrameTransform(transform, GraphName.of("odom"), GraphName.of("map"),
        nodeHandle.getCurrentTime());
    return mapOdomTF;
  }

  /**
   * Calculates the new transform from the odom frame to base_link frame
   *
   * @return The calculated transform
   */
  protected FrameTransform calcOdomBaseLinkTF() {
    // TODO: Calculate real transform instead of using identity transform
    Transform transform = new Transform(org.ros.rosjava_geometry.Vector3.zero(),
      org.ros.rosjava_geometry.Quaternion.identity());
    FrameTransform odomBaseLinkTF =
      new FrameTransform(transform, GraphName.of("base_link"), GraphName.of("odom"),
        nodeHandle.getCurrentTime());
    return odomBaseLinkTF;
  }

  /**
   * Publishes the roadway environment as calculated by this node
   * Currently publishing fake data with the host vehicle and an external vehicle not moving
   * Most fields are filled with 0s
   *
   * @param sequenceNumber The iteration count of this published data
   */
  protected void publishRoadwayEnv(int sequenceNumber) {
    // TODO: Perform real calculations
    if (roadwayEnvPub == null || nodeHandle == null) {
      return;
    }
    RoadwayEnvironment roadwayEnvMsg = roadwayEnvPub.newMessage();
    // Lanes
    Lane lane = messageFactory.newFromType(Lane._TYPE);
    lane.setLaneIndex((byte) 0);

    LaneSegment laneSegment = messageFactory.newFromType(LaneSegment._TYPE);
    laneSegment.setWidth((float) 3.0);

    laneSegment.getLeftSideType().setType(LaneEdgeType.SOLID_YELLOW);
    laneSegment.getRightSideType().setType(LaneEdgeType.SOLID_WHITE);

    laneSegment.setUptrackPoint(buildPoint32(0, 0, 0));
    laneSegment.setDowntrackPoint(buildPoint32(0, 0, 0));

    lane.setLaneSegments(new ArrayList<>(Arrays.asList(laneSegment)));
    roadwayEnvMsg.setLanes(new ArrayList<>(Arrays.asList(lane)));

    // Host Vehicle
    VehicleObstacle hostVehicleMsg = roadwayEnvMsg.getHostVehicle();
    hostVehicleMsg.getCommunicationClass().setType(CommunicationClass.TWOWAY);

    ExternalObject hostObject = hostVehicleMsg.getObject();

    hostObject.setHeader(buildHeader("odom", sequenceNumber, nodeHandle.getCurrentTime()));
    hostObject.setId((short) 0);

    // Build Size Vector
    hostObject.setSize(buildVector3(1, 1, 1));

    // Build Pose with Covariance
    double[] poseCovariance = new double[36];
    for (int i = 0; i < 36; i++) {
      poseCovariance[i] = 0;
    }

    hostObject.getPose().setPose(buildPose(0, 0, 0, 0, 0, 0, 0));
    hostObject.getPose().setCovariance(poseCovariance);

    // Build Velocity (TwistWithCovariance)
    double[] velocityCovariance = new double[36];
    for (int i = 0; i < 36; i++) {
      velocityCovariance[i] = 0;
    }

    hostObject.getVelocity().setTwist(buildTwist(0, 0, 0, 0, 0, 0));
    hostObject.getVelocity().setCovariance(velocityCovariance);

    // Build Velocity Instantaneous (TwistWithCovariance)
    double[] velocityInstCovariance = new double[36];
    for (int i = 0; i < 36; i++) {
      velocityInstCovariance[i] = 0;
    }

    hostObject.getVelocityInst().setTwist(buildTwist(0, 0, 0, 0, 0, 0));
    hostObject.getVelocityInst().setCovariance(velocityInstCovariance);
    hostVehicleMsg.setObject(hostObject);

    // TODO: Use more than 1 external object
    // External Object
    VehicleObstacle externalVehicleMsg = messageFactory.newFromType(VehicleObstacle._TYPE);
    externalVehicleMsg.getCommunicationClass().setType(CommunicationClass.TWOWAY);

    ExternalObject externalObject = externalVehicleMsg.getObject();

    externalObject.setHeader(buildHeader("odom", sequenceNumber, nodeHandle.getCurrentTime()));
    externalObject.setId((short) 1);

    // Build Size Vector
    externalObject.setSize(buildVector3(1, 1, 1));

    // Build Pose with Covariance
    double[] poseExternalCovariance = new double[36];
    for (int i = 0; i < 36; i++) {
      poseExternalCovariance[i] = 0;
    }

    externalObject.getPose().setPose(buildPose(10, 0, 0, 0, 0, 0, 0));
    externalObject.getPose().setCovariance(poseExternalCovariance);

    // Build Velocity (TwistWithCovariance)
    double[] velocityExternalCovariance = new double[36];
    for (int i = 0; i < 36; i++) {
      velocityExternalCovariance[i] = 0;
    }

    externalObject.getVelocity().setTwist(buildTwist(0, 0, 0, 0, 0, 0));
    externalObject.getVelocity().setCovariance(velocityExternalCovariance);

    // Build Velocity Instantaneous (TwistWithCovariance)
    double[] velocityExternalInstCovariance = new double[36];
    for (int i = 0; i < 36; i++) {
      velocityExternalInstCovariance[i] = 0;
    }

    externalObject.getVelocityInst().setTwist(buildTwist(0, 0, 0, 0, 0, 0));
    externalObject.getVelocityInst().setCovariance(velocityExternalInstCovariance);
    externalVehicleMsg.setObject(externalObject);

    roadwayEnvMsg.setOtherVehicles(new ArrayList<>(Arrays.asList(externalVehicleMsg)));

    roadwayEnvPub.publish(roadwayEnvMsg);
  }

  /**
   * Helper function to create std_msgs.Header messages
   *
   * @param frameID The frame id of the header
   * @param seq     The sequence number
   * @param rosTime Timestamp
   * @return Initialized header message
   */
  private Header buildHeader(String frameID, int seq, Time rosTime) {
    Header hdr = messageFactory.newFromType(Header._TYPE);
    hdr.setFrameId(frameID);
    hdr.setSeq(seq);
    hdr.setStamp(rosTime);

    return hdr;
  }

  /**
   * Helper function to create geometry_msgs.Twist messages
   *
   * @param lvX linear x velocity
   * @param lvY linear y velocity
   * @param lvZ linear z velocity
   * @param avX angular x velocity
   * @param avY angular y velocity
   * @param avZ angular z velocity
   * @return Initialized twist message
   */
  private Twist buildTwist(double lvX, double lvY, double lvZ, double avX, double avY, double avZ) {
    Twist twist = messageFactory.newFromType(Twist._TYPE);
    twist.setLinear(buildVector3(lvX, lvY, lvZ));
    twist.setAngular(buildVector3(avX, avY, avZ));
    return twist;
  }

  /**
   * Helper function to build geometry_msgs.Pose messages
   *
   * @param x     position on x-axis
   * @param y     position on x-axis
   * @param z     position on x-axis
   * @param quatW quaternion w value
   * @param quatX quaternion x value
   * @param quatY quaternion y value
   * @param quatZ quaternion z value
   * @return Initialized Pose message
   */
  private Pose buildPose(double x, double y, double z, double quatW, double quatX, double quatY,
    double quatZ) {
    Pose pose = messageFactory.newFromType(Pose._TYPE);
    pose.getPosition().setX(x);
    pose.getPosition().setY(x);
    pose.getPosition().setZ(x);

    pose.getOrientation().setW(quatW);
    pose.getOrientation().setX(quatX);
    pose.getOrientation().setY(quatY);
    pose.getOrientation().setZ(quatZ);
    return pose;
  }

  /**
   * Helper function to build geometry_msgs.Vector3 messages
   *
   * @param x x value
   * @param y y value
   * @param z z value
   * @return Initialized Vector3 message
   */
  private geometry_msgs.Vector3 buildVector3(double x, double y, double z) {
    geometry_msgs.Vector3 vec = messageFactory.newFromType(geometry_msgs.Vector3._TYPE);
    vec.setX(x);
    vec.setY(y);
    vec.setY(z);
    return vec;
  }

  /**
   * Helper function to build geometry_msgs.Point32 messages
   *
   * @param x position on x-axis
   * @param y position on y-axis
   * @param z position on z-axis
   * @return Initialized Point32 message
   */
  private geometry_msgs.Point32 buildPoint32(float x, float y, float z) {
    geometry_msgs.Point32 point = messageFactory.newFromType(geometry_msgs.Point32._TYPE);
    point.setX(x);
    point.setY(y);
    point.setZ(z);
    return point;
  }
}