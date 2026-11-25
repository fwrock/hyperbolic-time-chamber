#!/bin/sh
set -eu
rpk redpanda config set redpanda.admin '[{"address":"0.0.0.0","port":19644}]'
exec rpk redpanda start "$@"
