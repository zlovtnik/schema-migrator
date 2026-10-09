#!/bin/sh
set -eu
apt-get -o Dir::Etc::sourceparts="-" update
apt-get install -y --no-install-recommends curl bash "$@"
archive="$(mktemp)"
trap 'rm -f "$archive"' EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
curl -fsSL https://github.com/sbt/sbt/releases/download/v1.12.14/sbt-1.12.14.tgz -o "$archive"
tar xzf "$archive" -C /opt
ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt
