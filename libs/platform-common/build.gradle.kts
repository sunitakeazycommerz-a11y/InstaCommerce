plugins {
    `java-library`
    id("io.spring.dependency-management")
}

dependencies {
    compileOnly("org.springframework.boot:spring-boot-starter-web")
    compileOnly("org.springframework.boot:spring-boot-starter-security")
    compileOnly("org.springframework.boot:spring-boot-starter-cache")
    compileOnly("org.springframework.boot:spring-boot-starter-data-redis")
    compileOnly("org.springframework.data:spring-data-commons")
    compileOnly("com.github.ben-manes.caffeine:caffeine")
    compileOnly("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    compileOnly("io.jsonwebtoken:jjwt-api")

    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.boot:spring-boot-starter-cache")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.springframework.data:spring-data-commons")
    testImplementation("com.github.ben-manes.caffeine:caffeine")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testImplementation("io.jsonwebtoken:jjwt-api")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("io.jsonwebtoken:jjwt-impl")
    testRuntimeOnly("io.jsonwebtoken:jjwt-jackson")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
