# Readme

## Build Agent

cd ~/development/git/atatus/atatus-java-agent/
./mvnw clean install -DskipTests=true -Dmaven.javadoc.skip=true

* Remove snapshot from jar

    ./mvnw clean install -DremoveSnapshot -DskipTests=true -Dmaven.javadoc.skip=true

* Remove snapshot and set new version for jar

    ./mvnw clean install versions:set -DremoveSnapshot -DskipTests=true -Dmaven.javadoc.skip=true

### Check manifest file

    unzip -p atatus-java-agent.jar META-INF/MANIFEST.MF

--------------------------------------------------------------------------------

## Test

### Start MongoDB

cd ~/development/git/atatus
docker-compose up -d mongo
docker exec -it mongodb bash


### Start sample apps

cd ~/development/git/atatus/atatus-java-agent/java-test-projects/SpringBoot/spring-boot-mongodb/target

java8 -javaagent:/Users/apple/development/git/atatus/atatus-java-apm/atatus-java-agent/atatus-apm-agent/target/atatus-apm-agent-1.0.0.jar -Datatus.server_urls=http://localhost:8200  -Datatus.application_packages=guru.springframework -Datatus.log_level=Debug -Datatus.trace_min_duration=10ms -jar spring-boot-mongodb-0.0.1-SNAPSHOT.jar

java8 -javaagent:/Users/apple/development/git/atatus/atatus-java-apm/atatus-java-agent/atatus-apm-agent/target/atatus-apm-agent-1.0.0.jar -Datatus.server_urls=http://localhost:8200  -Datatus.application_packages=guru.springframework -Datatus.log_level=Debug -Datatus.trace_min_duration=10ms -Datatus.use_path_as_transaction_name=true -jar spring-boot-mongodb-0.0.1-SNAPSHOT.jar

java8 -javaagent:/Users/apple/development/git/atatus/atatus-java-apm/atatus-java-agent/atatus-apm-agent/target/atatus-apm-agent-1.0.0.jar -Datatus.license_key="lic_apm_0dc7f9851bc44b08ad915eca1ed05b51" -Datatus.app_name="Java APM Backend"  -Datatus.application_packages=guru.springframework -Datatus.log_level=Info -jar spring-boot-mongodb-0.0.1-SNAPSHOT.jar

### Run in different port:

cd ~/development/git/atatus/atatus-java-agent/java-test-projects/angular6-springboot-mysql-crud/springboot2-jpa-crud-example/target

java8 -javaagent:/Users/apple/development/git/atatus/atatus-java-apm/atatus-java-agent/atatus-apm-agent/target/atatus-apm-agent-1.0.0.jar -Datatus.license_key="lic_apm_42eb4423ad504909b7f9ac2f97a735a9" -Datatus.app_name="Java APM Backend"  -Datatus.application_packages=guru.springframework -Datatus.log_level=Info -Dserver.port=9090 -jar springboot2-jpa-crud-example-0.0.1-SNAPSHOT.jar


--------------------------------------------------------------------------------

### Java Tips

cd test/
jar xf atatus-apm-agent-1.0.0.jar
rm atatus-apm-agent-1.0.0.jar

jar cfm ../atatus-apm-agent-1.0.0.jar META-INF/MANIFEST.MF *

--------------------------------------------------------------------------------

