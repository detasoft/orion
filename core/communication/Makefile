# Make targets to run OpenSSL s_client in various modes
# NOTE: The following commands are added as requested.
# They run exactly as shown below.

.PHONY: dtls12 tls13_key_update_once tls13_key_update_continual

# DTLS 1.2 client connecting to localhost:4444
# Command:
#   openssl s_client -connect 127.0.0.1:4444 -dtls1_2
# Usage:
#   make dtls12

dtls12:
	openssl s_client -connect 127.0.0.1:4444 -dtls1_2

# TLS 1.3 client with single key update
# Command:
#   openssl s_client -connect host:port -tls1_3 -key_update once
# Usage:
#   make tls13_key_update_once

tls13_key_update_once:
	openssl s_client -connect host:port -tls1_3 -key_update once

# TLS 1.3 client with continual key updates
# Command:
#   openssl s_client -connect host:port -tls1_3 -key_update continual
# Usage:
#   make tls13_key_update_continual

tls13_key_update_continual:
	openssl s_client -connect host:port -tls1_3 -key_update continual
