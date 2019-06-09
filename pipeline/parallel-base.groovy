// parallel-base.groovy

// Set Job properties and triggers
properties([
parameters([string(defaultValue: 'v3.11', description: 'OCP3 version to deploy', name: 'OCP3_VERSION', trim: false),
string(defaultValue: 'v4.1', description: 'OCP4 version to deploy', name: 'OCP4_VERSION', trim: false),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_access_key_id', description: 'EC2 access key ID for auth purposes', name: 'EC2_ACCESS_KEY_ID', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_secret_access_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_SECRET_ACCESS_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_ec2_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_PRIV_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_rhel_sub_user', description: 'RHEL Openshift subscription account username', name: 'EC2_SUB_USER', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_rhel_sub_pass', description: 'RHEL Openshift subscription account password', name: 'EC2_SUB_PASS', required: true),
string(defaultValue: 'ci', description: 'EC2 SSH key name to deploy on instances for remote access ', name: 'EC2_KEY', trim: false),
string(defaultValue: 'eu-west-1', description: 'EC2 region to deploy instances', name: 'EC2_REGION', trim: false),
string(defaultValue: 'm4.large', description: 'EC2 instance type to deploy', name: 'EC2_INSTANCE_TYPE', trim: false),
string(defaultValue: 'jenkins-parallel-ci', description: 'Cluster names to deploy', name: 'CLUSTER_NAME', trim: false),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_pull_secret', description: 'Pull secret needed for OCP4 deployments', name: 'OCP4_PULL_SECRET', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_pub_key', description: 'EC2 public key needed for OCP4 instances', name: 'EC2_PUB_KEY', required: true),
string(defaultValue: 'https://github.com/Danil-Grigorev/mig-ci.git', description: 'MIG CI repo URL to checkout', name: 'MIG_CI_REPO', trim: false), // TODO: change to upstream
string(defaultValue: 'oa-to-pipelines', description: 'MIG CI repo branch to checkout', name: 'MIG_CI_BRANCH', trim: false),
string(defaultValue: 'https://github.com/fusor/mig-e2e.git', description: 'MIG E2E repo URL to checkout', name: 'MIG_E2E_REPO', trim: false),
string(defaultValue: 'master', description: 'MIG E2E repo branch to checkout', name: 'MIG_E2E_BRANCH', trim: false),
booleanParam(defaultValue: true, description: 'Clean up workspace after build', name: 'CLEAN_WORKSPACE'),
booleanParam(defaultValue: true, description: 'EC2 terminate instances after build', name: 'EC2_TERMINATE_INSTANCES')])])

// true/false build parameter that defines if we terminate instances once build is done
def EC2_TERMINATE_INSTANCES = params.EC2_TERMINATE_INSTANCES
// true/false build parameter that defines if we cleanup workspace once build is done
def CLEAN_WORKSPACE = params.CLEAN_WORKSPACE

def common_stages

steps_finished = []

echo "Running job ${env.JOB_NAME}, build ${env.BUILD_ID} on ${env.JENKINS_URL}"
echo "Build URL ${env.BUILD_URL}"
echo "Job URL ${env.JOB_URL}"

