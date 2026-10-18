plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("io.lettuce:lettuce-core")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.micrometer:micrometer-registry-otlp")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("com.google.guava:guava")
    implementation("io.jsonwebtoken:jjwt-api")
    implementation("com.google.cloud:spring-cloud-gcp-starter-secretmanager")
    implementation("com.google.cloud.sql:postgres-socket-factory")
    implementation("io.github.resilience4j:resilience4j-spring-boot3")
    implementation("net.javacrumbs.shedlock:shedlock-spring")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    runtimeOnly("io.jsonwebtoken:jjwt-impl")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-test-autoconfigure")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("com.redis.testcontainers:testcontainers-redis")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
