/**
 * Copyright 2016 LinkedIn Corp. All rights reserved.
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
package com.github.ambry.tools.admin;

import com.codahale.metrics.MetricRegistry;
import com.github.ambry.clustermap.ClusterAgentsFactory;
import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.clustermap.DataNodeId;
import com.github.ambry.clustermap.PartitionId;
import com.github.ambry.commons.BlobId;
import com.github.ambry.commons.SSLFactory;
import com.github.ambry.commons.ServerErrorCode;
import com.github.ambry.config.ClusterMapConfig;
import com.github.ambry.config.Config;
import com.github.ambry.config.Default;
import com.github.ambry.config.NetworkConfig;
import com.github.ambry.config.SSLConfig;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.messageformat.BlobAll;
import com.github.ambry.messageformat.BlobData;
import com.github.ambry.messageformat.BlobProperties;
import com.github.ambry.messageformat.MessageFormatFlags;
import com.github.ambry.messageformat.MessageFormatRecord;
import com.github.ambry.network.NetworkClient;
import com.github.ambry.network.NetworkClientFactory;
import com.github.ambry.network.NetworkMetrics;
import com.github.ambry.network.Port;
import com.github.ambry.network.RequestInfo;
import com.github.ambry.network.ResponseInfo;
import com.github.ambry.network.Send;
import com.github.ambry.protocol.AdminRequest;
import com.github.ambry.protocol.AdminRequestOrResponseType;
import com.github.ambry.protocol.AdminResponse;
import com.github.ambry.protocol.CatchupStatusAdminRequest;
import com.github.ambry.protocol.CatchupStatusAdminResponse;
import com.github.ambry.protocol.GetOption;
import com.github.ambry.protocol.GetRequest;
import com.github.ambry.protocol.GetResponse;
import com.github.ambry.protocol.PartitionRequestInfo;
import com.github.ambry.protocol.ReplicationControlAdminRequest;
import com.github.ambry.protocol.RequestControlAdminRequest;
import com.github.ambry.protocol.RequestOrResponseType;
import com.github.ambry.store.StoreKeyFactory;
import com.github.ambry.tools.util.ToolUtils;
import com.github.ambry.utils.ByteBufferInputStream;
import com.github.ambry.utils.Pair;
import com.github.ambry.utils.SystemTime;
import com.github.ambry.utils.Time;
import com.github.ambry.utils.Utils;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Tool to support admin related operations on Ambry server
 * Currently supports:
 * 1. Get of either BlobProperties, UserMetadata or blob data for a particular blob from a particular storage node.
 * 2. Triggering of compaction of a particular partition on a particular node.
 */
public class ServerAdminTool implements Closeable {
  private static final int MAX_CONNECTIONS_PER_SERVER = 1;
  private static final int POLL_TIMEOUT_MS = 10;
  private static final int OPERATION_TIMEOUT_MS = 5000;
  private static final int CONNECTION_CHECKOUT_TIMEOUT_MS = 2000;
  private static final String CLIENT_ID = "ServerAdminTool";
  private static final Logger LOGGER = LoggerFactory.getLogger(ServerAdminTool.class);

  private final NetworkClient networkClient;
  private final AtomicInteger correlationId = new AtomicInteger(0);
  private final Time time = SystemTime.getInstance();

  /**
   * The different operations supported by the tool.
   */
  private enum Operation {
    GetBlobProperties, GetUserMetadata, GetBlob, TriggerCompaction, RequestControl, ReplicationControl, CatchupStatus
  }

  /**
   * Config values associated with the tool.
   */
  private static class ServerAdminToolConfig {

    /**
     * The path to the hardware layout file.
     */
    @Config("hardware.layout.file.path")
    final String hardwareLayoutFilePath;

    /**
     * The path to the partition layout file.
     */
    @Config("partition.layout.file.path")
    final String partitionLayoutFilePath;

    /**
     * The type of operation.
     * Operations are: GetBlobProperties,GetUserMetadata,GetBlob,TriggerCompaction,RequestControl,ReplicationControl,
     * CatchupStatus
     */
    @Config("type.of.operation")
    final Operation typeOfOperation;

    /**
     * The hostname of the target server as it appears in the partition layout.
     */
    @Config("hostname")
    @Default("localhost")
    final String hostname;

