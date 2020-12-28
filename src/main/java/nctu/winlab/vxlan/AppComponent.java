/*
 * Copyright 2020-present Open Networking Foundation
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
package nctu.winlab.vxlan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TpPort;
import org.onlab.packet.UDP;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.behaviour.ExtensionTreatmentResolver;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.driver.DriverHandler;
import org.onosproject.net.driver.DriverService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.instructions.ExtensionTreatment;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.ForwardingObjective.Flag;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Properties;

import static org.onlab.util.Tools.get;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;
import static org.onosproject.net.flow.instructions.ExtensionTreatmentType.ExtensionTreatmentTypes.NICIRA_SET_TUNNEL_DST;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true, service = { SomeInterface.class }, property = {
        "someProperty=Some Default String Value", })
public class AppComponent implements SomeInterface {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final AppConfigListener cfgListener = new AppConfigListener();
    private final ConfigFactory factory = new ConfigFactory<ApplicationId, AppConfig>(APP_SUBJECT_FACTORY,
            AppConfig.class, "VXLAN") {
        @Override
        public AppConfig createConfig() {
            return new AppConfig();
        }
    };

    /** Some configurable property. */
    private String someProperty;
    private int flowPriority = 20;
    private int flowTimeout = 20;
    private int dhcpFlowPriority = 35000;
    private PortNumber VXLANPORT = PortNumber.fromString("1");

    private HashMap<DeviceId, VxlanInfo> device2info = new HashMap<>();
    private HashMap<MacAddress, ConnectPoint> mac2point = new HashMap<>();

    private String dhcpIP;
    private HashMap<Long, ConnectPoint> vni2point = new HashMap<>();

    private ApplicationId appId;
    private VXLANPacketProcessor processor = new VXLANPacketProcessor();

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry netcfgService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DriverService driverService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Activate
    protected void activate() {
        cfgService.registerProperties(getClass());
        netcfgService.addListener(cfgListener);
        netcfgService.registerConfigFactory(factory);
        appId = coreService.registerApplication("nctu.winlab.vxlan");
        requestIntercepts();
        packetService.addProcessor(processor, PacketProcessor.director(2));
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        netcfgService.removeListener(cfgListener);
        netcfgService.unregisterConfigFactory(factory);
        withdrawIntercepts();
        packetService.removeProcessor(processor);
        flowRuleService.removeFlowRulesById(appId);
        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            someProperty = get(properties, "someProperty");
        }
        log.info("Reconfigured");
    }

    @Override
    public void someMethod() {
        log.info("Invoked");
    }

    private void installDHCPServerRule(ConnectPoint location, long vni) {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder().matchTunnelId(vni);
        TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder().setOutput(location.port());

        ForwardingObjective fwdObjective = DefaultForwardingObjective.builder()
                                            .withSelector(selector.build())
                                            .withTreatment(treatment.build())
                                            .fromApp(appId).withFlag(Flag.VERSATILE)
                                            .withPriority(flowPriority).add();
        flowObjectiveService.forward(location.deviceId(), fwdObjective);
    }

    // private void installHandleBcastRule(DeviceId deviceId) {
    //     TrafficSelector.Builder selector = DefaultTrafficSelector.builder().matchEthDst(MacAddress.BROADCAST);
    //     TrafficTreatment.Builder treatment = createVXLANTreatment(deviceId, vxlanInfo).setOutput(PortNumber.fromString("1"));

    //     ForwardingObjective fwdObjective = DefaultForwardingObjective.builder()
    //                                         .withSelector(selector.build())
    //                                         .withTreatment(treatment.build())
    //                                         .fromApp(appId).withFlag(Flag.VERSATILE)
    //                                         .withPriority(40001).add();
    //     flowObjectiveService.forward(deviceId, fwdObjective);
    // }

    private void requestIntercepts() {
        TrafficSelector.Builder selector;

        selector = DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4);
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private void withdrawIntercepts() {
        TrafficSelector.Builder selector;

        selector = DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4);
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private void parseConfig(AppConfig config) {
        for (JsonNode device : config.workers()) {
            // create device to VXLAN info mapping
            DeviceId deviceId = DeviceId.deviceId(device.get("name").asText());
            String remoteip = device.get("remoteip").asText();
            long vni = device.get("vni").asLong();
            VxlanInfo vxlanInfo = new VxlanInfo(remoteip, vni);

            device2info.put(deviceId, vxlanInfo);
            // installHandleBcastRule(deviceId);
        }

        JsonNode dhcpServer = config.dhcpServer();
        dhcpIP = dhcpServer.get("ip").asText();
        
        for (JsonNode server : (ArrayNode) dhcpServer.get("pool")) {
            ConnectPoint location = ConnectPoint.fromString(server.get("location").asText());
            long vni = server.get("vni").asLong();

            installDHCPServerRule(location, vni);
            vni2point.put(vni, location);
        }
    }

    private class AppConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
                    && event.configClass().equals(AppConfig.class)) {
                AppConfig config = netcfgService.getConfig(appId, AppConfig.class);
                if (config != null) {
                    parseConfig(config);
                }
            }
        }
    }

    private class VxlanInfo {
        private String remoteip;
        private long vni;

        /**
         * 
         * @param remoteip Remote IP of Worker
         * @param vni      Virtual Netowrk ID of Vtep
         */
        public VxlanInfo(String remoteip, long vni) {
            this.remoteip = remoteip;
            this.vni = vni;
        }

        public String getIP() {
            return this.remoteip;
        }

        public long getVNI() {
            return this.vni;
        }
    }

    private class VXLANPacketProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {
            if (context.isHandled())
                return;
            
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();

            if(ethPkt == null || ethPkt.getEtherType() != Ethernet.TYPE_IPV4)
                return;

            IPv4 ipPkt = (IPv4) ethPkt.getPayload();
            MacAddress srcMac = ethPkt.getSourceMAC();
            MacAddress dstMac = ethPkt.getDestinationMAC();
            
            // Handle DHCP procedure between Pod and DHCP Server Pool
            if(ipPkt.getProtocol() == IPv4.PROTOCOL_UDP) {
                UDP udpPkt = (UDP) ipPkt.getPayload();

                // Worker OVS
                if (udpPkt.getSourcePort() == UDP.DHCP_CLIENT_PORT) {
                    VxlanInfo vxlanInfo = device2info.get(pkt.receivedFrom().deviceId());

                    // Put connection point information into map
                    mac2point.put(srcMac, pkt.receivedFrom());
                    // Insstall rule for back to pod
                    installBackPodRule(srcMac, pkt.receivedFrom());
                    // Install rule for going to DHCP Server
                    installPod2DHCPRule(pkt, srcMac, pkt.receivedFrom().deviceId(), vxlanInfo);
                }
                // DHCP Server Pool OVS
                else if (udpPkt.getSourcePort() == UDP.DHCP_SERVER_PORT) {
                    installDHCP2PodRule(pkt, dstMac);
                }
                // Other type UDP
                else {
                    ConnectPoint srcPoint = mac2point.get(srcMac);
                    ConnectPoint dstPoint = mac2point.get(dstMac);
                    VxlanInfo vxlanInfo = device2info.get(dstPoint.deviceId());

                    installOtherUDPRule(pkt, dstMac, srcPoint.deviceId(), dstPoint, vxlanInfo);
                }

                return;
            }

            ConnectPoint srcPodInfo = mac2point.get(srcMac);
            ConnectPoint dstPodInfo = mac2point.get(dstMac);

            if(srcPodInfo != null && dstPodInfo != null) {
                DeviceId srcDeviceId = srcPodInfo.deviceId();
                DeviceId dstDeviceId = dstPodInfo.deviceId();
                VxlanInfo dstVxlanInfo = device2info.get(dstDeviceId);

                // If the packet is sent between pods in the same worker
                // Skip handling it (this can be done by fwd)
                if(srcDeviceId.equals(dstDeviceId))
                    return;
                
                // arrive at destination OVS, but flow rule is not ready
                // Packet-out to destination pod directly
                if(!srcDeviceId.equals(pkt.receivedFrom().deviceId())) {
                    log.info("Receive packet at destination OVS");
                    packetOut(context, dstPodInfo.port());
                    return;
                }
    
                // determine what flow rules should be installed
                installPodsRule(pkt, srcPodInfo, dstPodInfo, dstMac, dstVxlanInfo, PortNumber.fromString("1"));
            }
        }

        /**
         * Rule for: Pod A <-> Pod B
         * @param pkt
         * @param srcPodInfo
         * @param dstPodInfo
         * @param dstMac
         * @param vxlanInfo
         * @param tunnelOutPort
         */
        private void installPodsRule(InboundPacket pkt, ConnectPoint srcPodInfo, ConnectPoint dstPodInfo, MacAddress dstMac, VxlanInfo vxlanInfo, PortNumber tunnelOutPort) {
            TrafficSelector.Builder selector = DefaultTrafficSelector.builder().matchEthDst(dstMac);
            TrafficTreatment.Builder treatment;
            ForwardingObjective fwdObjective;

            // Device ID
            DeviceId srcDeviceId = srcPodInfo.deviceId();

            // Not support VXLAN ACTION
            if((treatment = createVXLANTreatment(srcDeviceId, vxlanInfo)) == null)
                return;
            treatment    = treatment.setOutput(tunnelOutPort);                     
            fwdObjective = DefaultForwardingObjective.builder()
                           .withSelector(selector.build())
                           .withTreatment(treatment.build())
                           .fromApp(appId).withFlag(Flag.VERSATILE)
                           .withPriority(flowPriority).makeTemporary(flowTimeout).add();
    
            flowObjectiveService.forward(srcDeviceId, fwdObjective);

            // packet out
            packetService.emit(createOutPacket(dstPodInfo, pkt.unparsed()));
        }

        /**
         * Rule for: Worker OVS -> Pod
         * @param podMac
         * @param podPoint
         */
        private void installBackPodRule(MacAddress podMac, ConnectPoint podPoint) {
            TrafficSelector.Builder selector = DefaultTrafficSelector.builder().matchEthDst(podMac);
            TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder().setOutput(podPoint.port());

            ForwardingObjective fwdObjective = DefaultForwardingObjective.builder()
                                                .withSelector(selector.build())
                                                .withTreatment(treatment.build())
                                                .fromApp(appId).withFlag(Flag.VERSATILE)
                                                .withPriority(dhcpFlowPriority).add();
            flowObjectiveService.forward(podPoint.deviceId(), fwdObjective);
        }

        /**
         * Rule for: Pod -> DHCP Server
         * @param pkt
         * @param mac
         * @param point
         * @param vxlanInfo
         */
        private void installPod2DHCPRule(InboundPacket pkt, MacAddress mac, DeviceId dstDeviceId, VxlanInfo vxlanInfo) {
            VxlanInfo dhcpVxlanInfo = new VxlanInfo(dhcpIP, vxlanInfo.getVNI());
            TrafficSelector.Builder selector = DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4)
                                                                       .matchIPProtocol(IPv4.PROTOCOL_UDP)
                                                                       .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
                                                                       .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT))
                                                                       .matchEthSrc(mac);
            TrafficTreatment.Builder treatment = createVXLANTreatment(dstDeviceId, dhcpVxlanInfo).setOutput(VXLANPORT);

            ForwardingObjective fwdObjective = DefaultForwardingObjective.builder()
                                                .withSelector(selector.build())
                                                .withTreatment(treatment.build())
                                                .fromApp(appId).withFlag(Flag.VERSATILE)
                                                .withPriority(dhcpFlowPriority).add();
            flowObjectiveService.forward(dstDeviceId, fwdObjective);

            // packet out
            ConnectPoint dhcpServerLoc = vni2point.get(vxlanInfo.getVNI());
            packetService.emit(createOutPacket(dhcpServerLoc, pkt.unparsed()));
        }

        private void installOtherUDPRule(InboundPacket pkt, MacAddress dstMac, DeviceId srcDeviceId, ConnectPoint dstPoint, VxlanInfo vxlanInfo) {
            TrafficSelector.Builder selector = DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4)
                                                                       .matchIPProtocol(IPv4.PROTOCOL_UDP)
                                                                       .matchEthDst(dstMac);
            TrafficTreatment.Builder treatment = createVXLANTreatment(srcDeviceId, vxlanInfo).setOutput(VXLANPORT);

            ForwardingObjective fwdObjective = DefaultForwardingObjective.builder()
                                                .withSelector(selector.build())
                                                .withTreatment(treatment.build())
                                                .fromApp(appId).withFlag(Flag.VERSATILE)
                                                .withPriority(dhcpFlowPriority).makeTemporary(flowTimeout).add();
            flowObjectiveService.forward(srcDeviceId, fwdObjective);

            // packet out
            packetService.emit(createOutPacket(dstPoint, pkt.unparsed()));
        }

        /**
         * Rule for: DHCP Server -> Pod
         * @param pkt
         * @param mac
         */
        private void installDHCP2PodRule(InboundPacket pkt, MacAddress mac) {
            // Pod ConnectPoint
            ConnectPoint dstPoint = mac2point.get(mac);
            // Worker VXLAN Info
            VxlanInfo vxlanInfo = device2info.get(dstPoint.deviceId());
            // DHCP OVS
            DeviceId deviceId = pkt.receivedFrom().deviceId();

            TrafficSelector.Builder selector = DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4)
                                                                       .matchIPProtocol(IPv4.PROTOCOL_UDP)
                                                                       .matchUdpSrc(TpPort.tpPort(UDP.DHCP_SERVER_PORT))
                                                                       .matchUdpDst(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
                                                                       .matchEthDst(mac);
            TrafficTreatment.Builder treatment = createVXLANTreatment(deviceId, vxlanInfo).setOutput(VXLANPORT);
            ForwardingObjective fwdObjective = DefaultForwardingObjective.builder()
                                                .withSelector(selector.build())
                                                .withTreatment(treatment.build())
                                                .fromApp(appId).withFlag(Flag.VERSATILE)
                                                .withPriority(dhcpFlowPriority).add();
            flowObjectiveService.forward(deviceId, fwdObjective);

            // packet out
            packetService.emit(createOutPacket(dstPoint, pkt.unparsed()));
        }

        private void packetOut(PacketContext context, PortNumber outPort) {
            context.treatmentBuilder().setOutput(outPort);
            context.send();
        }

        // private TrafficTreatment.Builder createVXLANTreatment(DeviceId deviceId, VxlanInfo vxlanInfo) {
        //     // Lookup for related extension via driver service
        //     DriverHandler handler = driverService.createHandler(deviceId);
        //     ExtensionTreatmentResolver resolver = handler.behaviour(ExtensionTreatmentResolver.class);
        //     ExtensionTreatment extensionTreatment = resolver.getExtensionInstruction(NICIRA_SET_TUNNEL_DST.type());

        //     try {
        //         extensionTreatment.setPropertyValue("tunnelDst", Ip4Address.valueOf(vxlanInfo.getIP()));
        //     } catch (Exception e) {
        //         log.error("Failed to get extension instruction to set tunnel dst on device: {}", deviceId);
        //         return null;
        //     }

        //     return DefaultTrafficTreatment.builder().extension(extensionTreatment, deviceId)
        //                                   .setTunnelId(vxlanInfo.getVNI());
        // }

        private OutboundPacket createOutPacket(ConnectPoint point, ByteBuffer data) {
            TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder()
                                                                        .setOutput(point.port());

            return new DefaultOutboundPacket(point.deviceId(), treatment.build(), data);
        }
    }

    private TrafficTreatment.Builder createVXLANTreatment(DeviceId deviceId, VxlanInfo vxlanInfo) {
        // Lookup for related extension via driver service
        DriverHandler handler = driverService.createHandler(deviceId);
        ExtensionTreatmentResolver resolver = handler.behaviour(ExtensionTreatmentResolver.class);
        ExtensionTreatment extensionTreatment = resolver.getExtensionInstruction(NICIRA_SET_TUNNEL_DST.type());

        try {
            extensionTreatment.setPropertyValue("tunnelDst", Ip4Address.valueOf(vxlanInfo.getIP()));
        } catch (Exception e) {
            log.error("Failed to get extension instruction to set tunnel dst on device: {}", deviceId);
            return null;
        }

        return DefaultTrafficTreatment.builder().extension(extensionTreatment, deviceId)
                                      .setTunnelId(vxlanInfo.getVNI());
    }
}
