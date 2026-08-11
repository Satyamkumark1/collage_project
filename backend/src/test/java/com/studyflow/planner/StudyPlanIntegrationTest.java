package com.studyflow.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.studyflow.identity.dto.AccessTokenResponse;
import com.studyflow.identity.dto.LoginRequest;
import com.studyflow.identity.dto.RegisterRequest;
import com.studyflow.library.dto.UploadResponse;
import com.studyflow.planner.dto.StudyPlanRequest;
import com.studyflow.planner.dto.StudyPlanResponse;
import com.studyflow.support.DatabaseCleanerExtension;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Real Postgres, deliberately no Groq/Voyage — study-plan build is pure arithmetic (see
 * docs/DECISIONS.md), so unlike every other study/* integration test this one carries no
 * real-API rate-limit fragility.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(DatabaseCleanerExtension.class)
class StudyPlanIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void endToEndCreateListGetAndExportIcs() {
        String accessToken = registerAndLogin();
        UUID documentId = upload(accessToken);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        LocalDate examDate = LocalDate.now().plusDays(10);
        ResponseEntity<StudyPlanResponse> createResponse = restTemplate.exchange(
                "/api/v1/documents/" + documentId + "/study-plans", HttpMethod.POST,
                new HttpEntity<>(new StudyPlanRequest(examDate), headers), StudyPlanResponse.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        StudyPlanResponse plan = createResponse.getBody();
        assertThat(plan.examDate()).isEqualTo(examDate);
        // 10 days out: offsets {10,7,5,3,2,1,0} survive the cadence filter.
        assertThat(plan.sessions()).hasSize(7);
        assertThat(plan.sessions().get(plan.sessions().size() - 1).scheduledDate()).isEqualTo(examDate);

        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.setBearerAuth(accessToken);
        ResponseEntity<StudyPlanResponse[]> listResponse = restTemplate.exchange(
                "/api/v1/documents/" + documentId + "/study-plans", HttpMethod.GET, new HttpEntity<>(getHeaders),
                StudyPlanResponse[].class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).hasSize(1);

        ResponseEntity<StudyPlanResponse> singleResponse = restTemplate.exchange(
                "/api/v1/study-plans/" + plan.id(), HttpMethod.GET, new HttpEntity<>(getHeaders),
                StudyPlanResponse.class);
        assertThat(singleResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(singleResponse.getBody().sessions()).hasSize(7);

        ResponseEntity<String> icsResponse = restTemplate.exchange("/api/v1/study-plans/" + plan.id() + "/export.ics",
                HttpMethod.GET, new HttpEntity<>(getHeaders), String.class);
        assertThat(icsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String ics = icsResponse.getBody();
        assertThat(ics).startsWith("BEGIN:VCALENDAR").endsWith("END:VCALENDAR\r\n");
        assertThat(countOccurrences(ics, "BEGIN:VEVENT")).isEqualTo(7);
        assertThat(ics).contains("DTSTART;VALUE=DATE:" + examDate.toString().replace("-", ""));
    }

    @Test
    void gettingAnUnknownPlanIsRejected() {
        String accessToken = registerAndLogin();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/study-plans/" + UUID.randomUUID(),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode problem = objectMapper.readTree(response.getBody());
        assertThat(problem.get("code").asString()).isEqualTo("STUDY_PLAN_NOT_FOUND");
    }

    private int countOccurrences(String haystack, String needle) {
        return (haystack.length() - haystack.replace(needle, "").length()) / needle.length();
    }

    private UUID upload(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add("Idempotency-Key", "planner-upload-" + System.nanoTime());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource("plain text file".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "quick.txt";
            }
        });
        ResponseEntity<UploadResponse> uploadResponse = restTemplate.postForEntity("/api/v1/documents",
                new HttpEntity<>(body, headers), UploadResponse.class);
        return uploadResponse.getBody().documentId();
    }

    private String registerAndLogin() {
        String email = "planner" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "correct horse battery", "Planner Test User", (short) 2000), Object.class);
        ResponseEntity<AccessTokenResponse> login = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "correct horse battery"), AccessTokenResponse.class);
        return login.getBody().accessToken();
    }
}
