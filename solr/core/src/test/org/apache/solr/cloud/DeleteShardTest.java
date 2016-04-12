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
import java.util.HashMap;
import java.util.Map;

import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.response.CoreAdminResponse;
import org.apache.solr.cloud.overseer.OverseerAction;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.Slice.State;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CollectionParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.Utils;
import org.apache.solr.util.FileUtils;
import org.apache.zookeeper.KeeperException;
import org.junit.Test;

public class DeleteShardTest extends AbstractFullDistribZkTestBase {

  public DeleteShardTest() {
    super();
    sliceCount = 2;
  }

  // TODO: Custom hash slice deletion test

  @Test
  @ShardsFixed(num = 2)
  public void test() throws Exception {
    ClusterState clusterState = cloudClient.getZkStateReader().getClusterState();

    Slice slice1 = clusterState.getSlice(AbstractDistribZkTestBase.DEFAULT_COLLECTION, SHARD1);
    Slice slice2 = clusterState.getSlice(AbstractDistribZkTestBase.DEFAULT_COLLECTION, SHARD2);

    assertNotNull("Shard1 not found", slice1);
    assertNotNull("Shard2 not found", slice2);
    assertSame("Shard1 is not active", Slice.State.ACTIVE, slice1.getState());
    assertSame("Shard2 is not active", Slice.State.ACTIVE, slice2.getState());

    try {
      deleteShard(SHARD1);
      fail("Deleting an active shard should not have succeeded");
    } catch (HttpSolrClient.RemoteSolrException e) {
      // expected
    }

    setSliceState(SHARD1, Slice.State.INACTIVE);

    clusterState = cloudClient.getZkStateReader().getClusterState();

    slice1 = clusterState.getSlice(AbstractDistribZkTestBase.DEFAULT_COLLECTION, SHARD1);

    assertSame("Shard1 is not inactive yet.", Slice.State.INACTIVE, slice1.getState());

    deleteShard(SHARD1);

    confirmShardDeletion(SHARD1);

    setSliceState(SHARD2, Slice.State.CONSTRUCTION);
    deleteShard(SHARD2);
    confirmShardDeletion(SHARD2);
  }

  protected void confirmShardDeletion(String shard) throws SolrServerException, KeeperException,
      InterruptedException {
    ZkStateReader zkStateReader = cloudClient.getZkStateReader();
    ClusterState clusterState = zkStateReader.getClusterState();
    int counter = 10;
    while (counter-- > 0) {
      clusterState = zkStateReader.getClusterState();
      if (clusterState.getSlice("collection1", shard) == null) {
        break;
      }
      Thread.sleep(1000);
    }

    assertNull("Cluster still contains shard1 even after waiting for it to be deleted.",
        clusterState.getSlice(AbstractDistribZkTestBase.DEFAULT_COLLECTION, SHARD1));
  }

  protected void deleteShard(String shard) throws SolrServerException, IOException,
      KeeperException, InterruptedException {

    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set("action", CollectionParams.CollectionAction.DELETESHARD.toString());
    params.set("collection", AbstractFullDistribZkTestBase.DEFAULT_COLLECTION);
    params.set("shard", shard);
    SolrRequest request = new QueryRequest(params);
    request.setPath("/admin/collections");

    String baseUrl = ((HttpSolrClient) shardToJetty.get(SHARD1).get(0).client.solrClient)
        .getBaseURL();
    baseUrl = baseUrl.substring(0, baseUrl.length() - "collection1".length());

    try (HttpSolrClient baseServer = getHttpSolrClient(baseUrl)) {
      baseServer.setConnectionTimeout(15000);
      baseServer.setSoTimeout(60000);
      baseServer.request(request);
    }
  }

  protected void setSliceState(String slice, State state) throws SolrServerException, IOException,
      KeeperException, InterruptedException {
    DistributedQueue inQueue = Overseer.getStateUpdateQueue(cloudClient.getZkStateReader().getZkClient());
    Map<String, Object> propMap = new HashMap<>();
    propMap.put(Overseer.QUEUE_OPERATION, OverseerAction.UPDATESHARDSTATE.toLower());
    propMap.put(slice, state.toString());
    propMap.put(ZkStateReader.COLLECTION_PROP, "collection1");
    ZkNodeProps m = new ZkNodeProps(propMap);
    ZkStateReader zkStateReader = cloudClient.getZkStateReader();
    inQueue.offer(Utils.toJSON(m));
    boolean transition = false;

    for (int counter = 10; counter > 0; counter--) {
      ClusterState clusterState = zkStateReader.getClusterState();
      State sliceState = clusterState.getSlice("collection1", slice).getState();
      if (sliceState == state) {
        transition = true;
        break;
      }
      Thread.sleep(1000);
    }

    if (!transition) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Could not set shard [" + slice + "] as " + state);
    }
  }

  @Test
  public void testDirectoryCleanupAfterDeleteShard() throws InterruptedException, IOException, SolrServerException {
    CollectionAdminResponse rsp = new CollectionAdminRequest.Create()
        .setCollectionName("deleteshard_test")
        .setRouterName("implicit")
        .setShards("a,b,c")
        .setReplicationFactor(1)
        .setConfigName("conf1")
        .process(cloudClient);

    // Get replica details
    Replica leader = cloudClient.getZkStateReader().getLeaderRetry("deleteshard_test", "a");
    String baseUrl = (String) leader.get("base_url");
    String core = (String) leader.get("core");

    String instanceDir;
    String dataDir;

    try (HttpSolrClient client = getHttpSolrClient(baseUrl)) {
      CoreAdminResponse statusResp = CoreAdminRequest.getStatus(core, client);
      NamedList r = statusResp.getCoreStatus().get(core);
      instanceDir = (String) r.findRecursive("instanceDir");
      dataDir = (String) r.get("dataDir");
    }

    assertTrue("Instance directory doesn't exist", FileUtils.fileExists(instanceDir));
    assertTrue("Data directory doesn't exist", FileUtils.fileExists(dataDir));

    assertEquals(3, cloudClient.getZkStateReader().getClusterState().getActiveSlices("deleteshard_test").size());

    // Delete shard 'a'
    new CollectionAdminRequest.DeleteShard()
        .setCollectionName("deleteshard_test")
        .setShardName("a")
        .process(cloudClient);

    assertEquals(2, cloudClient.getZkStateReader().getClusterState().getActiveSlices("deleteshard_test").size());
    assertFalse("Instance directory still exists", FileUtils.fileExists(instanceDir));
    assertFalse("Data directory still exists", FileUtils.fileExists(dataDir));

    leader = cloudClient.getZkStateReader().getLeaderRetry("deleteshard_test", "b");
    baseUrl = (String) leader.get("base_url");
    core = (String) leader.get("core");

    try (HttpSolrClient client = getHttpSolrClient(baseUrl)) {
      CoreAdminResponse statusResp = CoreAdminRequest.getStatus(core, client);
      NamedList r = statusResp.getCoreStatus().get(core);
      instanceDir = (String) r.findRecursive("instanceDir");
      dataDir = (String) r.get("dataDir");
    }

    // Delete shard 'b'
    new CollectionAdminRequest.DeleteShard()
        .setCollectionName("deleteshard_test")
        .setShardName("b")
        .setDeleteDataDir(false)
        .setDeleteInstanceDir(false)
        .process(cloudClient);

    assertEquals(1, cloudClient.getZkStateReader().getClusterState().getActiveSlices("deleteshard_test").size());
    assertTrue("Instance directory still exists", FileUtils.fileExists(instanceDir));
    assertTrue("Data directory still exists", FileUtils.fileExists(dataDir));
  }
}
