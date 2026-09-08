package com.psystem.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AppConfig {

    /**
     * Spring's modern synchronous HTTP client (recommended over RestTemplate for new code
     * since Spring 6.1 / Boot 3.2). Used for the pre-signed URL request only - the actual
     * file bytes are streamed to S3 with a lower-level HttpURLConnection/HttpClient so we
     * have full control over chunked streaming for very large files (see S3UploadService).
     * <p>
     * No {@code baseUrl(...)} is configured here - {@link com.psystem.service.presignedurl.PresignedUrlClient}
     * passes the full, absolute URL from {@code psystem.upload.presigned-url-api} explicitly
     * on every request via {@code .uri(URI)}. This keeps the target endpoint visible at the
     * call site rather than implicit in client construction, and avoids relying on how an
     * empty relative URI resolves against a configured base.
     */
    @Bean
    public RestClient presignedUrlRestClient(AgentProperties properties) {
        RestTemplateBuilder builder = new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofMillis(properties.getUpload().getConnectionTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(properties.getUpload().getReadTimeoutMs()));

        return RestClient.builder()
                .requestFactory(builder.buildRequestFactory())
                .build();
    }
}
