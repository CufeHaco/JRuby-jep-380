#!/bin/bash
set -euo pipefail
rm -f /tmp/test_*.sock /tmp/jep380-demo.*.sock
echo "java $(java -version 2>&1 | head -n1)"
command -v jruby >/dev/null && jruby --version || echo "jruby not on PATH"
jruby test_sockets.rb
ruby demo.rb
