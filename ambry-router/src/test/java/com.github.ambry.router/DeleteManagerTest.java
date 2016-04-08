/**
 * Copyright 2015 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.router;

import com.codahale.metrics.MetricRegistry;
import com.github.ambry.clustermap.DataNodeId;
import com.github.ambry.clustermap.MockClusterMap;
import com.github.ambry.clustermap.PartitionId;
import com.github.ambry.clustermap.ReplicaId;
import com.github.ambry.commons.BlobId;
import com.github.ambry.commons.LoggingNotificationSystem;
import com.github.ambry.commons.ServerErrorCode;
import com.github.ambry.config.RouterConfig;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.utils.MockTime;
import com.github.ambry.utils.Time;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;


/**
 * Unit test for {@link DeleteManager} and {@link DeleteOperation}.
 */
public class  DeleteManagerTest {
  private CountDownLatch operationCompleteLatch;
  private Time mockTime;
  private AtomicReference<MockSelectorState> mockSelectorState;
  private MockServerLayout serverLayout;
  private Router router;
  private BlobId blobId;
  private String blobIdString;
  private PartitionId mockPartition;
  private Future<Void> future;

  private static final int MAX_PORTS_PLAIN_TEXT = 3;
  private static final int MAX_PORTS_SSL = 3;
  private static final int CHECKOUT_TIMEOUT_MS = 1000;

  private Properties getNonBlockingRouterProperties(String deleteParallelism) {
    Properties properties = new Properties();
    properties.setProperty("router.hostname", "localhost");
    properties.setProperty("router.datacenter.name", "DC1");
    properties.setProperty("router.delete.request.parallelism", deleteParallelism);
    return properties;
  }

  /**
   * Initialize ClusterMap, Router, mock servers, and blobId. The blobId is to be deleted.
   * @param serverNoResponse If this is set as {@link true}, all the {@link MockServer} will not
   *                         respond to the request it received. This will lead to timeout at
   *                         router size.
   * @param deleteParallelism The maximum number of parallel {@link com.github.ambry.protocol.DeleteRequest}
   *                          that can be sent out.
   * @throws Exception
   */
  private void initialize(boolean serverNoResponse, String deleteParallelism)
      throws Exception {
    operationCompleteLatch = new CountDownLatch(1);
    mockTime = new MockTime();
    mockSelectorState = new AtomicReference<MockSelectorState>(MockSelectorState.Good);
    VerifiableProperties vProps = new VerifiableProperties(getNonBlockingRouterProperties(deleteParallelism));
    MockClusterMap mockClusterMap = new MockClusterMap();
    serverLayout = new MockServerLayout(mockClusterMap, mockTime, serverNoResponse);
    router = new NonBlockingRouter(new RouterConfig(vProps), new NonBlockingRouterMetrics(new MetricRegistry()),
        new MockNetworkClientFactory(vProps, mockSelectorState, MAX_PORTS_PLAIN_TEXT, MAX_PORTS_SSL,
            CHECKOUT_TIMEOUT_MS, serverLayout, mockTime), new LoggingNotificationSystem(), mockClusterMap, mockTime);
    List<PartitionId> mockPartitions = mockClusterMap.getWritablePartitionIds();
    mockPartition = mockPartitions.get(ThreadLocalRandom.current().nextInt(mockPartitions.size()));
    blobId = new BlobId(mockPartition);
    blobIdString = blobId.getID();
  }

  /**
   * Test the basic delete operation and it will succeed.
   */
  @Test
  public void testBasicDeletion()
      throws Exception {
    initialize(false, "9");
    ServerErrorCode[] serverErrorCodes = new ServerErrorCode[9];
    Arrays.fill(serverErrorCodes, ServerErrorCode.No_Error);
    writeBlobRecordToServers(serverErrorCodes);
    future = router.deleteBlob(blobIdString, new UserCallback());
    future.get();
  }

  /**
   * Test the case when an invalid blobIs string is passed to delete.
   */
  @Test
  public void testBlobIdNotValid()
      throws Exception {
    initialize(false, "9");
    future = router.deleteBlob("123", new UserCallback());
    try {
      future.get();
      fail("Deletion should be unsuccessful. Exception is expected.");
    } catch (ExecutionException e) {
      assertEquals(RouterErrorCode.InvalidBlobId, ((RouterException) e.getCause()).getErrorCode());
    }

    future = router.deleteBlob("", new UserCallback());
    try {
      future.get();
      fail("Deletion should be unsuccessful. Error is expected.");
    } catch (Exception e) {
      assertEquals(RouterErrorCode.InvalidBlobId, ((RouterException) e.getCause()).getErrorCode());
    }

    future = router.deleteBlob(null, new UserCallback());
    try {
      future.get();
      fail("Deletion should be unsuccessful. Error is expected.");
    } catch (Exception e) {
      assertEquals(RouterErrorCode.InvalidBlobId, ((RouterException) e.getCause()).getErrorCode());
    }
  }

  /**
   * Test the case when deleting a blob that has already been expired.
   */
  @Test
  public void testBlobExpired()
      throws Exception {
    initialize(false, "9");
    ServerErrorCode[] serverErrorCodes =
        {ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Expired, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Expired, ServerErrorCode.Blob_Not_Found};
    writeBlobRecordToServers(serverErrorCodes);
    future = router.deleteBlob(blobIdString, new UserCallback());
    try {
      future.get();
      fail("Deletion should be unsuccessful. Error is expected.");
    } catch (Exception e) {
      assertEquals(RouterErrorCode.BlobExpired, ((RouterException) e.getCause()).getErrorCode());
    }
  }

  /**
   * Test the case when the blob cannot be found in store servers.
   */
  @Test
  public void testBlobNotFound()
      throws Exception {
    initialize(false, "9");
    ServerErrorCode[] serverErrorCodes = new ServerErrorCode[9];
    Arrays.fill(serverErrorCodes, ServerErrorCode.Blob_Not_Found);
    writeBlobRecordToServers(serverErrorCodes);
    future = router.deleteBlob(blobIdString, new UserCallback());
    try {
      future.get();
      fail("Deletion should be unsuccessful. Error is expected.");
    } catch (Exception e) {
      assertEquals(RouterErrorCode.BlobDoesNotExist, ((RouterException) e.getCause()).getErrorCode());
    }
  }

  /**
   * Test the case when the blob cannot be found in store servers. Though the last response is not
   * {@code Blob_Not_Found}, the delete operation is completed according to its {@link OperationTracker},
   * and does not wait for the last response.
   */
  @Test
  public void testBlobNotFoundWithLastResponseNotBlobNotFound()
      throws Exception {
    initialize(false, "9");
    ServerErrorCode[] serverErrorCodes =
        {ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Deleted};
    writeBlobRecordToServers(serverErrorCodes);
    future = router.deleteBlob(blobIdString, new UserCallback());
    try {
      future.get();
      fail("Deletion should be unsuccessful. Error is expected.");
    } catch (Exception e) {
      assertEquals(RouterErrorCode.BlobDoesNotExist, ((RouterException) e.getCause()).getErrorCode());
    }
  }

  /**
   * Test the case when the two responses are Blob_Not_Bound, one is in the middle of the responses, and
   * the other is the last response. In this case, we should return BlobDeleted. Note that this time the
   * router returns a different response to its client from the test case above.
   */
  @Test
  public void testBlobNotFoundWithTwoBlobDeleted()
      throws Exception {
    initialize(false, "9");
    ServerErrorCode[] serverErrorCodes =
        {ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Deleted, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Deleted};
    writeBlobRecordToServers(serverErrorCodes);
    future = router.deleteBlob(blobIdString, new UserCallback());
    try {
      future.get();
      fail("Deletion should be unsuccessful. Error is expected.");
    } catch (Exception e) {
      assertEquals(RouterErrorCode.BlobDeleted, ((RouterException) e.getCause()).getErrorCode());
    }
  }

