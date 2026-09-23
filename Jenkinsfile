pipeline {
  agent any
  options {
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
      steps {
        deleteDir()
        checkout scm
      }
    }
    stage('Test') {
      parallel {
        stage('Backend') {
          steps {
            sh '''
              set -eu
              tar -cf - . | docker run --rm -i -w /workspace \
                -v /var/run/docker.sock:/var/run/docker.sock azul/zulu-openjdk:21 \
                sh -c 'tar --no-same-owner -xf - && apt-get -o Dir::Etc::sourceparts="-" update && apt-get install -y --no-install-recommends curl bash && curl -fsSL https://github.com/sbt/sbt/releases/download/v1.12.14/sbt-1.12.14.tgz | tar xz -C /opt && ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt && sbt -Dsbt.supershell=false "Test / testFull"'
            '''
          }
        }
        stage('UI') {
          steps {
            sh '''
              set -eu
              tar -cf - schema-migrator-ui | docker run --rm -i -w /workspace/schema-migrator-ui oven/bun:1.3.11 \
                sh -c 'mkdir -p /workspace && tar --no-same-owner -C /workspace -xf - && bun install --frozen-lockfile && bun run test && bun run build'
            '''
          }
        }
      }
    }
    stage('Publish immutable images') {
      when { branch 'main' }
      steps {
        sh '''
          set -eu
          test -n "${CI_REGISTRY:-}"
          mkdir -p artifacts
          revision="$(git rev-parse HEAD)"
          if docker context inspect "$DOCKER_CONTEXT_NAME" >/dev/null 2>&1; then
            docker context rm --force "$DOCKER_CONTEXT_NAME" >/dev/null
          fi
          docker context create "$DOCKER_CONTEXT_NAME" \
            --docker "host=$DOCKER_HOST,ca=$DOCKER_CERT_PATH/ca.pem,cert=$DOCKER_CERT_PATH/cert.pem,key=$DOCKER_CERT_PATH/key.pem" >/dev/null
          docker_cmd() {
            env -u DOCKER_HOST -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH \
              DOCKER_CONTEXT="$DOCKER_CONTEXT_NAME" docker "$@"
          }
          printf '[registry."%s"]\n  http = true\n  insecure = true\n' "$CI_REGISTRY" > artifacts/buildkitd.toml
          if ! docker_cmd buildx inspect "$BUILDER" >/dev/null 2>&1; then
            docker_cmd buildx create --name "$BUILDER" --driver docker-container \
              --driver-opt network=host --buildkitd-config artifacts/buildkitd.toml >/dev/null
          fi
          docker_cmd buildx inspect "$BUILDER" --bootstrap >/dev/null
          docker_cmd buildx build --builder "$BUILDER" --platform linux/amd64 \
            --file Dockerfile.backend --tag "$CI_REGISTRY/schema-migrator-backend:$revision" \
            --metadata-file artifacts/schema-migrator-backend.json --push .
          docker_cmd buildx build --builder "$BUILDER" --platform linux/amd64 \
            --file frontend/Dockerfile --tag "$CI_REGISTRY/schema-migrator-ui:$revision" \
            --metadata-file artifacts/schema-migrator-ui.json --push .
        '''
        archiveArtifacts artifacts: 'artifacts/*.json', fingerprint: true
      }
    }
  }
}