    /**
     * The port of the target server in the partition layout (need not be the actual port to connect to).
     */
    @Config("port")
    @Default("6667")
    final int port;

    /**
     * The id of the blob to operate on (if applicable).
     * Applicable for: GetBlobProperties,GetUserMetadata,GetBlob
     */
    @Config("blob.id")
    @Default("")
    final String blobId;

    /**
     * The get option to use to do the get operation (if applicable)
     * Applicable for: GetBlobProperties,GetUserMetadata,GetBlob
     */
    @Config("get.option")
    @Default("None")
    final GetOption getOption;

    /**
     * Comma separated list of the string representations of the partitions to operate on (if applicable).
     * Some requests (TriggerCompaction) will not work with an empty list but some requests treat empty lists as "all
     * partitions" (RequestControl,ReplicationControl,CatchupStatus).
     * Applicable for: TriggerCompaction,RequestControl,ReplicationControl,CatchupStatus
     */
    @Config("partition.ids")
    @Default("")
    final String[] partitionIds;

    /**
     * The type of request to control
     * Applicable for: RequestControl
     */
    @Config("request.type.to.control")
    @Default("PutRequest")
    final RequestOrResponseType requestTypeToControl;

    /**
     * Enables the request type or replication if {@code true}. Disables if {@code false}.
     * Applicable for: RequestControl,ReplicationControl
     */
    @Config("enable.state")
    @Default("true")
    final boolean enableState;

    /**
     * The comma separated names of the datacenters from which replication should be controlled.
     * Applicable for: ReplicationControl
     */
    @Config("replication.origins")
    @Default("")
    final String[] origins;

    /**
     * The acceptable lag in bytes in case of catchup status requests
     * Applicable for: CatchupStatus
     */
    @Config("acceptable.lag.in.bytes")
    @Default("0")
    final long acceptableLagInBytes;

    /**
     * Path of the file where the data from certain operations will output. For example, the blob from GetBlob and the
     * user metadata from GetUserMetadata will be written into this file.
     */
    @Config("data.output.file.path")
    @Default("/tmp/ambryResult.out")
    final String dataOutputFilePath;

    /**
     * Constructs the configs associated with the tool.
     * @param verifiableProperties the props to use to load the config.
     */
    ServerAdminToolConfig(VerifiableProperties verifiableProperties) {
      hardwareLayoutFilePath = verifiableProperties.getString("hardware.layout.file.path");
      partitionLayoutFilePath = verifiableProperties.getString("partition.layout.file.path");
      typeOfOperation = Operation.valueOf(verifiableProperties.getString("type.of.operation"));
      hostname = verifiableProperties.getString("hostname", "localhost");
      port = verifiableProperties.getIntInRange("port", 6667, 1, 65535);
      blobId = verifiableProperties.getString("blob.id", "");
      getOption = GetOption.valueOf(verifiableProperties.getString("get.option", "None"));
      partitionIds = verifiableProperties.getString("partition.ids", "").split(",");
      requestTypeToControl =
          RequestOrResponseType.valueOf(verifiableProperties.getString("request.type.to.control", "PutRequest"));
      enableState = verifiableProperties.getBoolean("enable.state", true);
      origins = verifiableProperties.getString("replication.origins", "").split(",");
      acceptableLagInBytes = verifiableProperties.getLongInRange("acceptable.lag.in.bytes", 0, 0, Long.MAX_VALUE);
      dataOutputFilePath = verifiableProperties.getString("data.output.file.path", "/tmp/ambryResult.out");
    }
  }

