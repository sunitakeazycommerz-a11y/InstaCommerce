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
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.micrometer:micrometer-registry-otlp")
    implementation("net.logstash.logback:logstash-logback-encoder")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.jsonwebtoken:jjwt-api")
    runtimeOnly("io.jsonwebtoken:jjwt-impl")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson")
    implementation("org.mapstruct:mapstruct")
    annotationProcessor("org.mapstruct:mapstruct-processor")
    implementation("com.google.cloud:spring-cloud-gcp-starter-secretmanager")
    implementation("com.google.cloud.sql:postgres-socket-factory")
    implementation("io.github.resilience4j:resilience4j-spring-boot3")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("net.javacrumbs.shedlock:shedlock-spring")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
