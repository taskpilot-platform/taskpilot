package com.taskpilot.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.app.TaskPilotApplication;
import com.taskpilot.app.migration.MigrationDatabaseConfiguration;
import com.taskpilot.ai.dto.ConfirmationRequiredDto;
import com.taskpilot.ai.service.*;
import com.taskpilot.ai.tools.*;
import com.taskpilot.ai.entity.AiLogEntity;
import com.taskpilot.ai.repository.AiLogRepository;
import com.taskpilot.contracts.aiquery.port.out.TaskCommandPort;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.repository.UserRepository;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP, transactions and resource authorization on an isolated reconciled PostgreSQL DB. */
@Testcontainers
class P3AiAuthorizationIntegrationTest {
    private static final String TEST_JWT_SECRET="taskpilot-test-only-app-security-jwt-signing-key-2026-32bytes";
    private static final ObjectMapper MAPPER=new ObjectMapper();
    private static final HttpClient HTTP=HttpClient.newHttpClient();
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("taskpilot").withUsername("test").withPassword("test").withStartupTimeout(Duration.ofSeconds(90));
    private static ConfigurableApplicationContext context;
    private static String baseUrl;
    private static PendingAiActionService pending;
    private static TaskCommandPort tasks;
    private static TaskPilotAiTools tools;
    private static JdbcTemplate jdbc;
    @BeforeAll static void startApp() {
        MigrationDatabaseConfiguration.configure(Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())).load().migrate();
        context=SpringApplication.run(TaskPilotApplication.class,appArgs());
        baseUrl="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort();
        pending=context.getBean(PendingAiActionService.class);tasks=context.getBean(TaskCommandPort.class);
        tools=context.getBean(TaskPilotAiTools.class);jdbc=context.getBean(JdbcTemplate.class);
    }
    @AfterAll static void stopApp(){if(context!=null)context.close();}
    record Fixture(long uid,long sid,long project,long task,String jwt){}
    private Fixture fixture() throws Exception {
        String email="p3-"+UUID.randomUUID()+"@example.com";
        UserEntity user=new UserEntity();user.setEmail(email);user.setFullName("P3 Test");user.setRole(UserRole.USER);
        user.setPassword(context.getBean(PasswordEncoder.class).encode("Pass!12345"));user.setEmailVerified(true);
        user=context.getBean(UserRepository.class).saveAndFlush(user);
        var login=post("/api/v1/auth/login",Map.of("email",email,"password","Pass!12345"),null);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String jwt=MAPPER.readTree(login.body()).path("data").path("token").asText();
        var project=post("/api/v1/projects",Map.of("name","P3 project "+UUID.randomUUID()),jwt);
        assertThat(project.statusCode()).as(project.body()).isEqualTo(201);
        long pid=MAPPER.readTree(project.body()).path("data").path("id").asLong();
        long sid=context.getBean(ChatSessionService.class).createSession(user.getId(),"P3 session").getId();
        long task=tasks.createTask(pid,"Before",null,"MEDIUM",0f,null,null,5,null,null,null,null,null,user.getId()).id();
        return new Fixture(user.getId(),sid,pid,task,jwt);
    }
    private ConfirmationRequiredDto preview(Fixture f) throws Exception {
        String args=MAPPER.writeValueAsString(Map.of("taskId",f.task,"patchData",Map.of("title","After"),"reason","P3 test"));
        String raw=ToolExecutionContext.callWith(new ToolExecutionContext.Context(f.uid,f.sid,"change title"),
                ()->context.getBean(ToolCallingRegistryService.class).execute(ToolExecutionRequest.builder()
                    .name("patchTask").arguments(args).build()));
        var json=MAPPER.readTree(raw);
        assertThat(json.path("confirmationRequired").asBoolean()).isTrue();
        assertThat(json.path("payloadHash").asText()).hasSize(64);
        assertThat(json.path("expiresAt").isTextual()).isTrue();
        java.time.Instant.parse(json.path("expiresAt").asText());
        var action=pending.describe(json.path("actionId").asText(),f.uid,f.sid);
        assertThat(action.payloadHash()).isEqualTo(json.path("payloadHash").asText());
        return action;
    }
    private String path(Fixture f,ConfirmationRequiredDto a){return "/api/v1/ai/sessions/"+f.sid+"/actions/"+a.actionId();}
    private HttpResponse<String> confirm(Fixture f,ConfirmationRequiredDto a,String key,String token) throws Exception {
        return post(path(f,a)+"/confirm",Map.of("idempotencyKey",key,"payloadHash",a.payloadHash()),token);
    }
    @Test void explicitHttpOwnerPayloadSessionAndReplayExecuteOnce() throws Exception {
        var f=fixture();var other=fixture();var a=preview(f);
        assertThat(confirm(f,a,"k",other.jwt).statusCode()).isIn(403,404);
        assertThat(post("/api/v1/ai/sessions/"+other.sid+"/actions/"+a.actionId()+"/confirm",
                Map.of("idempotencyKey","k","payloadHash",a.payloadHash()),f.jwt).statusCode()).isIn(403,404);
        assertThat(post(path(f,a)+"/confirm",Map.of("idempotencyKey","k","payloadHash","forged"),f.jwt).statusCode()).isEqualTo(409);
        var result=confirm(f,a,"k",f.jwt);assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        assertThat(tasks.getTaskDetails(f.task,f.uid).title()).isEqualTo("After");
        assertThat(confirm(f,a,"k",f.jwt).statusCode()).isEqualTo(200);
        assertThat(confirm(f,a,"different",f.jwt).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from chat_messages where session_id=?",Long.class,f.sid)).isEqualTo(1L);
        var cancelled=preview(f);assertThat(post(path(f,cancelled)+"/cancel",Map.of(),f.jwt).statusCode()).isEqualTo(200);
        assertThat(confirm(f,cancelled,"cancel",f.jwt).statusCode()).isEqualTo(410);
    }
    @Test void currentResourceAndManagerChangesForceRepreviewAndRevokedCacheDoesNotLeak() throws Exception {
        var f=fixture();var a=preview(f);
        jdbc.update("update tasks set title='Changed elsewhere' where id=?",f.task);
        assertThat(confirm(f,a,"stale",f.jwt).statusCode()).isEqualTo(409);
        assertThat(tasks.getTaskDetails(f.task,f.uid).title()).isEqualTo("Changed elsewhere");
        var managerPreview=preview(f);
        jdbc.update("update project_members set role='MEMBER' where project_id=? and user_id=?",f.project,f.uid);
        assertThat(confirm(f,managerPreview,"demoted",f.jwt).statusCode()).isEqualTo(409);
        var fresh=preview(f);
        var registry=context.getBean(ToolCallingRegistryService.class);
        var req=ToolExecutionRequest.builder().name("getTaskDetails").arguments("{\"taskId\":"+f.task+"}").build();
        String cached=ToolExecutionContext.callWith(new ToolExecutionContext.Context(f.uid,f.sid,"read"),()->registry.execute(req));
        assertThat(cached).contains("Changed elsewhere");
        // SQL fixture simulates concurrent committed roster revision, avoiding unrelated invitation fixtures.
        jdbc.update("delete from project_members where project_id=? and user_id=?",f.project,f.uid);
        assertThat(confirm(f,fresh,"revoked",f.jwt).statusCode()).isEqualTo(403);
        String denied=ToolExecutionContext.callWith(new ToolExecutionContext.Context(f.uid,f.sid,"read"),()->registry.execute(req));
        assertThat(denied).startsWith("Tool execution failed").doesNotContain("Changed elsewhere");
        assertThat(post("/api/v1/ai/sessions/"+f.sid+"/warmup",Map.of(),fixture().jwt).statusCode()).isIn(403,404);
    }
    @Test void failedExecutionRollsBackTaskNotificationAndHistoryThenSameKeyCanRetry() throws Exception {
        var f=fixture();var attempts=new AtomicInteger();
        Long before=jdbc.queryForObject("select count(*) from notifications where user_id=?",Long.class,f.uid);
        var a=pending.create(f.uid,f.sid,"assignTaskToMember","Assign",Map.of("taskId",f.task,"memberId",f.uid),null,()->{
            var result=tasks.assignTaskToMember(f.task,f.uid,"test",f.uid,false);
            if(attempts.incrementAndGet()==1)throw new IllegalStateException("test rollback after notification");
            return result;
        });
        assertThat(confirm(f,a,"retry",f.jwt).statusCode()).isEqualTo(500);
        assertThat(tasks.getTaskDetails(f.task,f.uid).assigneeId()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id=?",Long.class,f.uid)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from chat_messages where session_id=?",Long.class,f.sid)).isZero();
        var retry=confirm(f,a,"retry",f.jwt);assertThat(retry.statusCode()).as(retry.body()).isEqualTo(200);
        assertThat(confirm(f,a,"retry",f.jwt).statusCode()).isEqualTo(200);
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id=?",Long.class,f.uid)).isEqualTo(before+1);
        assertThat(jdbc.queryForObject("select count(*) from chat_messages where session_id=?",Long.class,f.sid)).isEqualTo(1L);
    }
    @Test void feedbackOwnerOnlyAndMissingLog404ThroughHttp() throws Exception {
        var f=fixture();var other=fixture();
        var repo=context.getBean(AiLogRepository.class);
        var log=repo.saveAndFlush(AiLogEntity.builder().userId(f.uid).projectId(f.project).request("test").response("test").build());
        assertThat(request("PATCH","/api/v1/ai/logs/"+log.getId()+"/feedback?feedback=ACCEPTED",Map.of(),other.jwt).statusCode()).isEqualTo(403);
        assertThat(request("PATCH","/api/v1/ai/logs/"+log.getId()+"/feedback?feedback=ACCEPTED",Map.of(),f.jwt).statusCode()).isEqualTo(200);
        assertThat(request("PATCH","/api/v1/ai/logs/9223372036854775807/feedback?feedback=ACCEPTED",Map.of(),f.jwt).statusCode()).isEqualTo(404);
    }
    @Test void recommendationRestAndToolRejectForeignProjectBeforeModel() throws Exception {
        var f=fixture();var other=fixture();
        var denied=post("/api/v1/ai/auto-assign",Map.of("projectId",f.project,"requiredSkills",List.of("Java"),"taskDifficulty",5),other.jwt);
        assertThat(denied.statusCode()).as(denied.body()).isEqualTo(403);
        var registry=context.getBean(ToolCallingRegistryService.class);
        String result=ToolExecutionContext.callWith(new ToolExecutionContext.Context(other.uid,other.sid,"recommend"),
            ()->registry.execute(ToolExecutionRequest.builder().name("recommendAssignmentCandidates")
                .arguments("{\"projectId\":\""+f.project+"\",\"skills\":\"Java\",\"difficulty\":\"5\"}").build()));
        assertThat(result).contains("not a member").doesNotContain("candidates");
    }
    @Test void accountRevocationCommittedWhileWaitingForProjectLockCannotUseManagedStaleState() throws Exception {
        var f=fixture();var a=preview(f);
        var tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var response=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<HttpResponse<String>>>();
            tx.executeWithoutResult(status->{
                jdbc.queryForObject("select id from projects where id=? for update",Long.class,f.project);
                response.set(executor.submit(()->confirm(f,a,"racing-revoke",f.jwt)));
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    jdbc.execute("select pg_stat_clear_snapshot()");
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid<>pg_backend_pid() and wait_event_type='Lock' and query like '%public.projects%for update%')",Boolean.class));
                    if(waiting)break;
                    java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20));
                }
                assertThat(waiting).as("Confirmation must already have read account state before blocking on the project lock").isTrue();
                jdbc.update("update users set email_verified=false where id=?",f.uid);
            });
            assertThat(response.get().get(10,java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(403);
            assertThat(jdbc.queryForObject("select title from tasks where id=?",String.class,f.task)).isEqualTo("Before");
        }
    }
    @Test void recommendedAssigneeRevokedAfterPreviewCannotBeAssigned() throws Exception {
        var f=fixture();var target=fixture();
        jdbc.update("insert into project_members (project_id,user_id,role) values(?,?,'MEMBER')",f.project,target.uid);
        var a=pending.create(f.uid,f.sid,"recommendAndAssignTask","Assign recommended member",
            Map.of("taskId",f.task,"memberId",target.uid),null,
            ()->tasks.assignTaskToMember(f.task,target.uid,"test",f.uid,false));
        jdbc.update("update users set email_verified=false where id=?",target.uid);
        assertThat(confirm(f,a,"target-revoked",f.jwt).statusCode()).isEqualTo(403);
        assertThat(tasks.getTaskDetails(f.task,f.uid).assigneeId()).isNull();
    }
    private static HttpResponse<String> post(String path,Object body,String token)throws Exception{return request("POST",path,body,token);}
    private static HttpResponse<String> request(String method,String path,Object body,String token)throws Exception {
        var b=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json")
                .method(method,HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if(token!=null)b.header("Authorization","Bearer "+token);
        return HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static String[] appArgs() {
        return new String[]{
                "--server.port=0",
                "--spring.profiles.active=dev",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.jpa.hibernate.ddl-auto=none",
                "--spring.flyway.enabled=false",
                "--spring.mail.host=localhost",
                "--spring.mail.port=1025",
                "--spring.task.scheduling.enabled=false",
                "--ai.gemini.api-key=local-dummy-not-a-secret",
                "--ai.groq.enabled=false",
                "--ai.openrouter.enabled=false",
                "--supabase.s3.endpoint=http://127.0.0.1:19999",
                "--supabase.s3.access-key=test-access",
                "--supabase.s3.secret-key=test-secret",
                "--supabase.s3.bucket=test-bucket",
                "--supabase.s3.public-url=http://127.0.0.1:19999/public",
                "--application.security.jwt.secret-key=" + TEST_JWT_SECRET,
                "--application.security.jwt.issuer=taskpilot",
                "--application.security.jwt.audience=taskpilot-api",
                "--application.security.jwt.expiration=600000",
                "--application.security.jwt.refresh-token.expiration=604800000",
                "--application.security.jwt.blocklist.provider=memory",
                "--application.security.password-reset.rate-limit.provider=memory",
                "--rag.embedding.poll-interval-ms=86400000",
                "--decorator.datasource.p6spy.enable-logging=false",
                "--logging.level.root=WARN"
        };
    }
}
