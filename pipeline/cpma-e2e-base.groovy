properties([
parameters([
string(defaultValue: 'ec2-user', description: 'SSH login', name: 'CPMA_SSHLOGIN', trim: false),
string(defaultValue: '22', description: 'SSH port', name: 'CPMA_SSHPORT', trim: false),
string(defaultValue: 'master', description: 'CPMA branch to checkout', name: 'CPMA_BRANCH', trim: false),
string(defaultValue: 'https://github.com/fusor/cpma.git', description: 'CPMA repo to clone', name: 'CPMA_REPO', trim: false),
string(defaultValue: 'v3.11', description: 'OCP3 version to deploy', name: 'OCP3_VERSION', trim: false),
string(defaultValue: 'v4.1', description: 'OCP4 version to deploy', name: 'OCP4_VERSION', trim: false),
string(defaultValue: 'e2e-cpma-manifests', description: 'Cluster name to deploy', name: 'CLUSTER_NAME', trim: false),
string(defaultValue: 'mig-ci@redhat.com', description: 'Email to register the deployment', name: 'EMAIL', trim: false),
string(defaultValue: '1', description: 'OCP3 master instance count', name: 'OCP3_MASTER_INSTANCE_COUNT', trim: false),
string(defaultValue: '1', description: 'OCP3 worker instance count', name: 'OCP3_WORKER_INSTANCE_COUNT', trim: false),
string(defaultValue: 'm5.large', description: 'OCP3 master instance type', name: 'OCP3_MASTER_INSTANCE_TYPE', trim: false),
string(defaultValue: 'm5.xlarge', description: 'OCP3 worker instance type', name: 'OCP3_WORKER_INSTANCE_TYPE', trim: false),
string(defaultValue: '1', description: 'OCP4 master instance count', name: 'OCP4_MASTER_INSTANCE_COUNT', trim: false),
string(defaultValue: '1', description: 'OCP4 worker instance count', name: 'OCP4_WORKER_INSTANCE_COUNT', trim: false),
string(defaultValue: 'm5.xlarge', description: 'OCP4 master instance type', name: 'OCP4_MASTER_INSTANCE_TYPE', trim: false),
string(defaultValue: 'm5.xlarge', description: 'OCP4 worker instance type', name: 'OCP4_WORKER_INSTANCE_TYPE', trim: false),
string(defaultValue: '.mg.dog8code.com', description: 'Zone suffix for instance hostname address', name: 'BASESUFFIX', trim: false),
string(defaultValue: 'Z2GE8CSGW2ZA8W', description: 'Zone id', name: 'HOSTZONEID', trim: false),
string(defaultValue: 'ocp-workshop', description: 'AgnosticD environment type to deploy', name: 'ENVTYPE', trim: false),
string(defaultValue: 'ci', description: 'EC2 SSH key name to deploy on instances for remote access ', name: 'EC2_KEY', trim: false),
string(defaultValue: 'eu-central-1', description: 'AWS region to deploy instances', name: 'AWS_REGION', trim: false),
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

steps_finished = []

node {
  goroot = tool name: 'go 1.12.7', type: 'go'
  build_workspace = "${WORKSPACE}-${BUILD_NUMBER}"
  data_dir = "${build_workspace}/data"
  manifests_dir = "${data_dir}/manifests"

  ws("${build_workspace}") {
  try {
    checkout scm
    common_stages = load "${env.WORKSPACE}/pipeline/common_stages.groovy"
    utils = load "${env.WORKSPACE}/pipeline/utils.groovy"

    utils.notifyBuild('STARTED')

    stage('Setup CPMA, source and target cluster') {
        steps_finished << 'Setup CPMA, source and target cluster'

        utils.prepare_workspace(OCP3_VERSION, OCP4_VERSION)
        utils.copy_private_keys()
        utils.copy_public_keys()
        utils.prepare_agnosticd()
        utils.prepare_cpma(CPMA_REPO, CPMA_BRANCH)
    }

    // stage('Deploy clusters') {
    //     steps_finished << 'Deploy clusters'
    //     parallel deploy_OCP3: {
    //         common_stages.deploy_ocp3_agnosticd(SOURCE_KUBECONFIG).call()
    //     }, deploy_OCP4: {
    //         common_stages.deployOCP4(TARGET_KUBECONFIG).call()
    //     },
    //     failFast: true
    // }

    stage('Run CPMA') {
      steps_finished << 'Run CPMA report and manifest extraction'
      // Extracting the context name from the cluster
      withEnv (["KUBECONFIG=${SOURCE_KUBECONFIG}"]) {
        CURRENTCONTEXT = sh (
          script: "${OC_BINARY} whoami --show-context | cut -d '/' -f 2",
          returnStdout: true
        ).trim()
      }
      dir('cpma') {
        withEnv([
            "GOROOT=${goroot}",
            "PATH+GO=${goroot}/bin",
            "KUBECONFIG=/var/lib/jenkins/testing/kubeconfig",
            "CPMA_SSHPRIVATEKEY=/var/lib/jenkins/testing/libra.pem",
            "CPMA_HOSTNAME=master1.dgr-ui-source-3.mg.dog8code.com",
            "CPMA_CLUSTERNAME=master1-dgr-ui-source-3-mg-dog8code-com:443",
            "CPMA_CONFIGSOURCE=remote",
            "CPMA_WORKDIR=${data_dir}",
            "CPMA_SAVECONFIG=false",
            "CPMA_ETCDCONFIGFILE=/etc/etcd/etcd.conf",
            "CPMA_MASTERCONFIGFILE=/etc/origin/master/master-config.yaml",
            "CPMA_REGISTRIESCONFIGFILE=/etc/containers/registries.conf",
            "CPMA_NODECONFIGFILE=/etc/origin/node/node-config.yaml",
            "CPMA_CRIOCONFIGFILE=/etc/crio/crio.conf",
            "CPMA_SSHLOGIN=ec2-user",
            "CPMA_SSHPORT=22"]) {
          sh 'env | grep CPMA'
          sh "make build && ${WORKSPACE}/cpma/bin/cpma -i --debug --verbose"
        }
      }
    }

    stage('Apply manifests on a target cluster') {
      steps_finished << 'Apply manifests on a target cluster'
      withEnv(["KUBECONFIG=/var/lib/jenkins/testing/kubeconfig-target"]) {
        sh "find ${manifests_dir} -type f | xargs -I{} /var/lib/jenkins/workspace/cpma-e2e-manifests-63/bin/oc apply -f {}"
      }
    }
  } catch (Exception ex) {
      currentBuild.result = "FAILED"
      println(ex.toString())
  } finally {
    utils.notifyBuild(currentBuild.result)
    if (CLEAN_WORKSPACE) {
      withCredentials([
        string(credentialsId: "$EC2_ACCESS_KEY_ID", variable: 'AWS_ACCESS_KEY_ID'),
        string(credentialsId: "$EC2_SECRET_ACCESS_KEY", variable: 'AWS_SECRET_ACCESS_KEY')
        ])
      {
        parallel destroy_OCP3: {
          utils.teardown_ocp3_agnosticd()
        }, destroy_OCP4: {
          utils.teardown_OCP4()
        }, failFast: false
      }
      cleanWs cleanWhenFailure: false, notFailBuild: true
    }
  }
  }
}