  /**
   * Runs the server admin tool
   * @param args associated arguments.
   * @throws Exception
   */
  public static void main(String args[]) throws Exception {
    VerifiableProperties verifiableProperties = ToolUtils.getVerifiableProperties(args);
    ServerAdminToolConfig config = new ServerAdminToolConfig(verifiableProperties);
    ClusterMapConfig clusterMapConfig = new ClusterMapConfig(verifiableProperties);
    ClusterMap clusterMap =
        ((ClusterAgentsFactory) Utils.getObj(clusterMapConfig.clusterMapClusterAgentsFactory, clusterMapConfig,
            config.hardwareLayoutFilePath, config.partitionLayoutFilePath)).getClusterMap();
    SSLFactory sslFactory = !clusterMapConfig.clusterMapSslEnabledDatacenters.isEmpty() ? new SSLFactory(
        new SSLConfig(verifiableProperties)) : null;
    ServerAdminTool serverAdminTool =
        new ServerAdminTool(clusterMap.getMetricRegistry(), sslFactory, verifiableProperties);
    File file = new File(config.dataOutputFilePath);
    if (!file.exists() && !file.createNewFile()) {
      throw new IllegalStateException("Could not create " + file);
    }
    FileOutputStream outputFileStream = new FileOutputStream(config.dataOutputFilePath);
    DataNodeId dataNodeId = clusterMap.getDataNodeId(config.hostname, config.port);
    if (dataNodeId == null) {
      throw new IllegalArgumentException(
          "Could not find a data node corresponding to " + config.hostname + ":" + config.port);
    }
    switch (config.typeOfOperation) {
      case GetBlobProperties:
        BlobId blobId = new BlobId(config.blobId, clusterMap);
        Pair<ServerErrorCode, BlobProperties> bpResponse =
            serverAdminTool.getBlobProperties(dataNodeId, blobId, config.getOption, clusterMap);
        if (bpResponse.getFirst() == ServerErrorCode.No_Error) {
          LOGGER.info("Blob properties for {} from {}: {}", blobId, dataNodeId, bpResponse.getSecond());
        } else {
          LOGGER.error("Failed to get blob properties for {} from {} with option {}. Error code is {}", blobId,
              dataNodeId, config.getOption, bpResponse.getFirst());
        }
        break;
      case GetUserMetadata:
        blobId = new BlobId(config.blobId, clusterMap);
        Pair<ServerErrorCode, ByteBuffer> umResponse =
            serverAdminTool.getUserMetadata(dataNodeId, blobId, config.getOption, clusterMap);
        if (umResponse.getFirst() == ServerErrorCode.No_Error) {
          writeBufferToFile(umResponse.getSecond(), outputFileStream);
          LOGGER.info("User metadata for {} from {} written to {}", blobId, dataNodeId, config.dataOutputFilePath);
        } else {
          LOGGER.error("Failed to get user metadata for {} from {} with option {}. Error code is {}", blobId,
              dataNodeId, config.getOption, umResponse.getFirst());
        }
        break;
      case GetBlob:
        blobId = new BlobId(config.blobId, clusterMap);
        Pair<ServerErrorCode, BlobData> bResponse =
            serverAdminTool.getBlob(dataNodeId, blobId, config.getOption, clusterMap);
        if (bResponse.getFirst() == ServerErrorCode.No_Error) {
          LOGGER.info("Blob type of {} from {} is {}", blobId, dataNodeId, bResponse.getSecond().getBlobType());
          writeBufferToFile(bResponse.getSecond().getStream().getByteBuffer(), outputFileStream);
          LOGGER.info("Blob data for {} from {} written to {}", blobId, dataNodeId, config.dataOutputFilePath);
        } else {
          LOGGER.error("Failed to get blob data for {} from {} with option {}. Error code is {}", blobId, dataNodeId,
              config.getOption, bResponse.getFirst());
        }
        break;
      case TriggerCompaction:
        if (config.partitionIds.length > 0 && !config.partitionIds[0].isEmpty()) {
          for (String partitionId : config.partitionIds) {
            ServerErrorCode errorCode = serverAdminTool.triggerCompaction(dataNodeId, partitionId, clusterMap);
            if (errorCode == ServerErrorCode.No_Error) {
              LOGGER.info("Compaction has been triggered for {} on {}", partitionId, dataNodeId);
            } else {
              LOGGER.error("From {}, received server error code {} for trigger compaction request on {}", dataNodeId,
                  errorCode, partitionId);
            }
          }
        } else {
          LOGGER.error("There were no partitions provided to trigger compaction on");
        }
        break;
      case RequestControl:
        if (config.partitionIds.length > 0 && !config.partitionIds[0].isEmpty()) {
          for (String partitionId : config.partitionIds) {
            sendRequestControlRequest(serverAdminTool, clusterMap, dataNodeId, partitionId, config.requestTypeToControl,
                config.enableState);
          }
        } else {
          LOGGER.info("No partition list provided. Requesting enable status of {} to be set to {} on all partitions",
              config.requestTypeToControl, config.enableState);
          sendRequestControlRequest(serverAdminTool, clusterMap, dataNodeId, null, config.requestTypeToControl,
              config.enableState);
        }
        break;
      case ReplicationControl:
        List<String> origins = Arrays.asList(config.origins);
        if (config.partitionIds.length > 0 && !config.partitionIds[0].isEmpty()) {
          for (String partitionId : config.partitionIds) {
            sendReplicationControlRequest(serverAdminTool, clusterMap, dataNodeId, partitionId, origins,
                config.enableState);
          }
        } else {
          LOGGER.info("No partition list provided. Requesting enable status for replication from {} to be set to {} on "
              + "all partitions", origins, config.enableState);
          sendReplicationControlRequest(serverAdminTool, clusterMap, dataNodeId, null, origins, config.enableState);
        }
        break;
      case CatchupStatus:
        if (config.partitionIds.length > 0 && !config.partitionIds[0].isEmpty()) {
          for (String partitionId : config.partitionIds) {
            Pair<ServerErrorCode, Boolean> response =
                serverAdminTool.isCaughtUp(dataNodeId, partitionId, config.acceptableLagInBytes, clusterMap);
            if (response.getFirst() == ServerErrorCode.No_Error) {
              LOGGER.info("Replicas are {} within {} for {}", response.getSecond() ? "" : "NOT",
                  config.acceptableLagInBytes, partitionId);
            } else {
              LOGGER.error("From {}, received server error code {} for request for catchup status of {}", dataNodeId,
                  response.getFirst(), partitionId);
            }
          }
        } else {
          Pair<ServerErrorCode, Boolean> response =
              serverAdminTool.isCaughtUp(dataNodeId, null, config.acceptableLagInBytes, clusterMap);
          if (response.getFirst() == ServerErrorCode.No_Error) {
            LOGGER.info("Replicas are {} within {} for all partitions", response.getSecond() ? "" : "NOT",
                config.acceptableLagInBytes);
          } else {
            LOGGER.error("From {}, received server error code {} for request for catchup status of all partitions",
                dataNodeId, response.getFirst());
          }
        }
        break;
      default:
        throw new IllegalStateException("Recognized but unsupported operation: " + config.typeOfOperation);
    }
    serverAdminTool.close();
    outputFileStream.close();
    clusterMap.close();
  }

