package com.github.ambry.router;

import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.commons.BlobId;
import com.github.ambry.rest.RestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This is a utility class used by Router.
 */
public class RouterUtils {

  private static Logger logger = LoggerFactory.getLogger(RestUtils.class);

  /**
   * Get {@link BlobId} from a blob string.
   * @param blobIdString The string of blobId.
   * @param clusterMap The {@link ClusterMap} based on which to generate the {@link BlobId}.
   * @return BlobId
   * @throws RouterException If parsing a string blobId fails.
   */
  static BlobId getBlobIdFromString(String blobIdString, ClusterMap clusterMap)
      throws RouterException {
    if (blobIdString == null || blobIdString.length() == 0) {
      logger.error("BlobIdString argument is null or zero length: {}", blobIdString);
      throw new RouterException("BlobId is empty.", RouterErrorCode.InvalidBlobId);
    }

    BlobId blobId;
    try {
      blobId = new BlobId(blobIdString, clusterMap);
      logger.trace("BlobId created " + blobId + " with partition " + blobId.getPartition());
    } catch (Exception e) {
      logger.error("Caller passed in invalid BlobId " + blobIdString);
      throw new RouterException("BlobId is invalid " + blobIdString, RouterErrorCode.InvalidBlobId);
    }
    return blobId;
  }
}