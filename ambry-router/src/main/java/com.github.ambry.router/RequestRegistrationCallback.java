package com.github.ambry.router;

import com.github.ambry.network.RequestInfo;


/**
 * The callback to be used when delete requests are created and needs to be sent out. The {@link DeleteManager} passes this
 * callback to the {@link DeleteOperation} and the {@link DeleteOperation} uses this callback when requests are created and
 * need to be sent out.
 */
public interface RequestRegistrationCallback {
  public void registerRequestToSend(DeleteOperation deleteOperation, RequestInfo request);
}