  /**
   * Writes the content of {@code buffer} into {@link ServerAdminToolConfig#dataOutputFilePath}.
   * @param buffer the {@link ByteBuffer} whose content needs to be written.
   * @param outputFileStream the {@link FileOutputStream} to write to.
   * @throws IOException
   */
  private static void writeBufferToFile(ByteBuffer buffer, FileOutputStream outputFileStream) throws IOException {
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    outputFileStream.write(bytes);
  }

  /**
   * Sends a {@link RequestControlAdminRequest} to {@code dataNodeId} to set enable status of {@code toControl} to
   * {@code enable} for {@code partitionId}.
   * @param serverAdminTool the {@link ServerAdminTool} instance to use.
   * @param clusterMap the {@link ClusterMap} to use.
   * @param dataNodeId the {@link DataNodeId} to send the request to.
   * @param partitionId the partition id (string) on which the operation will take place. Can be {@code null}.
   * @param toControl the {@link RequestOrResponseType} to control.
   * @param enable the enable (or disable) status required for {@code toControl}.
   * @throws IOException
   * @throws TimeoutException
   */
  private static void sendRequestControlRequest(ServerAdminTool serverAdminTool, ClusterMap clusterMap,
      DataNodeId dataNodeId, String partitionId, RequestOrResponseType toControl, boolean enable)
      throws IOException, TimeoutException {
    ServerErrorCode errorCode = serverAdminTool.controlRequest(dataNodeId, partitionId, toControl, enable, clusterMap);
    if (errorCode == ServerErrorCode.No_Error) {
      LOGGER.info("{} enable state has been set to {} for {} on {}", toControl, enable, partitionId, dataNodeId);
    } else {
      LOGGER.error("From {}, received server error code {} for request to set enable state {} for {} on {}", dataNodeId,
          errorCode, enable, toControl, partitionId);
    }
  }

