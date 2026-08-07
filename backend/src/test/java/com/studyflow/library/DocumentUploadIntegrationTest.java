package com.studyflow.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.studyflow.identity.dto.AccessTokenResponse;
import com.studyflow.identity.dto.LoginRequest;
import com.studyflow.identity.dto.RegisterRequest;
import com.studyflow.library.dto.DocumentResponse;
import com.studyflow.library.dto.UploadResponse;
import com.studyflow.support.DatabaseCleanerExtension;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(DatabaseCleanerExtension.class)
class DocumentUploadIntegrationTest {

    private static final byte[] MINIMAL_PDF = ("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\n"
            + "trailer<</Root 1 0 R>>\n%%EOF").getBytes(StandardCharsets.UTF_8);

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void uploadingAPdfCreatesAnUploadedDocumentAndEnqueuesAnIngestJob() {
        String accessToken = registerAndLogin(adultBirthYear());

        ResponseEntity<UploadResponse> uploadResponse = uploadPdf(accessToken, MINIMAL_PDF, "notes.pdf",
                "idem-" + System.nanoTime());
        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(uploadResponse.getBody()).isNotNull();
        assertThat(uploadResponse.getBody().documentId()).isNotNull();
        assertThat(uploadResponse.getBody().jobId()).isNotNull();

        HttpHeaders authHeaders = bearer(accessToken);
        ResponseEntity<DocumentResponse> getResponse = restTemplate.exchange(
                "/api/v1/documents/" + uploadResponse.getBody().documentId(), org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(authHeaders), DocumentResponse.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().status()).isEqualTo("UPLOADED");
        assertThat(getResponse.getBody().fileType()).isEqualTo("PDF");
    }

    @Test
    void reuploadingIdenticalBytesReturnsTheExistingDocumentInstead() {
        String accessToken = registerAndLogin(adultBirthYear());
        byte[] content = ("%PDF-1.4\nunique-" + System.nanoTime() + "\n%%EOF").getBytes(StandardCharsets.UTF_8);

        ResponseEntity<UploadResponse> first = uploadPdf(accessToken, content, "a.pdf", "idem-a-" + System.nanoTime());
        ResponseEntity<UploadResponse> second = uploadPdf(accessToken, content, "b.pdf", "idem-b-" + System.nanoTime());

        assertThat(second.getBody().documentId()).isEqualTo(first.getBody().documentId());
        assertThat(second.getBody().jobId()).isNull();
    }

    @Test
    void unsupportedFileTypeIsRejected() {
        String accessToken = registerAndLogin(adultBirthYear());
        byte[] binaryJunk = {0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE};

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(binaryJunk) {
            @Override
            public String getFilename() {
                return "malware.exe";
            }
        });
        HttpHeaders headers = bearer(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/documents",
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).contains("FILE_TYPE_UNSUPPORTED");
    }

    @Test
    void minorWithoutGuardianConsentIsBlockedFromUploading() {
        int currentYear = Year.now(ZoneId.of("Asia/Kolkata")).getValue();
        String accessToken = registerAndLogin((short) (currentYear - 15));

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/documents",
                org.springframework.http.HttpMethod.POST, multipartEntity(accessToken, MINIMAL_PDF, "notes.pdf"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("AUTH_GUARDIAN_CONSENT_REQUIRED");
    }

    private ResponseEntity<UploadResponse> uploadPdf(String accessToken, byte[] content, String filename,
            String idempotencyKey) {
        HttpHeaders headers = bearer(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add("Idempotency-Key", idempotencyKey);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });

        return restTemplate.postForEntity("/api/v1/documents", new HttpEntity<>(body, headers), UploadResponse.class);
    }

    private HttpEntity<MultiValueMap<String, Object>> multipartEntity(String accessToken, byte[] content,
            String filename) {
        HttpHeaders headers = bearer(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        return new HttpEntity<>(body, headers);
    }

    private HttpHeaders bearer(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }

    private short adultBirthYear() {
        return (short) (Year.now(ZoneId.of("Asia/Kolkata")).getValue() - 25);
    }

    private String registerAndLogin(short birthYear) {
        String email = "uploader" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "correct horse battery", "Uploader", birthYear), Object.class);
        ResponseEntity<AccessTokenResponse> login = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "correct horse battery"), AccessTokenResponse.class);
        return login.getBody().accessToken();
    }
}
