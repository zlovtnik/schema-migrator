#!/bin/sh
set -eu
sh /workspace/scripts/ci/tasks/install-sbt.sh
sbt -Dsbt.supershell=false "Test / testFull"
