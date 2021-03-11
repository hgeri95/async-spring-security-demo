package com.example.asyncsecuritydemo;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.net.URISyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
class AsyncSecurityDemoApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void testSimpleCall() {
        final String username = "Joe";
        RequestEntity requestEntity = generateRequest(username);
        ResponseEntity<String> response = this.restTemplate.exchange(requestEntity, String.class);
        assertEquals(username, response.getBody());
    }

    @Test
    @SneakyThrows
    void testSecurityContextInAsyncEnvironmentWithThreadPool() {
        Thread threadA = generateThread("Timon"); // Thread to call endpoint /A 500 times
        Thread threadB = generateThread("Pumbaa"); // Thread to call endpoint /B 500 times
        threadA.start();
        threadB.start();
        threadA.join();
        threadB.join();
    }

    private Thread generateThread(String username) {
        return new Thread(() -> {
            RequestEntity requestEntity = generateRequest(username);
            for (int i = 0; i < 500; i++) {
                ResponseEntity<String> response = this.restTemplate.exchange(requestEntity, String.class);
                assertEquals(username, response.getBody());
            }
        });
    }

    private RequestEntity generateRequest(String username) {
        try {
            String url = "http://localhost:" + port + "/name/" + username;
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, username);
            RequestEntity requestEntity = new RequestEntity(headers, HttpMethod.GET, new URI(url));
            return requestEntity;
        } catch (URISyntaxException ex) {
            log.error("", ex);
            return null;
        }
    }
}
