# Build context: repository root (see docker-compose.yml). Dev image only; release images are T002.
FROM gradle:8.11.1-jdk17 AS build
ARG KOTOBAYA_VERSION=dev
ENV KOTOBAYA_VERSION=${KOTOBAYA_VERSION}
WORKDIR /src
COPY . .
RUN gradle --no-daemon :server:installDist -x test

FROM eclipse-temurin:17-jre
COPY --from=build /src/server/build/install/server /opt/server
USER 65534
EXPOSE 8080
CMD ["/opt/server/bin/server"]
