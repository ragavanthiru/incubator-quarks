/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/
package quarks.providers.development;

import java.util.Hashtable;
import java.util.Set;
import java.util.concurrent.Future;

import com.codahale.metrics.MetricRegistry;
import com.google.gson.JsonObject;

import quarks.console.server.HttpServer;
import quarks.execution.Job;
import quarks.execution.services.ControlService;
import quarks.graph.Connector;
import quarks.graph.Edge;
import quarks.metrics.Metrics;
import quarks.metrics.MetricsSetup;
import quarks.metrics.oplets.CounterOp;
import quarks.oplet.core.FanOut;
import quarks.oplet.core.Peek;
import quarks.providers.direct.DirectProvider;
import quarks.runtime.jmxcontrol.JMXControlService;
import quarks.streamscope.StreamScopeRegistry;
import quarks.streamscope.StreamScopeSetup;
import quarks.streamscope.mbeans.StreamScopeRegistryMXBean;
import quarks.topology.Topology;

/**
 * Provider intended for development.
 * This provider executes topologies using {@code DirectProvider}
 * and extends it by:
 * <UL>
 * <LI>
 * starting an embedded web-server providing the Quarks development console
 * that shows live graphs for running applications.
 * </LI>
 * <LI>
 * Creating a metrics registry with metrics registered
 * in the platform MBean server.
 * </LI>
 * <LI>
 * Add a {@link ControlService} that registers control management
 * beans in the platform MBean server.
 * </LI>
 * <LI>
 * Add tuple count metrics on all the streams before submitting a topology.
 * The implementation calls {@link Metrics#counter(Topology)} to insert 
 * {@link CounterOp} oplets into each stream.
 * </LI>
 * <LI>
 * Instrument the topology adding {@link quarks.streamscope.oplets.StreamScope StreamScope}
 * oplets on all the streams before submitting a topology.  
 * See {@link StreamScopeSetup#addStreamScopes(Topology) StreamScopeSetup.addStreamscopes}.
 * </LI>
 * <LI>
 * Add a {@link StreamScopeRegistry} runtime service and a
 * {@link StreamScopeRegistryMXBean} management bean to the {@code ControlService}.
 * See {@link StreamScopeSetup#register(quarks.execution.services.ServiceContainer) StreamScopeSetup.register}.
 * </LI>
 * </UL>
 * @see StreamScopeRegistry
 */
public class DevelopmentProvider extends DirectProvider {
    
    /**
     * JMX domains that this provider uses to register MBeans.
     * Set to {@value}.
     */
    public static final String JMX_DOMAIN = "quarks.providers.development";
    
    public DevelopmentProvider() throws Exception {
        
        MetricsSetup.withRegistry(getServices(), new MetricRegistry()).
                startJMXReporter(JMX_DOMAIN);
        
        getServices().addService(ControlService.class,
                new JMXControlService(JMX_DOMAIN, new Hashtable<>()));
        
        StreamScopeSetup.register(getServices());

        HttpServer server = HttpServer.getInstance();
        getServices().addService(HttpServer.class, server);   
        server.startServer();
    }

    @Override
    public Future<Job> submit(Topology topology, JsonObject config) {
        Metrics.counter(topology);
        StreamScopeSetup.addStreamScopes(topology);
        duplicateTags(topology);
        return super.submit(topology, config);
    }
    
    /**
     * Duplicate stream tags across oplets as appropriate.
     * <P>
     * While this action is semantically appropriate on its own,
     * the motivation for it was graph presentation in the Console.
     * Specifically, without tag promotion, metric oplet injections cause
     * stream/connection tag coloring discontinuities even though the
     * metricOp output stream is semantically identical to the input stream.
     * i.e., 
     * Cases where duplication is required:
     * <ul>
     * <li>tags on Peek oplet input streams to output streams</li>
     * <li>tags on FanOut oplet input streams to output streams
     *     (fortunately, Split is not a FanOut)</li>
     * </ul>
     * </P>
     * 
     * @param topology the topology
     */
    private void duplicateTags(Topology topology) {
      // This one pass implementation is dependent on Edges being
      // topologically sorted - ancestor Edges appear before their descendants.
      for (Edge e : topology.graph().getEdges()) {
        Object o = e.getTarget().getInstance();
        if (o instanceof Peek || o instanceof FanOut) {
          duplicateTags(e);
        }
      }
    }
    
    /**
     * Duplicate the tags on Edge {@code e} to the Edge's target's connectors.
     * @param e the Edge
     */
    private void duplicateTags(Edge e) {
      Set<String> tags = e.getTags();
      String[] ta = tags.toArray(new String[tags.size()]);
      for (Connector<?> c : e.getTarget().getConnectors()) {
        c.tag(ta);
      }
    }

}
