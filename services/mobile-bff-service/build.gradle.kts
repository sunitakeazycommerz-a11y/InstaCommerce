plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("io.github.resilience4j:resilience4j-spring-boot3")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.micrometer:micrometer-registry-otlp")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-web")
    implementation("org.springframework.security:spring-security-config")
    implementation("org.springframework.security:spring-security-core")
    implementation("io.jsonwebtoken:jjwt-api")
    runtimeOnly("io.jsonwebtoken:jjwt-impl")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
