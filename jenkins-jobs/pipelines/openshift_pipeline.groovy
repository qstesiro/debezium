pipeline {
    agent {
        label 'Slave'
    }

    stages {
        stage('Checkout - Debezium') {
            steps {
                checkout([
                        $class           : 'GitSCM',
                        branches         : [[name: "${DBZ_GIT_BRANCH}"]],
                        userRemoteConfigs: [[url: "${DBZ_GIT_REPOSITORY}"]],
                        extensions       : [[$class           : 'RelativeTargetDirectory',
                                             relativeTargetDir: 'debezium']],
                ])
            }
        }

        stage('Checkout - Debezium DB2 connector') {
            when {
                expression { !params.PRODUCT_BUILD }
            }
            steps {
                checkout([
                        $class           : 'GitSCM',
                        branches         : [[name: "${DBZ_GIT_BRANCH_DB2}"]],
                        userRemoteConfigs: [[url: "${DBZ_GIT_REPOSITORY_DB2}"]],
                        extensions       : [[$class           : 'RelativeTargetDirectory',
                                             relativeTargetDir: 'debezium-connector-db2']],
                ])
            }
        }

        stage('Checkout - Upstream Strimzi') {
            when {
                expression { !params.PRODUCT_BUILD }
            }
            steps {
                checkout([
                        $class           : 'GitSCM',
                        branches         : [[name: "${STRZ_GIT_BRANCH}"]],
                        userRemoteConfigs: [[url: "${STRZ_GIT_REPOSITORY}"]],
                        extensions       : [[$class           : 'RelativeTargetDirectory',
                                             relativeTargetDir: 'strimzi']],
                ])
                script {
                    env.STRZ_RESOURCES = "${env.WORKSPACE}/strimzi/install/cluster-operator"
                }
            }
        }

        stage('Checkout - Downstream AMQ Streams') {
            when {
                expression { params.PRODUCT_BUILD }
            }
            steps {
                script {
                    env.STRZ_RESOURCES = "${env.WORKSPACE}/strimzi/install/cluster-operator"
                }
                copyArtifacts projectName: 'ocp-downstream-strimzi-prepare-job', filter: 'amq-streams-install-examples.zip', selector: lastSuccessful()
                unzip zipFile: 'amq-streams-install-examples.zip', dir: 'strimzi'
            }
        }

        stage('Checkout - Upstream Apicurio') {
            when {
                expression { !params.PRODUCT_BUILD && params.TEST_APICURIO_REGISTRY }
            }
            steps {
                error('Upstream Apicurio testing is not supported by the pipeline')
            }
        }

        stage('Checkout - Downstream Service registry') {
            when {
                expression { params.PRODUCT_BUILD && params.TEST_APICURIO_REGISTRY }
            }
            steps {
                script {
                    env.APIC_RESOURCES = "${env.WORKSPACE}/apicurio/install/"
                }
                copyArtifacts projectName: 'ocp-downstream-apicurio-prepare-job', filter: 'apicurio-registry-install-examples.zip', selector: lastSuccessful()
                unzip zipFile: 'apicurio-registry-install-examples.zip', dir: 'apicurio'
            }
        }

        stage('Configure - Apicurio') {
            when {
                expression { params.TEST_APICURIO_REGISTRY }
            }
            steps {
                script {
                    env.OCP_PROJECT_REGISTRY = "debezium-${BUILD_NUMBER}-registry"
                }
                withCredentials([
                        usernamePassword(credentialsId: "${OCP_CREDENTIALS}", usernameVariable: 'OCP_USERNAME', passwordVariable: 'OCP_PASSWORD'),
                        usernamePassword(credentialsId: "${QUAY_CREDENTIALS}", usernameVariable: 'QUAY_USERNAME', passwordVariable: 'QUAY_PASSWORD'),

                ]) {
                    sh '''
                    set -x            
                    oc login ${OCP_URL} -u "${OCP_USERNAME}" --password="${OCP_PASSWORD}" --insecure-skip-tls-verify=true >/dev/null
                    oc new-project ${OCP_PROJECT_REGISTRY}
                    '''
                    sh '''
                    set -x
                    cat ${APIC_RESOURCES}/install.yaml | grep "namespace: apicurio-registry-operator-namespace" -A5 -B5
                    sed -i "s/namespace: apicurio-registry-operator-namespace/namespace: ${OCP_PROJECT_REGISTRY}/" ${APIC_RESOURCES}/install.yaml
                    cat ${APIC_RESOURCES}/install.yaml | grep "namespace: ${OCP_PROJECT_REGISTRY}" -A5 -B5
                    oc delete -f ${APIC_RESOURCES} -n ${OCP_PROJECT_REGISTRY} --ignore-not-found
                    oc create -f ${APIC_RESOURCES} -n ${OCP_PROJECT_REGISTRY}
                    '''
                }
            }
        }

        stage('Configure') {
            steps {
                script {
                    env.OCP_PROJECT_DEBEZIUM = "debezium-${BUILD_NUMBER}"
                    env.OCP_PROJECT_MYSQL = "debezium-${BUILD_NUMBER}-mysql"
                    env.OCP_PROJECT_POSTGRESQL = "debezium-${BUILD_NUMBER}-postgresql"
                    env.OCP_PROJECT_SQLSERVER = "debezium-${BUILD_NUMBER}-sqlserver"
                    env.OCP_PROJECT_MONGO = "debezium-${BUILD_NUMBER}-mongo"
                    env.OCP_PROJECT_DB2 = "debezium-${BUILD_NUMBER}-db2"
                    env.OCP_PROJECT_ORACLE = "debezium-${BUILD_NUMBER}-oracle"

                    env.MVN_PROFILE_PROD = params.PRODUCT_BUILD ? "-Pproduct" : ""

                    env.TEST_CONNECT_STRZ_BUILD = params.TEST_CONNECT_STRZ_IMAGE ? false : true

                    env.IMAGE_TAG_SUFFIX="${BUILD_NUMBER}"
                    env.MVN_IMAGE_CONNECT_STRZ = params.IMAGE_CONNECT_STRZ ? "-Dimage.kc=${params.IMAGE_CONNECT_STRZ}" : ""
                    env.MVN_IMAGE_CONNECT_RHEL = params.IMAGE_CONNECT_RHEL ? "-Ddocker.image.kc=${params.IMAGE_CONNECT_RHEL}" : ""
                    env.MVN_IMAGE_DBZ_AS = params.IMAGE_DBZ_AS ? "-Dimage.as=${params.IMAGE_DBZ_AS}" : ""

                    env.MVN_TAGS = params.TEST_TAGS ? "-Dgroups=${params.TEST_TAGS}" : ""
                    env.MVN_TAGS_EXCLUDE = params.TEST_TAGS_EXCLUDE ? "-DexcludedGroups=${params.TEST_TAGS_EXCLUDE }" : ""

                    env.MVN_VERSION_KAFKA = params.TEST_VERSION_KAFKA ? "-Dversion.kafka=${params.TEST_VERSION_KAFKA}" : ""
                    env.MVN_VERSION_AS_DEBEZIUM = params.AS_VERSION_DEBEZIUM ? "-Das.debezium.version=${params.AS_VERSION_DEBEZIUM}" : ""
                    env.MVN_VERSION_AS_APICURIO = params.AS_VERSION_APICURIO ? "-Das.apicurio.version=${params.AS_VERSION_APICURIO}" : ""

                    env.ORACLE_ARTIFACT_VERSION='21.1.0.0'
                    env.ORACLE_ARTIFACT_DIR = "${env.HOME}/oracle-libs/21.1.0.0.0"
                }
                withCredentials([
                        usernamePassword(credentialsId: "${OCP_CREDENTIALS}", usernameVariable: 'OCP_USERNAME', passwordVariable: 'OCP_PASSWORD'),
                        usernamePassword(credentialsId: "${QUAY_CREDENTIALS}", usernameVariable: 'QUAY_USERNAME', passwordVariable: 'QUAY_PASSWORD'),

                ]) {
                    sh '''
                    set -x
                    cd ${ORACLE_ARTIFACT_DIR}
                    mvn install:install-file -DgroupId=com.oracle.instantclient -DartifactId=ojdbc8 -Dversion=${ORACLE_ARTIFACT_VERSION} -Dpackaging=jar -Dfile=ojdbc8.jar
                    mvn install:install-file -DgroupId=com.oracle.instantclient -DartifactId=xstreams -Dversion=${ORACLE_ARTIFACT_VERSION} -Dpackaging=jar -Dfile=xstreams.jar
                    '''
                    sh '''
                    set -x            
                    oc login ${OCP_URL} -u "${OCP_USERNAME}" --password="${OCP_PASSWORD}" --insecure-skip-tls-verify=true >/dev/null
                    oc new-project ${OCP_PROJECT_DEBEZIUM}
                    oc new-project ${OCP_PROJECT_MYSQL}
                    oc new-project ${OCP_PROJECT_POSTGRESQL}
                    oc new-project ${OCP_PROJECT_SQLSERVER}
                    oc new-project ${OCP_PROJECT_MONGO}
                    oc new-project ${OCP_PROJECT_DB2}
                    oc new-project ${OCP_PROJECT_ORACLE}
                    '''
                    sh '''
                    set -x
                    sed -i "s/namespace: .*/namespace: ${OCP_PROJECT_DEBEZIUM}/" strimzi/install/cluster-operator/*RoleBinding*.yaml
                    oc delete -f ${STRZ_RESOURCES} -n ${OCP_PROJECT_DEBEZIUM} --ignore-not-found
                    oc create -f ${STRZ_RESOURCES} -n ${OCP_PROJECT_DEBEZIUM}
                    '''
                    sh '''
                    set -x
                    oc project ${OCP_PROJECT_SQLSERVER}
                    oc adm policy add-scc-to-user anyuid system:serviceaccount:${OCP_PROJECT_SQLSERVER}:default
                    oc project ${OCP_PROJECT_MONGO}
                    oc adm policy add-scc-to-user anyuid system:serviceaccount:${OCP_PROJECT_MONGO}:default
                    oc project ${OCP_PROJECT_DB2}
                    oc adm policy add-scc-to-user anyuid system:serviceaccount:${OCP_PROJECT_DB2}:default
                    oc adm policy add-scc-to-user privileged system:serviceaccount:${OCP_PROJECT_DB2}:default
                    oc project ${OCP_PROJECT_ORACLE}
                    oc adm policy add-scc-to-user anyuid system:serviceaccount:${OCP_PROJECT_ORACLE}:default
                    '''
                    sh '''
                    set -x
                    docker login -u=${QUAY_USERNAME} -p=${QUAY_PASSWORD} quay.io
                    '''
                }
            }
        }

        stage('Build') {
            steps {
                sh '''
                set -x
                cd ${WORKSPACE}/debezium
                mvn clean install -DskipTests -DskipITs
                '''
            }
        }

        stage('Build -- Upstream') {
            when {
                expression { !params.PRODUCT_BUILD }
            }
            steps {
//              Build DB2 Connector
                sh '''
                set -x
                cd ${WORKSPACE}/debezium-connector-db2
                mvn clean install -DskipTests -DskipITs -Passembly
                '''
//              Build Oracle connector
                sh '''
                set -x
                cd ${WORKSPACE}/debezium
                mvn install -Passembly,oracle -DskipTests -DskipITs 
                '''
            }
        }


        stage('Build & Deploy AS Image -- Upstream') {
            when {
                expression { !params.PRODUCT_BUILD && !params.IMAGE_CONNECT_STRZ && !params.IMAGE_DBZ_AS  }
            }
            steps {
                withCredentials([
                        usernamePassword(credentialsId: "${QUAY_CREDENTIALS}", usernameVariable: 'QUAY_USERNAME', passwordVariable: 'QUAY_PASSWORD'),
                ]) {
                    sh '''
                    set -x 
                    cd ${WORKSPACE}/debezium
                    docker login -u=${QUAY_USERNAME} -p=${QUAY_PASSWORD} quay.io
                    
                    mvn install -pl debezium-testing/debezium-testing-system -Pimages,oracle-image,oracleITs \\
                    -DskipTests \\
                    -DskipITs \\
                    -Dimage.build.kc.skip=true \\
                    -Dimage.tag.suffix="${IMAGE_TAG_SUFFIX}"
                    '''
                }
            }
        }

        stage('Enable debug') {
            when {
                expression { params.DEBUG_MODE }
            }
            steps {
                script {
                    env.MAVEN_OPTS="-DforkCount=0 -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=*:5005"
                }
            }
        }

        stage('Test') {
            steps {
                withCredentials([
                        usernamePassword(credentialsId: "${OCP_CREDENTIALS}", usernameVariable: 'OCP_USERNAME', passwordVariable: 'OCP_PASSWORD'),
                        file(credentialsId: "${PULL_SECRET}", variable: 'SECRET_PATH'),
                ]) {
                    sh '''
                    set -x
                    cd ${WORKSPACE}/debezium
                    mvn install -pl debezium-testing/debezium-testing-system -PsystemITs,oracleITs \\
                    ${MVN_PROFILE_PROD} \\
                    -Docp.project.debezium="${OCP_PROJECT_DEBEZIUM}" \\
                    -Docp.project.mysql="${OCP_PROJECT_MYSQL}"  \\
                    -Docp.project.postgresql="${OCP_PROJECT_POSTGRESQL}" \\
                    -Docp.project.sqlserver="${OCP_PROJECT_SQLSERVER}"  \\
                    -Docp.project.mongo="${OCP_PROJECT_MONGO}" \\
                    -Docp.project.db2="${OCP_PROJECT_DB2}" \\
                    -Docp.project.oracle="${OCP_PROJECT_ORACLE}" \\
                    -Docp.username="${OCP_USERNAME}" \\
                    -Docp.password="${OCP_PASSWORD}" \\
                    -Docp.url="${OCP_URL}" \\
                    -Docp.pull.secret.paths="${SECRET_PATH}" \\
                    -Dstrimzi.kc.build=${TEST_CONNECT_STRZ_BUILD} \\
                    -Dimage.tag.suffix="${IMAGE_TAG_SUFFIX}" \\
                    -Dtest.wait.scale="${TEST_WAIT_SCALE}" \\
                    ${MVN_IMAGE_CONNECT_STRZ} \\
                    ${MVN_IMAGE_CONNECT_RHEL} \\
                    ${MVN_IMAGE_DBZ_AS} \\
                    ${MVN_VERSION_KAFKA} \\
                    ${MVN_VERSION_AS_DEBEZIUM} \\
                    ${MVN_VERSION_AS_APICURIO} \\
                    ${MVN_TAGS} \\
                    ${MVN_TAGS_EXCLUDE}
                    '''
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts '**/target/failsafe-reports/*.xml'
            junit '**/target/failsafe-reports/*.xml'

            mail to: MAIL_TO, subject: "Debezium OpenShift test run #${BUILD_NUMBER} finished", body: """
OpenShift interoperability test run ${BUILD_URL} finished with result: ${currentBuild.currentResult}
"""
        }
        success {
            sh '''
            for project in $(oc projects | grep -Po "debezium-${BUILD_NUMBER}.*"); do
                oc delete project "${project}" --ignore-not-found
            done
            '''
        }
    }
}
