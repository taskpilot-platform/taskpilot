package com.taskpilot.contracts.aiquery.port.out;

import com.taskpilot.contracts.aiquery.dto.AiResourceRef;
import java.util.List;

public interface AiResourceAccessPort {
    /** Reauthorize all references, bind them to one project, and return an opaque current revision.
     * lock=true participates in the caller's transaction: projects, accounts, then resources. */
    String fingerprint(long userId, List<AiResourceRef> resources, List<Long> targetUsers, boolean lock);
}
