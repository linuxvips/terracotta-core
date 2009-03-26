#!/bin/sh

#  All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.

cd `dirname "$0"`/../../..
SANDBOX="`pwd`/sessions/configurator-sandbox/$1"
mkdir -p ../lib/dso-boot
../bin/make-boot-jar.sh -o ../lib/dso-boot -f "${SANDBOX}"/tc-config.xml
result=$?
if test $result -ne 0; then
  exit $result
fi
../bin/start-tc-server.sh -f "${SANDBOX}"/tc-config.xml
exit $?