  /**
   * Sends a {@link ReplicationControlAdminRequest} to {@code dataNodeId} to set enable status of replication from
   * {@code origins} to {@code enable} for {@code partitionId}.
   * @param serverAdminTool the {@link ServerAdminTool} instance to use.
   * @param clusterMap the {@link ClusterMap} to use.
   * @param dataNodeId the {@link DataNodeId} to send the request to.
   * @param partitionId the partition id (string) on which the operation will take place. Can be {@code null}.
   * @param origins the names of the datacenters from which replication should be controlled.
   * @param enable the enable (or disable) status required for {@code toControl}.
   * @throws IOException
   * @throws TimeoutException
   */
  private static void sendReplicationControlRequest(ServerAdminTool serverAdminTool, ClusterMap clusterMap,
      DataNodeId dataNodeId, String partitionId, List<String> origins, boolean enable)
      throws IOException, TimeoutException {
    ServerErrorCode errorCode =
        serverAdminTool.controlReplication(dataNodeId, partitionId, origins, enable, clusterMap);
    if (errorCode == ServerErrorCode.No_Error) {
      LOGGER.info("Enable state of replication from {} has been set to {} for {} on {}", origins, enable, partitionId,
          dataNodeId);
    } else {
      LOGGER.error(
          "From {}, received server error code {} for request to set enable state {} for replication from {} for {}",
          dataNodeId, errorCode, enable, origins, partitionId);
    }
  }

  /**
   * Creates an instance of the server admin tool
   * @param metricRegistry the {@link MetricRegistry} to use for metrics
   * @param sslFactory the {@link SSLFactory} to use
   * @param verifiableProperties the {@link VerifiableProperties} to use for config.
   * @throws Exception
   */
  public ServerAdminTool(MetricRegistry metricRegistry, SSLFactory sslFactory,
      VerifiableProperties verifiableProperties) throws Exception {
    NetworkMetrics metrics = new NetworkMetrics(metricRegistry);
    NetworkConfig config = new NetworkConfig(verifiableProperties);
    networkClient =
        new NetworkClientFactory(metrics, config, sslFactory, MAX_CONNECTIONS_PER_SERVER, MAX_CONNECTIONS_PER_SERVER,
            CONNECTION_CHECKOUT_TIMEOUT_MS, time).getNetworkClient();
  }

  /**
   * Releases all resources associated with the tool.
   * @throws IOException
   */
  @Override
  public void close() throws IOException {
    networkClient.close();
  }

  /**
   * Gets {@link BlobProperties} for {@code blobId}.
   * @param dataNodeId the {@link DataNodeId} to contact.
   * @param blobId the {@link BlobId} to operate on.
   * @param getOption the {@link GetOption} to send with the {@link GetRequest}.
   * @param clusterMap the {@link ClusterMap} to use.
   * @return the {@link ServerErrorCode} and {@link BlobProperties} of {@code blobId}.
   * @throws Exception
   */
  public Pair<ServerErrorCode, BlobProperties> getBlobProperties(DataNodeId dataNodeId, BlobId blobId,
      GetOption getOption, ClusterMap clusterMap) throws Exception {
    Pair<ServerErrorCode, InputStream> response =
        getGetResponse(dataNodeId, blobId, MessageFormatFlags.BlobProperties, getOption, clusterMap);
    InputStream stream = response.getSecond();
    BlobProperties blobProperties = stream != null ? MessageFormatRecord.deserializeBlobProperties(stream) : null;
    return new Pair<>(response.getFirst(), blobProperties);
  }

  /**
   * Gets user metadata for {@code blobId}.
   * @param dataNodeId the {@link DataNodeId} to contact.
   * @param blobId the {@link BlobId} to operate on.
   * @param getOption the {@link GetOption} to send with the {@link GetRequest}.
   * @param clusterMap the {@link ClusterMap} to use.
   * @return the {@link ServerErrorCode} and user metadata as a {@link ByteBuffer} for {@code blobId}
   * @throws Exception
   */
  public Pair<ServerErrorCode, ByteBuffer> getUserMetadata(DataNodeId dataNodeId, BlobId blobId, GetOption getOption,
      ClusterMap clusterMap) throws Exception {
    Pair<ServerErrorCode, InputStream> response =
        getGetResponse(dataNodeId, blobId, MessageFormatFlags.BlobUserMetadata, getOption, clusterMap);
    InputStream stream = response.getSecond();
    ByteBuffer userMetadata = stream != null ? MessageFormatRecord.deserializeUserMetadata(stream) : null;
    return new Pair<>(response.getFirst(), userMetadata);
  }

