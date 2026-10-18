plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("net.javacrumbs.shedlock:shedlock-spring")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.micrometer:micrometer-registry-otlp")
    implementation("net.logstash.logback:logstash-logback-encoder")
    implementation("io.temporal:temporal-sdk")
    implementation("io.github.resilience4j:resilience4j-spring-boot3")
    implementation("io.jsonwebtoken:jjwt-api")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.jsonwebtoken:jjwt-impl")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
