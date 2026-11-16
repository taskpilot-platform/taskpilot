package com.taskpilot.infrastructure.storage.range;

import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponse;
import com.google.api.services.drive.Drive;
import com.taskpilot.infrastructure.storage.S3StorageServiceImpl;
import com.taskpilot.infrastructure.storage.googledrive.GoogleDriveService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Provider doubles only: these tests exercise the real adapters without cloud traffic. */
class P4ProviderRangeReviewTest {
    private static final Optional<ByteRange> RANGE = Optional.of(new ByteRange(2, 5));

    @Test
    void driveRejectsIgnoredOrMismatchedRangeAndDisconnectsResponse() throws Exception {
        // A claimed exact range must not be fabricated from the request when the provider returns other bytes.
        for (var bad : new Object[][]{
                {200, null, 10L}, {206, "bytes 0-3/10", 4L},
                {206, "bytes 2-5/10", 10L}, {206, "bytes 2-5/*", 4L},
                {206, "bytes 2-5/5", 4L}, {206, "not-a-range", 4L}}) {
            Drive drive = mock(Drive.class);
            Drive.Files files = mock(Drive.Files.class);
            Drive.Files.Get request = mock(Drive.Files.Get.class);
            HttpResponse response = mock(HttpResponse.class);
            when(drive.files()).thenReturn(files);
            when(files.get("object")).thenReturn(request);
            when(request.getRequestHeaders()).thenReturn(new HttpHeaders());
            when(request.executeMedia()).thenReturn(response);
            when(response.getStatusCode()).thenReturn((int) bad[0]);
            when(response.getHeaders()).thenReturn(new HttpHeaders()
                    .setContentRange((String) bad[1]).setContentLength((long) bad[2]));

            assertThatThrownBy(() -> new GoogleDriveService(drive, "folder").readObject("object", RANGE))
                    .as("Drive response %s / %s / %s", bad).isInstanceOf(IOException.class);
            verify(response).disconnect();
        }
    }

    @Test
    void driveRejectsMissingRangeResponseInsteadOfOpeningUnverifiedFallback() throws Exception {
        Drive drive = mock(Drive.class);
        Drive.Files files = mock(Drive.Files.class);
        Drive.Files.Get request = mock(Drive.Files.Get.class);
        when(drive.files()).thenReturn(files);
        when(files.get("object")).thenReturn(request);
        when(request.getRequestHeaders()).thenReturn(new HttpHeaders());
        assertThatThrownBy(() -> new GoogleDriveService(drive, "folder").readObject("object", RANGE))
                .isInstanceOf(IOException.class);
        verify(request, never()).executeMediaAsInputStream();
    }

    @Test
    void s3RejectsIgnoredOrMismatchedRangeAndClosesResponse() throws Exception {
        for (var bad : new Object[][]{
                {200, null, 10L}, {206, "bytes 0-3/10", 4L},
                {206, "bytes 2-5/10", 10L}, {206, "bytes 2-5/*", 4L},
                {206, "bytes 2-5/5", 4L}, {206, "not-a-range", 4L}}) {
            S3Client client = mock(S3Client.class);
            var builder = GetObjectResponse.builder().contentRange((String) bad[1]).contentLength((long) bad[2]);
            builder.sdkHttpResponse(SdkHttpResponse.builder().statusCode((int) bad[0]).build());
            GetObjectResponse response = builder.build();
            ResponseInputStream<GetObjectResponse> stream = spy(new ResponseInputStream<GetObjectResponse>(
                    response, new ByteArrayInputStream(new byte[10])));
            when(client.getObject(any(GetObjectRequest.class))).thenReturn(stream);
            var service = new S3StorageServiceImpl("http://127.0.0.1:19999", "test", "test", "us-east-1", "documents", "");
            ReflectionTestUtils.setField(service, "s3Client", client);

            assertThatThrownBy(() -> service.readObject(new StorageReadRequest("documents", "object", RANGE)))
                    .as("S3 response %s / %s / %s", bad).isInstanceOf(IOException.class);
            verify(stream).close();
        }
    }
}