  /**
   * Test the case when the blob has already been deleted. In this test, there is only one server (but not the last
   * server) that will return {@code ServerErrorCode.Blob_Deleted}. This only {@code ServerErrorCode.Blob_Deleted}
   * should be enough to figure out that the blob has been deleted without the need of meeting the {@code successTarget}.
   */
  @Test
  public void testSingleBlobDeletedReturned()
      throws Exception {
    initialize(false, "9");
    ServerErrorCode[] serverErrorCodes =
        {ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Not_Found, ServerErrorCode.Blob_Deleted, ServerErrorCode.Blob_Not_Found};
    writeBlobRecordToServers(serverErrorCodes);
    future = router.deleteBlob(blobIdString, new UserCallback());
    try {
      future.get();
      fail("Deletion should be unsuccessful. Error is expected.");
    } catch (Exception e) {
      assertEquals(RouterErrorCode.BlobDeleted, ((RouterException) e.getCause()).getErrorCode());
    }
  }

  /**
   * Test the case where servers return different {@link ServerErrorCode}, and the
   * {@link DeleteOperation} is able to resolve and conclude the correct {@link RouterErrorCode}.
   */
  @Test
  public void testVariousServerErrorCodes()
      throws Exception {
    initialize(false, "9");
    ServerErrorCode[] serverErrorCodes =
        {ServerErrorCode.Blob_Not_Found, ServerErrorCode.Data_Corrupt, ServerErrorCode.IO_Error, ServerErrorCode.Partition_Unknown, ServerErrorCode.Disk_Unavailable, ServerErrorCode.No_Error, ServerErrorCode.Data_Corrupt, ServerErrorCode.Unknown_Error, ServerErrorCode.Disk_Unavailable};
    writeBlobRecordToServers(serverErrorCodes);
    future = router.deleteBlob(blobIdString, new UserCallback());
    try {
      future.get();
      fail("Deletion should be unsuccessful. Error is expected.");
    } catch (Exception e) {
      assertEquals(RouterErrorCode.AmbryUnavailable, ((RouterException) e.getCause()).getErrorCode());
    }
  }

  /**
   * Test the case where servers return different {@link ServerErrorCode}, and the
   * {@link DeleteOperation} is able to resolve and conclude the correct {@link RouterErrorCode}.
   * The parallelism is set to 3 not 9.
   */
  @Test
  public void testVariousServerErrorCodesForThreeParallelism()
      throws Exception {
    initialize(false, "3");
    ServerErrorCode[] serverErrorCodes =
        {ServerErrorCode.Blob_Not_Found, ServerErrorCode.Data_Corrupt, ServerErrorCode.IO_Error, ServerErrorCode.Partition_Unknown, ServerErrorCode.Disk_Unavailable, ServerErrorCode.No_Error, ServerErrorCode.Data_Corrupt, ServerErrorCode.Unknown_Error, ServerErrorCode.Disk_Unavailable};
    writeBlobRecordToServers(serverErrorCodes);
    future = router.deleteBlob(blobIdString, new UserCallback());
    try {
      future.get();
      fail("Deletion should be unsuccessful. Exception is expected.");
    } catch (Exception e) {
      assertEquals(RouterErrorCode.AmbryUnavailable, ((RouterException) e.getCause()).getErrorCode());
    }
  }

  /**
   * Test the case when request gets expired before the corresponding store server sends
   * back a response. Setting servers to not respond any requests, so {@link DeleteOperation}
   * can be "in flight".
   */
  @Test
  public void testResponseTimeout()
      throws Exception {
    // True will be passed to initialize() to indicate no response from the MockServer.
    initialize(true, "9");
    ServerErrorCode[] serverErrorCodes = new ServerErrorCode[9];
    Arrays.fill(serverErrorCodes, ServerErrorCode.No_Error);
    writeBlobRecordToServers(serverErrorCodes);
    future = router.deleteBlob(blobIdString, new UserCallback());
    do {
      // increment mock time
      mockTime.sleep(1000);
    } while (!operationCompleteLatch.await(10, TimeUnit.MILLISECONDS));
    try {
      future.get();
      fail("Deletion should be unsuccessful. Exception is expected.");
    } catch (Exception e) {
      assertEquals(RouterErrorCode.OperationTimedOut, ((RouterException) e.getCause()).getErrorCode());
    }
  }