  /**
   * Gets blob data for {@code blobId}.
   * @param dataNodeId the {@link DataNodeId} to contact.
   * @param blobId the {@link BlobId} to operate on.
   * @param getOption the {@link GetOption} to send with the {@link GetRequest}.
   * @param clusterMap the {@link ClusterMap} to use.
   * @return the {@link ServerErrorCode} and {@link BlobData} for {@code blobId}
   * @throws Exception
   */
  public Pair<ServerErrorCode, BlobData> getBlob(DataNodeId dataNodeId, BlobId blobId, GetOption getOption,
      ClusterMap clusterMap) throws Exception {
    Pair<ServerErrorCode, InputStream> response =
        getGetResponse(dataNodeId, blobId, MessageFormatFlags.Blob, getOption, clusterMap);
    InputStream stream = response.getSecond();
    BlobData blobData = stream != null ? MessageFormatRecord.deserializeBlob(stream) : null;
    return new Pair<>(response.getFirst(), blobData);
  }

  /**
   * Gets all data for {@code blobId}.
   * @param dataNodeId the {@link DataNodeId} to contact.
   * @param blobId the {@link BlobId} to operate on.
   * @param getOption the {@link GetOption} to send with the {@link GetRequest}.
   * @param clusterMap the {@link ClusterMap} to use.
   * @param storeKeyFactory the {@link StoreKeyFactory} to use.
   * @return the {@link ServerErrorCode} and {@link BlobAll} for {@code blobId}
   * @throws Exception
   */
  public Pair<ServerErrorCode, BlobAll> getAll(DataNodeId dataNodeId, BlobId blobId, GetOption getOption,
      ClusterMap clusterMap, StoreKeyFactory storeKeyFactory) throws Exception {
    Pair<ServerErrorCode, InputStream> response =
        getGetResponse(dataNodeId, blobId, MessageFormatFlags.All, getOption, clusterMap);
    InputStream stream = response.getSecond();
    BlobAll blobAll = stream != null ? MessageFormatRecord.deserializeBlobAll(stream, storeKeyFactory) : null;
    return new Pair<>(response.getFirst(), blobAll);
  }

  /**
   * Triggers compaction on {@code dataNodeId} for the partition defined in {@code partitionIdStr}.
   * @param dataNodeId the {@link DataNodeId} to contact.
   * @param partitionIdStr the String representation of the {@link PartitionId} to compact.
   * @param clusterMap the {@link ClusterMap} to use.
   * @return the {@link ServerErrorCode} that is returned.
   * @throws IOException
   * @throws TimeoutException
   */
  public ServerErrorCode triggerCompaction(DataNodeId dataNodeId, String partitionIdStr, ClusterMap clusterMap)
      throws IOException, TimeoutException {
    PartitionId targetPartitionId = getPartitionIdFromStr(partitionIdStr, clusterMap);
    AdminRequest adminRequest = new AdminRequest(AdminRequestOrResponseType.TriggerCompaction, targetPartitionId,
        correlationId.incrementAndGet(), CLIENT_ID);
    ByteBuffer responseBytes = sendRequestGetResponse(dataNodeId, adminRequest);
    AdminResponse adminResponse = AdminResponse.readFrom(new DataInputStream(new ByteBufferInputStream(responseBytes)));
    return adminResponse.getError();
  }

  /**
   * Sends a {@link RequestControlAdminRequest} to set the enable state of {@code toControl} on {@code partitionIdStr}
   * to {@code enable} in {@code dataNodeId}.
   * @param dataNodeId the {@link DataNodeId} to contact.
   * @param partitionIdStr the String representation of the {@link PartitionId} to compact. Can be {@code null}.
   * @param toControl the {@link RequestOrResponseType} to control.
   * @param enable the enable (or disable) status required for {@code toControl}.
   * @param clusterMap the {@link ClusterMap} to use.
   * @return the {@link ServerErrorCode} that is returned.
   * @throws IOException
   * @throws TimeoutException
   */
  public ServerErrorCode controlRequest(DataNodeId dataNodeId, String partitionIdStr, RequestOrResponseType toControl,
      boolean enable, ClusterMap clusterMap) throws IOException, TimeoutException {
    PartitionId targetPartitionId = getPartitionIdFromStr(partitionIdStr, clusterMap);
    AdminRequest adminRequest =
        new AdminRequest(AdminRequestOrResponseType.RequestControl, targetPartitionId, correlationId.incrementAndGet(),
            CLIENT_ID);
    RequestControlAdminRequest controlRequest = new RequestControlAdminRequest(toControl, enable, adminRequest);
    ByteBuffer responseBytes = sendRequestGetResponse(dataNodeId, controlRequest);
    AdminResponse adminResponse = AdminResponse.readFrom(new DataInputStream(new ByteBufferInputStream(responseBytes)));
    return adminResponse.getError();
  }

