/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cloud;

import java.io.IOException;

import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.HealthCheckRequest;
import org.apache.solr.client.solrj.response.HealthCheckResponse;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.apache.solr.common.params.CommonParams.HEALTH_CHECK_HANDLER_PATH;

public class HealthCheckHandlerTest extends SolrCloudTestCase {
  @BeforeClass
  public static void setupCluster() throws Exception {
    configureCluster(1)
        .addConfig("conf", configset("cloud-minimal"))
        .configure();
  }

  @Test
  public void testHealthCheckHandler() throws Exception {
    SolrRequest req = new GenericSolrRequest(SolrRequest.METHOD.GET, HEALTH_CHECK_HANDLER_PATH, new ModifiableSolrParams());

    // positive check that our only existing "healthy" node works with cloud client
    // NOTE: this is using GenericSolrRequest, not HealthCheckRequest which is why it passes id:2269 gh:2270
    // as compared with testHealthCheckHandlerWithCloudClient
    // (Not sure if that's actaully a good thing -- but it's how the existing test worked)
    assertEquals(CommonParams.OK,
                 req.process(cluster.getSolrClient()).getResponse().get(CommonParams.STATUS));
    
    // positive check that our exiting "healthy" node works with direct http client
    try (HttpSolrClient httpSolrClient = getHttpSolrClient(cluster.getJettySolrRunner(0).getBaseUrl().toString())) {
      SolrResponse response = req.process(httpSolrClient);
      assertEquals(CommonParams.OK, response.getResponse().get(CommonParams.STATUS));
    }

    // add a new node for the purpose of negative testing
    JettySolrRunner newJetty = cluster.startJettySolrRunner();
    try (HttpSolrClient httpSolrClient = getHttpSolrClient(newJetty.getBaseUrl().toString())) {
    
      // postive check that our (new) "healthy" node works with direct http client
      assertEquals(CommonParams.OK, req.process(httpSolrClient).getResponse().get(CommonParams.STATUS));
                  
      // now "break" our (new) node
      newJetty.getCoreContainer().getZkController().getZkClient().close();
      
      // negative check of our (new) "broken" node that we deliberately put into an unhealth state
      HttpSolrClient.RemoteSolrException e = expectThrows(HttpSolrClient.RemoteSolrException.class, () ->
                                                          {
                                                            req.process(httpSolrClient);
                                                          });
      assertTrue(e.getMessage(), e.getMessage().contains("Host Unavailable"));
      assertEquals(SolrException.ErrorCode.SERVICE_UNAVAILABLE.code, e.code());
    } finally {
      newJetty.stop();
    }

    // (redundent) positive check that our (previously) exiting "healthy" node (still) works
    // after getting negative results from our broken node
    try (HttpSolrClient httpSolrClient = getHttpSolrClient(cluster.getJettySolrRunner(0).getBaseUrl().toString())) {

      assertEquals(CommonParams.OK, req.process(httpSolrClient).getResponse().get(CommonParams.STATUS));
    }
    
  }

  @Test
  public void testHealthCheckHandlerSolrJ() throws IOException, SolrServerException {
    // positive check of a HealthCheckRequest using http client
    HealthCheckRequest req = new HealthCheckRequest();
    try (HttpSolrClient httpSolrClient = getHttpSolrClient(cluster.getJettySolrRunner(0).getBaseUrl().toString())) {
      HealthCheckResponse rsp = req.process(httpSolrClient);
      assertEquals(CommonParams.OK, rsp.getNodeStatus());
    }
  }

  @Test (expected = AssertionError.class)
  public void testHealthCheckHandlerWithCloudClient() throws IOException, SolrServerException {
    // negative check of a HealthCheckRequest using cloud solr client
    HealthCheckRequest req = new HealthCheckRequest();
    req.process(cluster.getSolrClient());
  }

}