node {
    try {
        checkout scm

        common_stages = load "${env.WORKSPACE}/pipeline/common_stages.groovy"

        common_stages.notifyBuild('STARTED')
        stage('Prepare Build Environment') {
            steps_finished << 'Prepare Build Environment'
            sh 'printenv'

            // Prepare EC2 key for ansible consumption
            KEYS_DIR = "${env.WORKSPACE}" + '/keys'
            sh "mkdir -p ${KEYS_DIR}"
            sh "mkdir -p ${env.WORKSPACE}/kubeconfigs"

            withCredentials([file(credentialsId: "$EC2_PRIV_KEY", variable: "SSH_PRIV_KEY")]) {

                sh "cat ${SSH_PRIV_KEY} > ${KEYS_DIR}/${EC2_KEY}.pem"
                sh "chmod 600 ${KEYS_DIR}/${EC2_KEY}.pem"
            }

            // Prepare pull secret
            withCredentials([file(credentialsId: "$OCP4_PULL_SECRET", variable: "PULL_SECRET")]) {
                sh "cat ${PULL_SECRET} > ${KEYS_DIR}/pull-secret"
            }

            // Prepare EC2 pub key for ansible consumption
            withCredentials([file(credentialsId: "$EC2_PUB_KEY", variable: "SSH_PUB_KEY")]) {
                sh "cat ${SSH_PUB_KEY} > ${KEYS_DIR}/${EC2_KEY}.pub"
            }

            echo 'Cloning mig-ci repo'
            checkout([$class: 'GitSCM', branches: [[name: "*/$MIG_CI_BRANCH"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'mig-ci']], submoduleCfg: [], userRemoteConfigs: [[url: "$MIG_CI_REPO"]]])

            // Set enviroment variables
            CLUSTER_NAME = "${CLUSTER_NAME}"
            KUBECONFIG_TMP = "${env.WORKSPACE}/kubeconfigs/kubeconfig"

            // Target kubeconfig locations
            KUBECONFIG_OCP3 = "${env.WORKSPACE}/kubeconfigs/ocp-$OCP3_VERSION-kubeconfig"
            KUBECONFIG_OCP4 = "${env.WORKSPACE}/kubeconfigs/ocp-$OCP4_VERSION-kubeconfig"

            sh "rm -f ${KUBECONFIG_TMP}"

            echo 'Cloning mig-e2e repo'
            checkout([$class: 'GitSCM', branches: [[name: "*/$MIG_E2E_BRANCH"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'mig-e2e']], submoduleCfg: [], userRemoteConfigs: [[url: "$MIG_E2E_REPO"]]])

            echo 'Cloning ocp-mig-test-data repo'
            checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'ocp-mig-test-data']], submoduleCfg: [], userRemoteConfigs: [[url: 'https://github.com/fusor/ocp-mig-test-data.git']]])

        }

        stage('Deploy clusters') {
            steps_finished << 'Deploy clusters'
            parallel deploy_OCP3: {
                common_stages.deployOCP3_OA().call()
            }, deploy_OCP4: {
                common_stages.deployOCP4().call()
            }, deploy_NFS: {
                stage('Configure NFS storage on OCP3') {
                    steps_finished << 'Configure NFS storage on OCP3'
                    ansiColor('xterm') {
                        ansiblePlaybook(
                            playbook: 'nfs_server_deploy.yml',
                            hostKeyChecking: false,
                            unbuffered: true,
                            colorized: true)
                    }
                }
            }
            failFast: true
        }

        stage('Deploy Velero and configure S3 storage on OCP3') {
            steps_finished << 'Deploy Velero and configure S3 storage on OCP3'
            withCredentials([
                string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
                string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
                ]) 
            {
                
                withEnv(['PATH+EXTRA=~/bin', "KUBECONFIG=${KUBECONFIG_OCP3}"]) {
                    dir('mig-ci') {
                        ansiColor('xterm') {
                            ansiblePlaybook(
                                playbook: 'setup_velero.yml',
                                extras: "-e cluster_version=3",
                                hostKeyChecking: false,
                                unbuffered: true,
                                colorized: true)
                        }
                    }
                }
            }
        }

        stage('Run OCP3 Sanity Checks') {
            steps_finished << 'Run OCP3 Sanity Checks'
            dir('mig-ci') {
                withEnv(['PATH+EXTRA=~/bin', "KUBECONFIG=${KUBECONFIG_OCP3}"]) {
                    ansiColor('xterm') {
                        ansiblePlaybook(
                            playbook: 'ocp_sanity_check.yml',
                            tags: 'router',
                            extras: '',
                            hostKeyChecking: false,
                            unbuffered: true,
                            colorized: true)
                    }
                }
            }
	}


        stage('Deploy Velero and configure S3 storage on OCP4') {
            steps_finished << 'Deploy Velero and configure S3 storage on OCP4'
            withCredentials([
                string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
                string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
                ]) 
            {
                withEnv(['PATH+EXTRA=~/bin']) {
                    dir('mig-ci') {
                        ansiColor('xterm') {
                            ansiblePlaybook(
                                playbook: 'setup_velero.yml',
                                extras: "-e cluster_version=4",
                                hostKeyChecking: false,
                                unbuffered: true,
                                colorized: true)
                        }
                    }
                }
            }
        }

        // stage('Run mig-e2e tests') {

        // // placeholder for mig-e2e playbooks
        //     // Create msql-pvc with backup on OCP3
        //     withEnv(['PATH+EXTRA=~/bin']) {
        //         dir('ocp-mig-test-data') {
        //             ansiColor('xterm') {
        //                 ansiblePlaybook(
        //                     playbook: 'mysql-pvc.yml',
        //                     extras: "-e with_backup=true -e with_restore=false -e with_data=false -e with_resources=true",
        //                     hostKeyChecking: false,
        //                     unbuffered: true,
        //                     colorized: true)
        //             }
        //         }
        //     }
            
        //     // Restore on OCP4
        //     withEnv(['PATH+EXTRA=~/bin']) {
        //         dir('ocp-mig-test-data') {
        //             ansiColor('xterm') {
        //                 ansiblePlaybook(
        //                     playbook: 'mysql-pvc.yml',
        //                     extras: "-e with_backup=false -e with_restore=true -e with_data=false -e with_resources=false",
        //                     hostKeyChecking: false,
        //                     unbuffered: true,
        //                     colorized: true)
        //             }
        //         }
        //     }
        // }
        
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e
    } finally {
        // Success or failure, always send notifications
        common_stages.notifyBuild(currentBuild.result)
        stage('Clean Up Environment') {
            // Always attempt to terminate instances if EC2_TERMINATE_INSTANCES is true
            if (EC2_TERMINATE_INSTANCES) {
                        withCredentials([
                            string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
                            string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
                            ]) 
                        {
                            withEnv(['PATH+EXTRA=~/bin', "AWS_REGION=${EC2_REGION}"]) {
                                dir('mig-ci') {
                                    parallel destroy_OCP3: {
                                        ansiColor('xterm') {
                                                ansiblePlaybook(
                                                    playbook: 'destroy_ocp3_cluster.yml',
                                                    extras: "-e prefix=${env.CLUSTER_NAME}",
                                                    hostKeyChecking: false,
                                                    unbuffered: true,
                                                    colorized: true)
                                        }
                                    }, destroy_OCP4: {
                                        common_stages.teardown_OCP4()
                                    }, destroy_NFS: {
                                        ansiColor('xterm') {
                                                ansiblePlaybook(
                                                    playbook: 'nfs_server_destroy.yml',
                                                    hostKeyChecking: false,
                                                    unbuffered: true,
                                                    colorized: true)
                                        }
                                    }, failFast: false
                                }
                            }
                        }
                }
            if (CLEAN_WORKSPACE) {  
                cleanWs cleanWhenFailure: false, notFailBuild: true
            }
        }
    }
}