  /**
   * Sends a {@link RequestControlAdminRequest} to enable/disable replication from {@code origins} for
   * {@code partitionIdStr} in {@code dataNodeId}.
   * @param dataNodeId the {@link DataNodeId} to contact.
   * @param partitionIdStr the String representation of the {@link PartitionId} to compact. Can be {@code null}.
   * @param origins the names of the datacenters from which replication should be controlled.
   * @param enable the enable (or disable) status required for replication from {@code origins}.
   * @param clusterMap the {@link ClusterMap} to use.
   * @return the {@link ServerErrorCode} that is returned.
   * @throws IOException
   * @throws TimeoutException
   */
  public ServerErrorCode controlReplication(DataNodeId dataNodeId, String partitionIdStr, List<String> origins,
      boolean enable, ClusterMap clusterMap) throws IOException, TimeoutException {
    PartitionId targetPartitionId = getPartitionIdFromStr(partitionIdStr, clusterMap);
    AdminRequest adminRequest = new AdminRequest(AdminRequestOrResponseType.ReplicationControl, targetPartitionId,
        correlationId.incrementAndGet(), CLIENT_ID);
    ReplicationControlAdminRequest controlRequest = new ReplicationControlAdminRequest(origins, enable, adminRequest);
    ByteBuffer responseBytes = sendRequestGetResponse(dataNodeId, controlRequest);
    AdminResponse adminResponse = AdminResponse.readFrom(new DataInputStream(new ByteBufferInputStream(responseBytes)));
    return adminResponse.getError();
  }

  /**
   * Sends a {@link CatchupStatusAdminRequest} for {@code partitionIdStr} to {@code dataNodeId}.
   * @param dataNodeId the {@link DataNodeId} to contact.
   * @param partitionIdStr the String representation of the {@link PartitionId} to compact. If {@code null}, status is
   *                       for all partitions on {@code dataNodeId}
   * @param acceptableLagInBytes that lag in bytes that is considered OK.
   * @param clusterMap the {@link ClusterMap} to use.
   * @return the {@link ServerErrorCode} and the catchup status that is returned.
   * @throws IOException
   * @throws TimeoutException
   */
  public Pair<ServerErrorCode, Boolean> isCaughtUp(DataNodeId dataNodeId, String partitionIdStr,
      long acceptableLagInBytes, ClusterMap clusterMap) throws IOException, TimeoutException {
    PartitionId targetPartitionId = getPartitionIdFromStr(partitionIdStr, clusterMap);
    AdminRequest adminRequest =
        new AdminRequest(AdminRequestOrResponseType.CatchupStatus, targetPartitionId, correlationId.incrementAndGet(),
            CLIENT_ID);
    CatchupStatusAdminRequest catchupStatusRequest = new CatchupStatusAdminRequest(acceptableLagInBytes, adminRequest);
    ByteBuffer responseBytes = sendRequestGetResponse(dataNodeId, catchupStatusRequest);
    CatchupStatusAdminResponse response =
        CatchupStatusAdminResponse.readFrom(new DataInputStream(new ByteBufferInputStream(responseBytes)));
    return new Pair<>(response.getError(), response.isCaughtUp());
  }

  /**
   * Gets the {@link PartitionId} in the {@code clusterMap} whose string representation matches {@code partitionIdStr}.
   * @param partitionIdStr the string representation of the partition required.
   * @param clusterMap the {@link ClusterMap} to use to list and process {@link PartitionId}s.
   * @return the {@link PartitionId} in the {@code clusterMap} whose string repr matches {@code partitionIdStr}.
   * {@code null} if {@code partitionIdStr} is {@code null}.
   * @throws IllegalArgumentException if there is no @link PartitionId} in the {@code clusterMap} whose string repr
   * matches {@code partitionIdStr}.
   */
  private PartitionId getPartitionIdFromStr(String partitionIdStr, ClusterMap clusterMap) {
    if (partitionIdStr == null) {
      return null;
    }
    PartitionId targetPartitionId = null;
    List<? extends PartitionId> partitionIds = clusterMap.getAllPartitionIds();
    for (PartitionId partitionId : partitionIds) {
      if (partitionId.isEqual(partitionIdStr)) {
        targetPartitionId = partitionId;
        break;
      }
    }
    if (targetPartitionId == null) {
      throw new IllegalArgumentException("Partition Id is not valid: [" + partitionIdStr + "]");
    }
    return targetPartitionId;
  }

