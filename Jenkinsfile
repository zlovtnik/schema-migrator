pipeline {
  agent any
  options {
    buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
    disableConcurrentBuilds(abortPrevious: true)
    skipDefaultCheckout(true)
    timestamps()
    timeout(time: 90, unit: 'MINUTES')
  }
  environment {
    DOCKER_CONTEXT_NAME = 'schema-migrator-ci-docker'
    BUILDER = 'schema-migrator-http-host'
  }
  stages {
    stage('Checkout') {
      options { timeout(time: 10, unit: 'MINUTES') }
      steps {
        deleteDir()
        checkout scm
      }
    }
    stage('Test') {
      parallel {
        stage('Backend') {
          options { timeout(time: 60, unit: 'MINUTES') }
          steps {
            sh 'bash scripts/ci/backend.sh'
          }
        }
        stage('UI') {
          options { timeout(time: 60, unit: 'MINUTES') }
          steps {
            sh 'bash scripts/ci/ui.sh'
          }
        }
      }
    }
    stage('Publish immutable images') {
      when { branch 'main' }
      options { timeout(time: 60, unit: 'MINUTES') }
      steps {
        sh 'bash scripts/ci/publish.sh'
        archiveArtifacts artifacts: 'artifacts/*.json', fingerprint: true
      }
    }
  }
  post {
    always {
      timeout(time: 5, unit: 'MINUTES') {
        sh label: 'Reclaim CI resources', script: 'if [ -f scripts/ci/cleanup.sh ]; then bash scripts/ci/cleanup.sh; fi'
      }
    }
  }
}
