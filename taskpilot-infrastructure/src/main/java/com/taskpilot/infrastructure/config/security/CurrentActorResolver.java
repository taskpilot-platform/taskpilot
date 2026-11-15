package com.taskpilot.infrastructure.config.security;

import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.contracts.user.dto.RealtimeAccessContext;
import com.taskpilot.infrastructure.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Optional;

/**
 * Extracts typed {@link CurrentActor} and immutable numeric {@code userId} from Spring Security
 * or WebSocket {@link Principal} instances. Fails closed on null, anonymous, or raw-string principals.
 */
@Component
public class CurrentActorResolver {

    public static Optional<CurrentActor> extract(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof TaskPilotPrincipal typedPrincipal) {
            return Optional.of(typedPrincipal.actor());
        }
        return Optional.empty();
    }

    public static Optional<CurrentActor> extract(Principal principal) {
        if (principal instanceof TaskPilotPrincipal typedPrincipal) {
            return Optional.of(typedPrincipal.actor());
        }
        if (principal instanceof Authentication authentication) {
            return extract(authentication);
        }
        return Optional.empty();
    }

    public static Optional<RealtimeAccessContext> extractRealtimeContext(Principal principal) {
        if (principal instanceof TaskPilotPrincipal typedPrincipal) {
            return Optional.ofNullable(typedPrincipal.realtimeContext());
        }
        if (principal instanceof Authentication authentication
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof TaskPilotPrincipal typedPrincipal) {
            return Optional.ofNullable(typedPrincipal.realtimeContext());
        }
        return Optional.empty();
    }

    public static CurrentActor resolve(Authentication authentication) {
        return extract(authentication).orElseThrow(CurrentActorResolver::unauthorized);
    }

    public static CurrentActor resolve(Principal principal) {
        return extract(principal).orElseThrow(CurrentActorResolver::unauthorized);
    }

    public static long userId(Authentication authentication) {
        return resolve(authentication).userId();
    }

    public static long userId(Principal principal) {
        return resolve(principal).userId();
    }

    public CurrentActor currentActor(Authentication authentication) {
        return resolve(authentication);
    }

    public CurrentActor currentActor(Principal principal) {
        return resolve(principal);
    }

    public long requireUserId(Authentication authentication) {
        return userId(authentication);
    }

    public long requireUserId(Principal principal) {
        return userId(principal);
    }

    private static BusinessException unauthorized() {
        return new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Authentication required");
    }
}
