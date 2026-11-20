package com.example.proposalservice;

import org.springframework.test.context.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestPropertySource(properties={"proposal.messaging.enabled=false","proposal.jobs.enabled=false","spring.rabbitmq.dynamic=false"})
public abstract class PostgresTestSupport {
    static PostgreSQLContainer postgres;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String url=System.getenv("PROPOSAL_TEST_DATABASE_URL");
        if(url==null){synchronized(PostgresTestSupport.class){if(postgres==null){postgres=new PostgreSQLContainer("postgres:16-alpine");postgres.start();}}properties.add("spring.datasource.url",postgres::getJdbcUrl);properties.add("spring.datasource.username",postgres::getUsername);properties.add("spring.datasource.password",postgres::getPassword);}
        else {if(!url.matches(".*[/]([^/?]*test[^/?]*)(\\?.*)?$"))throw new IllegalArgumentException("Use a dedicated test database whose name contains test.");properties.add("spring.datasource.url",()->url);properties.add("spring.datasource.username",()->System.getenv().getOrDefault("PROPOSAL_TEST_DATABASE_USERNAME","proposal_test"));properties.add("spring.datasource.password",()->System.getenv().getOrDefault("PROPOSAL_TEST_DATABASE_PASSWORD","proposal_test"));}
    }
}
