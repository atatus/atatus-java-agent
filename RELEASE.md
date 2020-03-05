# Release

First Commit to compare: 35493e3ea7cf9d8120dffb1853b7f95292d68e2b   (Thu Oct 10 2019)


    git diff 35493e3ea7cf9d8120dffb1853b7f95292d68e2b HEAD | subl

--------------------------------------------------------------------------------

TODO: In future, we need to revisit the LICENSE file contents in the jar file.
FIXME:
    - Remove old spans
    - API support for tags, custom data.
    - Maven release steps.


1. Change release version in all pom.xml files

    <version>1.2.0</version>

2. Build the new Java agent

    ./mvnw clean install -DskipTests=true -Dmaven.javadoc.skip=true

3. Rename target agent file

    cd atatus-apm-agent/target/

    cp atatus-apm-agent-1.2.0.jar atatus-java-agent.jar
    cp atatus-apm-agent-1.2.0.jar atatus-java-agent-1.2.0.jar


Check version in the jar file

    unzip -p atatus-java-agent.jar | head -n 15


4. Upload those files to following folder

    atatus-artifacts/atatus-java/downloads/latest
    atatus-artifacts/atatus-java/downloads/<version>


5. Update changelog in atatus documentation

