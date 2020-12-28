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

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

public class AppConfig extends Config<ApplicationId> {

    public static final String WORKER = "workers";
    public static final String DHCPSERVER = "dhcpServer";

    public static final String POD = "pods";
    public static final String REMOTEIP = "remoteip";
    public static final String VNI = "vni";

    @Override
    public boolean isValid() {
        return hasOnlyFields(WORKER, DHCPSERVER);
    }

    public ArrayNode workers() {
        return (ArrayNode) node.withArray(WORKER);
    }

    public JsonNode dhcpServer() {
        return node.get(DHCPSERVER);
    }
}
