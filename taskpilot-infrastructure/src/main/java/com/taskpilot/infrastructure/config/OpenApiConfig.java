package com.taskpilot.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI taskPilotOpenAPI() {
        String schemaName = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("TaskPilot API")
                        .description("AI-powered Task Allocation System")
                        .version("v1.0"))

                .addSecurityItem(new SecurityRequirement().addList(schemaName))
                .components(new Components()
                        .addSecuritySchemes(schemaName,
                                new SecurityScheme()
                                        .name(schemaName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")

                        )
                );
    }
}