  /**
   * Sends a {@link GetRequest} based on the provided parameters and returns the response stream if the request was
   * successful. {@code null} otherwise.
   * @param dataNodeId the {@link DataNodeId} to contact.
   * @param blobId the {@link BlobId} to operate on.
   * @param flags the {@link MessageFormatFlags} associated with the {@link GetRequest}.
   * @param getOption the {@link GetOption} to send with the {@link GetRequest}.
   * @param clusterMap the {@link ClusterMap} to use.
   * @return the {@link ServerErrorCode} and response stream if the request was successful. {@code null} for the
   * response stream otherwise.
   * @throws Exception
   */
  private Pair<ServerErrorCode, InputStream> getGetResponse(DataNodeId dataNodeId, BlobId blobId,
      MessageFormatFlags flags, GetOption getOption, ClusterMap clusterMap) throws Exception {
    PartitionRequestInfo partitionRequestInfo =
        new PartitionRequestInfo(blobId.getPartition(), Collections.singletonList(blobId));
    List<PartitionRequestInfo> partitionRequestInfos = new ArrayList<>();
    partitionRequestInfos.add(partitionRequestInfo);
    GetRequest getRequest =
        new GetRequest(correlationId.incrementAndGet(), CLIENT_ID, flags, partitionRequestInfos, getOption);
    InputStream serverResponseStream = new ByteBufferInputStream(sendRequestGetResponse(dataNodeId, getRequest));
    GetResponse getResponse = GetResponse.readFrom(new DataInputStream(serverResponseStream), clusterMap);
    ServerErrorCode partitionErrorCode = getResponse.getPartitionResponseInfoList().get(0).getErrorCode();
    ServerErrorCode errorCode =
        partitionErrorCode == ServerErrorCode.No_Error ? getResponse.getError() : partitionErrorCode;
    InputStream stream = errorCode == ServerErrorCode.No_Error ? getResponse.getInputStream() : null;
    return new Pair<>(errorCode, stream);
  }

  /**
   * Sends {@code request} to {@code dataNodeId} and returns the response as a {@link ByteBuffer}.
   * @param dataNodeId the {@link DataNodeId} to contact.
   * @param request the request to send.
   * @return the response as a {@link ByteBuffer} if the response was successfully received. {@code null} otherwise.
   * @throws TimeoutException
   */
  private ByteBuffer sendRequestGetResponse(DataNodeId dataNodeId, Send request) throws TimeoutException {
    String hostname = dataNodeId.getHostname();
    Port port = dataNodeId.getPortToConnectTo();
    String identifier = hostname + ":" + port.getPort();
    RequestInfo requestInfo = new RequestInfo(hostname, port, request);
    List<RequestInfo> requestInfos = Collections.singletonList(requestInfo);
    ResponseInfo responseInfo = null;
    long startTimeMs = time.milliseconds();
    do {
      if (time.milliseconds() - startTimeMs > OPERATION_TIMEOUT_MS) {
        throw new TimeoutException(identifier + ": Operation did not complete within " + OPERATION_TIMEOUT_MS + " ms");
      }
      List<ResponseInfo> responseInfos = networkClient.sendAndPoll(requestInfos, POLL_TIMEOUT_MS);
      if (responseInfos.size() > 1) {
        throw new IllegalStateException("Received more than one response even though a single request was sent");
      } else if (!responseInfos.isEmpty()) {
        responseInfo = responseInfos.get(0);
      }
      requestInfos = Collections.EMPTY_LIST;
    } while (responseInfo == null);
    if (responseInfo.getError() != null) {
      throw new IllegalStateException(
          identifier + ": Encountered error while trying to send request - " + responseInfo.getError());
    }
    return responseInfo.getResponse();
  }
}