  /**
   * Test the case when the {@link com.github.ambry.network.Selector} of
   * {@link com.github.ambry.network.NetworkClient} experiences various exceptions.
   */
  @Test
  public void testSelectorError()
      throws Exception {
    ServerErrorCode[] serverErrorCodes = new ServerErrorCode[9];
    Arrays.fill(serverErrorCodes, ServerErrorCode.No_Error);
    initialize(false, "9");
    HashMap<MockSelectorState, RouterErrorCode> errorCodeHashMap = new HashMap<MockSelectorState, RouterErrorCode>();
    errorCodeHashMap.put(MockSelectorState.DisconnectOnSend, RouterErrorCode.OperationTimedOut);
    errorCodeHashMap.put(MockSelectorState.ThrowExceptionOnAllPoll, RouterErrorCode.RouterClosed);
    errorCodeHashMap.put(MockSelectorState.ThrowExceptionOnConnect, RouterErrorCode.OperationTimedOut);
    errorCodeHashMap.put(MockSelectorState.ThrowExceptionOnSend, RouterErrorCode.RouterClosed);
    for (MockSelectorState state : MockSelectorState.values()) {
      if(state == MockSelectorState.Good) {
        continue;
      }
      mockSelectorState.set(state);
      writeBlobRecordToServers(serverErrorCodes);
      blobRecordVerification(serverErrorCodes);
      future = router.deleteBlob(blobIdString, new UserCallback());
      do {
        // increment mock time
        mockTime.sleep(1000);
      } while (!operationCompleteLatch.await(10, TimeUnit.MILLISECONDS));
      try {
        future.get();
        fail("Deletion should be unsuccessful. Exception is expected.");
      } catch (Exception e) {
        assertEquals(errorCodeHashMap.get(state), ((RouterException) e.getCause()).getErrorCode());
      }
    }
  }

  /**
   * Test the case how a {@link DeleteManager} acts when a router is closed, and when there are inflight
   * operations. Setting servers to not respond any requests, so {@link DeleteOperation} can be "in flight".
   * @throws Exception
   */
  @Test
  public void testRouterClosedDuringOperation()
      throws Exception {
    ServerErrorCode[] serverErrorCodes = new ServerErrorCode[9];
    Arrays.fill(serverErrorCodes, ServerErrorCode.No_Error);
    initialize(true, "9");
    writeBlobRecordToServers(serverErrorCodes);
    future = router.deleteBlob(blobIdString, new UserCallback());
    router.close();
    try {
      future.get();
      fail("Deletion should be unsuccessful. Exception is expected.");
    } catch (Exception e) {
      assertEquals(RouterErrorCode.RouterClosed, ((RouterException) e.getCause()).getErrorCode());
    }
  }

  /**
   * Prepare {@link MockServer}"s" so that each of them will respond to a request with a predefined
   * {@link ServerErrorCode}. The size of {@code serverErrorCodes} should be the same as the number
   * of nodes (i.e., {@link MockServer}"s") for a {@link com.github.ambry.clustermap.Partition}.
   * @param serverErrorCodes
   */
  private void writeBlobRecordToServers(ServerErrorCode[] serverErrorCodes) {
    int i = 0;
    for (ReplicaId replica : mockPartition.getReplicaIds()) {
      DataNodeId node = replica.getDataNodeId();
      MockServer mockServer = serverLayout.getMockServer(node.getHostname(), node.getPort());
      mockServer.setBlobIdToServerErrorCode(blobIdString, serverErrorCodes[i++]);
    }
    blobRecordVerification(serverErrorCodes);
  }

  /**
   * Verify if the {@link ServerErrorCode}s have been correctly written to {@link MockServer}.
   * @param serverErrorCodes
   */
  private void blobRecordVerification(ServerErrorCode[] serverErrorCodes) {
    int i = 0;
    List<ReplicaId> replicas = blobId.getPartition().getReplicaIds();
    for (ReplicaId replica : replicas) {
      DataNodeId node = replica.getDataNodeId();
      MockServer server = serverLayout.getMockServer(node.getHostname(), node.getPort());
      assertEquals(serverErrorCodes[i++], server.getErrorFromBlobIdStr(blobIdString));
    }
  }

  /**
   * User callback that is called when the {@link DeleteOperation} is completed.
   */
  private class UserCallback implements Callback<Void> {
    @Override
    public void onCompletion(Void t, Exception e) {
      operationCompleteLatch.countDown();
    }
  }
}
