package com.taskpilot.contracts.assignment.port.out;

import java.util.Map;
import java.util.Optional;

public interface SystemSettingPort {

    Optional<Map<String, Object>> findJsonObjectByKey(String keyName);
}
