package com.taskpilot.contracts.user.port.out;

/**
 * Port checking whether an access token's {@code jti} has been explicitly blocklisted.
 */
public interface RealtimeTokenBlocklistPort {

    boolean isTokenRevoked(String tokenId);
}
