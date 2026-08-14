package com.flydb.example.boot2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class Boot2DemoApplication {

    private final JdbcTemplate jdbcTemplate;

    public Boot2DemoApplication(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public static void main(String[] args) {
        SpringApplication.run(Boot2DemoApplication.class, args);
    }

    @GetMapping(value = "/flydb-demo", produces = MediaType.TEXT_PLAIN_VALUE)
    public String migrationStatus() {
        String marker = jdbcTemplate.queryForObject(
                "SELECT marker FROM boot2_flydb_demo WHERE id = 1", String.class);
        return "FLYDB_BOOT2_DEMO_OK:" + marker;
    }
}
