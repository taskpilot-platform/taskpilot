package com.taskpilot.infrastructure.realtime;

import com.taskpilot.contracts.user.port.out.RealtimeTokenBlocklistPort;
import com.taskpilot.infrastructure.config.security.JwtTokenBlocklistService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Infrastructure adapter exposing JWT {@code jti} blocklist state to {@link RealtimeTokenBlocklistPort}.
 */
@Component
@RequiredArgsConstructor
public class RealtimeJwtBlocklistAdapter implements RealtimeTokenBlocklistPort {

    private final JwtTokenBlocklistService jwtTokenBlocklistService;

    @Override
    public boolean isTokenRevoked(String tokenId) {
        return jwtTokenBlocklistService != null && jwtTokenBlocklistService.isJtiRevoked(tokenId);
    }
}
