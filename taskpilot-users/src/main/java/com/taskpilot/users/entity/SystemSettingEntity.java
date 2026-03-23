package com.taskpilot.users.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "system_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemSettingEntity {
    @Id
    @Column(name = "key_name")
    private String keyName;

    @Column(name = "value_json", nullable = false, columnDefinition = "jsonb")
    private String valueJson;

    private String description;
}
