FROM ghcr.io/graalvm/native-image-community:21 AS build
WORKDIR /app
ARG MAVEN_VERSION=3.9.6
RUN curl -fsSL https://downloads.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
    | tar xz -C /opt && ln -s /opt/apache-maven-${MAVEN_VERSION} /opt/maven
ENV PATH=/opt/maven/bin:${PATH}
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn -Pnative native:compile -DskipTests

FROM debian:12-slim
WORKDIR /app
COPY --from=build /app/target/chat-service .
EXPOSE 8080
ENTRYPOINT ["./chat-service"]
