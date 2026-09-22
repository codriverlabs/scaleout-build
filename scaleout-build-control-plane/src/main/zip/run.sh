#!/bin/bash
#
# Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
#
# Lambda handler for JVM mode on a managed runtime.
#
# With the AWS Lambda Web Adapter in front, the function's `handler` is the name of this script and
# AWS_LAMBDA_EXEC_WRAPPER=/opt/bootstrap points at the adapter, which starts this and then bridges the
# Lambda Runtime API to the HTTP server it brings up.
#
# The jar name below is FILTERED IN AT BUILD TIME from ${project.build.finalName}, not hardcoded.
# That is deliberate: express-compute-control-plane's equivalent script hardcodes
# `eks-dx-tenant-service-runner.jar` while its assembly produces `ecp-tenant-service-runner.jar`, so
# the two drifted apart silently at a rename. Deriving it removes the possibility.
set -euo pipefail

exec java \
  -XX:+UseSerialGC \
  -XX:TieredStopAtLevel=1 \
  -Dquarkus.http.port="${PORT:-8080}" \
  -jar "/var/task/${project.build.finalName}-runner.jar"
