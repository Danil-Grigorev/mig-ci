// parallel-base.groovy

// Set Job properties and triggers
properties([disableConcurrentBuilds(),
parameters([string(defaultValue: 'v3.11', description: 'OCP3 version to deploy', name: 'OCP3_VERSION', trim: false),
string(defaultValue: 'v4.1', description: 'OCP4 version to deploy', name: 'OCP4_VERSION', trim: false),
string(description: 'Cluster name to deploy', name: 'CLUSTER_NAME', trim: false),
string(defaultValue: 'jmatthew@redhat.com', description: 'Email to register the deploymnet', name: 'EMAIL', trim: false),
string(defaultValue: '1', description: 'Master count for cluster deployment', name: 'NODE_COUNT', trim: false),
string(defaultValue: '1', description: 'Node count for cluster deployment', name: 'MASTER_COUNT', trim: false),
string(defaultValue: '.mg.dog8code.com', description: 'Zone suffix for instance hostname address', name: 'BASESUFFIX', trim: false),
string(defaultValue: 'Z2GE8CSGW2ZA8W', description: 'Zone id', name: 'HOSTZONEID', trim: false),
string(defaultValue: 'ocp-workshop', description: 'AgnosticD environment type to deploy', name: 'ENVTYPE', trim: false),
string(defaultValue: 'ci', description: 'EC2 SSH key name to deploy on instances for remote access ', name: 'EC2_KEY', trim: false),
string(defaultValue: 'eu-west-1', description: 'AWS region to deploy instances', name: 'AWS_REGION', trim: false),
string(defaultValue: 'agnosticd', description: 'Deployment type to choose', name: 'DEPLOYMENT_TYPE', trim: false),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'agnosticd_own_repo', description: 'Private repo address for openshift-ansible packages', name: 'AGND_REPO', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_access_key_id', description: 'EC2 access key ID for auth purposes', name: 'EC2_ACCESS_KEY_ID', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_aws_secret_access_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_SECRET_ACCESS_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_ec2_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_PRIV_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_pub_key', description: 'EC2 public key needed for agnosticd instances', name: 'EC2_PUB_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_rhel_sub_user', description: 'RHEL Openshift subscription account username', name: 'EC2_SUB_USER', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_rhel_sub_pass', description: 'RHEL Openshift subscription account password', name: 'EC2_SUB_PASS', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'ci_pull_secret', description: 'Pull secret needed for OCP4 deployments', name: 'OCP4_PULL_SECRET', required: true),
booleanParam(defaultValue: true, description: 'Clean up workspace after build', name: 'CLEAN_WORKSPACE'),
booleanParam(defaultValue: true, description: 'EC2 terminate instances after build', name: 'EC2_TERMINATE_INSTANCES')])])

// true/false build parameter that defines if we terminate instances once build is done
def EC2_TERMINATE_INSTANCES = params.EC2_TERMINATE_INSTANCES
// true/false build parameter that defines if we cleanup workspace once build is done
def CLEAN_WORKSPACE = params.CLEAN_WORKSPACE

def common_stages
def utils

steps_finished = []

echo "Running job ${env.JOB_NAME}, build ${env.BUILD_ID} on ${env.JENKINS_URL}"
echo "Build URL ${env.BUILD_URL}"
echo "Job URL ${env.JOB_URL}"

node {
    try {
        checkout scm
        common_stages = load "${env.WORKSPACE}/pipeline/common_stages.groovy"
        utils = load "${env.WORKSPACE}/pipeline/utils.groovy"

        utils.notifyBuild('STARTED')

        stage('Setup for source and target cluster') {
            steps_finished << 'Setup for source and target cluster'

            utils.prepare_workspace("${env.OCP3_VERSION}", "${env.OCP4_VERSION}")
            utils.copy_private_keys()
            utils.copy_public_keys()
            utils.clone_related_repos()
            if (env.DEPLOYMENT_TYPE == 'agnosticd') {
                utils.prepare_agnosticd()
            } else if (env.DEPLOYMENT_TYPE != 'OA') {
                utils.prepare_origin3_dev()
            }
        }

        stage('Deploy clusters') {
            steps_finished << 'Deploy clusters'
            parallel deploy_OCP3: {
                if (env.DEPLOYMENT_TYPE == 'agnosticd') {
                    common_stages.deploy_ocp3_agnosticd().call()
                } else if (env.DEPLOYMENT_TYPE == 'OA') {
                    common_stages.deployOCP3_OA(CLUSTER_NAME).call()
                } else {
                    common_stages.deploy_origin3_dev(KUBECONFIG_OCP3).call()
                }
            }, deploy_OCP4: {
                common_stages.deployOCP4().call()
            }, deploy_NFS: {
                common_stages.deploy_NFS().call()
            }
            failFast: true
        }

        common_stages.provision_pvs().call()

        common_stages.deploy_mig_controller_source(KUBECONFIG_OCP3, KUBECONFIG_OCP4, true, true).call()

        common_stages.execute_migration(KUBECONFIG_OCP4).call()

    } catch (Exception ex) {
        currentBuild.result = "FAILED"
        println(ex.toString())
        throw ex
    } finally {
        // Success or failure, always send notifications
        utils.notifyBuild(currentBuild.result)
        stage('Clean Up Environment') {
            // Always attempt to terminate instances if EC2_TERMINATE_INSTANCES is true
            if (EC2_TERMINATE_INSTANCES) {
                        withCredentials([
                            string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
                            string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
                            ]) 
                        {
                            withEnv(['PATH+EXTRA=~/bin']) {
                                parallel destroy_OCP3: {
                                    if (env.DEPLOYMENT_TYPE == 'agnosticd') {
                                        utils.teardown_ocp3_agnosticd()
                                    } else if (env.DEPLOYMENT_TYPE == 'OA') {
                                        utils.teardown_OCP3_OA(CLUSTER_NAME)
                                    } else {
                                        utils.teardown_origin3_dev()
                                    }
                                }, destroy_OCP4: {
                                    utils.teardown_OCP4()
                                }, destroy_NFS: {
                                    if (env.DEPLOYMENT_TYPE != 'agnosticd') {
                                        utils.teardown_nfc()
                                    } else {
                                        echo 'Skipped'
                                    }
                                }, failFast: false
                            }
                        }
                }
            if (CLEAN_WORKSPACE) {
                cleanWs cleanWhenFailure: false, notFailBuild: true
            }
        }
    }
}
