// ocp39-OA-base.groovy

// Set Job properties and triggers
properties([
parameters([string(defaultValue: 'v3.9', description: 'OpenShift version to deploy', name: 'OCP3_VERSION', trim: false), 
// TODO: reset to ci values
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'dgrigore_aws_access_key_id', description: 'EC2 access key ID for auth purposes', name: 'EC2_ACCESS_KEY_ID', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'dgrigore_aws_secret_access_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_SECRET_ACCESS_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl', defaultValue: 'dgrigore_ec2_key', description: 'EC2 private key needed to access instances, from Jenkins credentials store', name: 'EC2_PRIV_KEY', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_rhel_sub_user', description: 'RHEL Openshift subscription account username', name: 'EC2_SUB_USER', required: true),
credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', defaultValue: 'ci_rhel_sub_pass', description: 'RHEL Openshift subscription account password', name: 'EC2_SUB_PASS', required: true),
string(defaultValue: 'libra', description: 'EC2 SSH key name to deploy on instances for remote access ', name: 'EC2_KEY', trim: false),
string(defaultValue: 'eu-west-1', description: 'EC2 region to deploy instances', name: 'EC2_REGION', trim: false),
string(defaultValue: 'https://github.com/Danil-Grigorev/mig-ci.git', description: 'MIG CI repo URL to checkout', name: 'MIG_CI_REPO', trim: false), // TODO: change to upstream
string(defaultValue: 'oa-to-pipelines', description: 'MIG CI repo branch to checkout', name: 'MIG_CI_BRANCH', trim: false),
booleanParam(defaultValue: true, description: 'EC2 terminate instances after build', name: 'EC2_TERMINATE_INSTANCES')]),
pipelineTriggers([cron('@midnight')])])

// true/false build parameter that defines if we terminate instances once build is done
def EC2_TERMINATE_INSTANCES = params.EC2_TERMINATE_INSTANCES

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

        common_stages.setup_OCP3_OA().call()
    
        common_stages.deployOCP3_OA().call()

        common_stages.load_sample_data().call()
        
        common_stages.sanity_checks().call()
 
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e
    } finally {
        // Success or failure, always send notifications
        common_stages.notifyBuild(currentBuild.result)
        stage('Clean Up Environment') {
            common_stages.teardown_OCP3_OA()
            cleanWs cleanWhenFailure: false, notFailBuild: true
        }
    }
}