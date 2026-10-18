plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

dependencies {
    implementation(project(":contracts"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-registry-otlp")
    implementation("net.logstash.logback:logstash-logback-encoder")
    implementation("com.google.cloud:spring-cloud-gcp-starter-secretmanager")
    implementation("com.google.cloud.sql:postgres-socket-factory")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.jsonwebtoken:jjwt-api")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.stripe:stripe-java")
    implementation("net.javacrumbs.shedlock:shedlock-spring")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template")
    implementation("io.github.resilience4j:resilience4j-spring-boot3")
    implementation("io.github.resilience4j:resilience4j-micrometer")
    runtimeOnly("io.jsonwebtoken:jjwt-impl")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
